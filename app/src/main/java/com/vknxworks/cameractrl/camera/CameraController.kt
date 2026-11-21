package com.vknxworks.cameractrl.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.night.NightExposure
import com.vknxworks.cameractrl.night.NightJob
import com.vknxworks.cameractrl.night.NightPreset
import com.vknxworks.cameractrl.scan.Decoder
import com.vknxworks.cameractrl.scan.Scanned
import com.vknxworks.cameractrl.ui.common.hasPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

enum class PhotoFormat { JPEG, RAW_JPEG, RAW }

/** Flash for photos. AUTO lets auto exposure decide; ON fires for every photo. The torch is separate. */
enum class FlashMode { OFF, AUTO, ON }

/**
 * What the session is set up for. NIGHT: preview plus a full-size YUV stream for the burst. SCAN: preview
 * plus a small YUV stream for the code reader. INTERVAL: JPEG only (the run itself is in IntervalRunner).
 */
enum class Mode { PHOTO, NIGHT, VIDEO, INTERVAL, SCAN }

/** Exposure, focus, white balance, torch and zoom. [auto] off means ISO and shutter are set by hand. */
data class Manual(
    val auto: Boolean = true,
    val iso: Int = 100,
    val exposureNs: Long = 10_000_000L,
    /** Focus in diopters (0 = infinity); null = autofocus. */
    val focus: Float? = null,
    val ev: Int = 0,
    val awb: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val torch: Boolean = false,
    val zoom: Float = 1f,
)

/** Everything that needs a new camera session when it changes. */
data class Setup(
    val lens: Lens,
    val mode: Mode,
    val format: PhotoFormat,
    val raw: RawProfile?,
    val maxRes: Boolean,
    val videoSize: Size?,
    val hevc: Boolean,
    val jpegQuality: Int,
    /** Ultra HDR (JPEG_R) instead of plain JPEG, where the lens offers it and the outputs can be combined. */
    val ultraHdr: Boolean = false,
    /** Video: 10-bit HLG (HEVC only), where the lens offers it. */
    val hdrVideo: Boolean = false,
    /** Video frame rate; only used when the lens can hold it (see [Lens.videoFps]). */
    val fps: Int = 30,
    /** Video: optical and electronic stabilisation, where offered. */
    val stabilization: Boolean = true,
)

data class CamState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val recording: Boolean = false,
    val recordStart: Long = 0L,
    val error: Int? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,
    /** What the open session actually saves (for the status line). */
    val saving: String = "",
    /** Night mode: frames of the burst received so far, out of [nightTotal] (0 when no burst is running). */
    val nightFrames: Int = 0,
    val nightTotal: Int = 0,
    /** Night mode: how many frames went into the last merged photo. */
    val lastNightMerged: Int = 0,
    /** Scan mode: the code that was found (scanning pauses until [CameraController.resumeScan]). */
    val scanned: Scanned? = null,
    /** Bracketing: shots of the series taken so far, out of [bracketTotal] (0 when no series is running). */
    val bracketDone: Int = 0,
    val bracketTotal: Int = 0,
    /** What the open video session actually records with (frame rate, HDR, stabilisation), for the status line. */
    val videoExtras: String = "",
)

/** One interval shot: where it was saved (null when it wasn't), and the JPEG itself. */
class Shot(val uri: Uri?, val jpeg: ByteArray)

/**
 * Camera2 on its own thread. Photos: JPEG, RAW (DNG) or both, in the lens's normal mode or, where Android
 * offers it, its maximum-resolution mode. Night: a burst of YUV frames merged into one JPEG. Scan: QR codes
 * and barcodes from a small YUV stream. Video: MediaRecorder on a persistent surface.
 *
 * Without a preview texture (an interval run in the foreground service) a small YUV stream stands in for
 * the preview, so auto exposure and focus keep running between shots.
 */
class CameraController(private val context: Context) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CameraCtrl").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(CamState())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    /** Stands in for the preview when there is no screen (interval runs). */
    private var meterReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    /** Night: full-size frames for the merge. Scan: small frames for the code reader. */
    private var yuvReader: ImageReader? = null
    private var maxResActive = false
    private var setup: Setup? = null
    private var manual = Manual()
    private var regions: Array<MeteringRectangle>? = null
    private var pending: Pending? = null
    private var openToken = 0

    /** What auto exposure chose for the last preview frame (night mode and bracketing start from it). */
    @Volatile private var lastAeNs = 0L
    @Volatile private var lastAeIso = 0

    /** The last preview frame's focus distance (diopters) and autofocus state, for close-up lens switching. */
    @Volatile var focusDiopters = -1f
        private set
    @Volatile var afState = -1
        private set
    /** Set (on the camera thread) while a flash photo waits for the precapture metering to finish. */
    private var precapture: Precapture? = null
    /** Photo mode flash; read when a photo is taken. */
    @Volatile var flashMode = FlashMode.OFF
    /** Let the camera use zero-shutter-lag for photos (where the lens offers it). */
    @Volatile var zslAllowed = true
    /** Whether the open session really saves Ultra HDR / records HLG (it falls back when the outputs can't be combined). */
    private var ultraHdrActive = false
    private var hdrVideoActive = false
    @Volatile private var bracketRunning = false

    private var night: NightJob? = null

    private val scanExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "CameraCtrl-scan") }
    private val decoder = Decoder()
    @Volatile private var scanBusy = false
    @Volatile private var scanPaused = false
    /** The frame being read for a code. Only touched on the camera thread, and only while [scanBusy] is false. */
    private var scanLuma = ByteArray(0)

    private var persistent: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null

    /** Device rotation in degrees clockwise (0, 90, 180, 270), from the screen's orientation listener. */
    @Volatile var orientation = 0
    @Volatile var shutterSound = true
    private val sound = MediaActionSound()

    init {
        // Loads the shutter and recording sounds ahead of time (on the camera thread), so the first one isn't late.
        handler.post {
            runCatching {
                sound.load(MediaActionSound.SHUTTER_CLICK)
                sound.load(MediaActionSound.START_VIDEO_RECORDING)
                sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        }
    }

    private class Precapture(val run: () -> Unit) {
        /** The trigger request has been processed; only later frames say anything about the metering. */
        var triggered = false
        var started = false
        var framesAfter = 0
        var done = false
    }

    private class Pending(
        val wantJpeg: Boolean,
        val wantRaw: Boolean,
        val saveJpeg: Boolean,
        val onShot: ((Shot?) -> Unit)?,
        val name: String? = null,
        val onDone: ((Boolean) -> Unit)? = null,
    ) {
        val result = CompletableDeferred<TotalCaptureResult>()
        val jpeg = CompletableDeferred<Image>()
        val raw = CompletableDeferred<Image>()
    }

    // ---------- Opening ----------

    /** Opens [s]. With no [texture] the camera runs without a preview (interval runs). */
    @SuppressLint("MissingPermission")
    fun open(s: Setup, texture: SurfaceTexture?) {
        val token = ++openToken
        handler.post {
            if (token != openToken) return@post
            closeInternal()
            setup = s
            if (texture != null) {
                val preview = choosePreview(s)
                texture.setDefaultBufferSize(preview.width, preview.height)
                previewSurface = Surface(texture)
            } else {
                val size = chooseYuv(s.lens, 640, 640L * 480)
                meterReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage() }.getOrNull()?.close() }, handler)
                }
                previewSurface = meterReader!!.surface
            }
            state.update { it.copy(ready = false, error = null) }
            try {
                cm.openCamera(s.lens.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (token != openToken) { d.close(); return }
                        device = d
                        createSession(s)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        if (device == d) device = null
                        state.update { it.copy(ready = false) }
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        if (device == d) device = null
                        state.update { it.copy(ready = false, error = when (error) { ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> R.string.err_in_use; ERROR_CAMERA_DISABLED -> R.string.err_disabled; else -> R.string.err_open }) }
                    }
                })
            } catch (e: android.hardware.camera2.CameraAccessException) {
                state.update { it.copy(error = if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISABLED) R.string.err_disabled else R.string.err_open) }
            } catch (_: Exception) {
                state.update { it.copy(error = R.string.err_open) }
            }
        }
    }

    /** A preview size with the shape of what is saved, no larger than about 1080p. */
    private fun choosePreview(s: Setup): Size {
        val target = if (s.mode == Mode.VIDEO) s.videoSize ?: Size(1920, 1080) else s.lens.pixelArray ?: Size(4, 3)
        val ratio = target.width.toFloat() / target.height
        val sizes = s.lens.previewSizes.filter { it.width <= 1920 && it.height <= 1440 }
        return sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    /** The largest YUV size with the sensor's shape, at most [maxWidth] wide and [maxPixels] in all. */
    private fun chooseYuv(lens: Lens, maxWidth: Int, maxPixels: Long): Size {
        val ratio = lens.pixelArray?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val fits = lens.yuvSizes.filter { it.width <= maxWidth && it.width.toLong() * it.height <= maxPixels && it.width % 2 == 0 && it.height % 2 == 0 }
        return fits.filter { abs(it.width.toFloat() / it.height - ratio) < 0.02f }.maxByOrNull { it.width.toLong() * it.height }
            ?: fits.maxByOrNull { it.width.toLong() * it.height }
            ?: lens.yuvSizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    private fun createSession(s: Setup) {
        val d = device ?: return
        val preview = previewSurface ?: return
        var built = buildOutputs(s, preview, ultra = s.ultraHdr, hlg = s.hdrVideo)
        // Ultra HDR and 10-bit HDR aren't possible with every combination of outputs. When Android says this one
        // isn't, the session is built without them (plain JPEG, 8-bit video) instead of failing.
        if ((ultraHdrActive || hdrVideoActive) && supported(d, built.first) == false) {
            closeReaders()
            built = buildOutputs(s, preview, ultra = false, hlg = false)
        }
        val (outs, saving) = built
        state.update { it.copy(saving = saving, videoExtras = videoExtras(s)) }
        try {
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) {
                    if (device != d) { sess.close(); return }
                    session = sess
                    startRepeating()
                    state.update { it.copy(ready = true) }
                }

                override fun onConfigureFailed(sess: CameraCaptureSession) {
                    state.update { it.copy(ready = false, error = R.string.err_config) }
                }
            }))
        } catch (_: Exception) {
            state.update { it.copy(ready = false, error = R.string.err_config) }
        }
    }

    /** Android's answer to "can these outputs be combined?" (null when the phone can't say). */
    private fun supported(d: CameraDevice, outs: List<OutputConfiguration>): Boolean? = try {
        d.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }))
    } catch (_: Exception) {
        null
    }

    private fun closeReaders() {
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { yuvReader?.close() }
        yuvReader = null
    }

    /** The outputs for [s], and the status line saying what is saved. Sets the readers and the *Active flags. */
    private fun buildOutputs(s: Setup, preview: Surface, ultra: Boolean, hlg: Boolean): Pair<List<OutputConfiguration>, String> {
        val phys = s.lens.physicalId
        val useHlg = hlg && s.mode == Mode.VIDEO && s.hevc && s.lens.hlg10
        fun cfg(surface: Surface, maxRes: Boolean = false, tenBit: Boolean = false) = OutputConfiguration(surface).apply {
            if (phys != null) setPhysicalCameraId(phys)
            if (maxRes) addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            // The preview and the recording carry the same 10-bit profile (Android's HDR video guide).
            if (tenBit) setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
        val outs = arrayListOf(cfg(preview, tenBit = useHlg))
        maxResActive = false
        ultraHdrActive = false
        hdrVideoActive = false
        var saving = ""
        when (s.mode) {
            Mode.PHOTO, Mode.INTERVAL -> {
                val wantRaw = s.format != PhotoFormat.JPEG && s.raw != null
                val wantJpeg = s.format != PhotoFormat.RAW || s.raw == null
                if (s.maxRes && s.lens.supportsMaxRes) {
                    // Maximum resolution: one full-size file per shot (Android doesn't guarantee RAW and JPEG together here).
                    val rawSize = s.raw?.let { s.lens.maxResRaws[it] }
                    if (wantRaw && rawSize != null) {
                        rawReader = reader(rawSize, s.raw!!.format, raw = true).also { outs += cfg(it.surface, maxRes = true) }
                        saving = "${s.raw!!.name} · ${mp(rawSize)}"
                    } else if (s.lens.maxResJpeg != null) {
                        jpegReader = reader(s.lens.maxResJpeg, ImageFormat.JPEG, raw = false).also { outs += cfg(it.surface, maxRes = true) }
                        saving = "JPEG · ${mp(s.lens.maxResJpeg)}"
                    }
                    maxResActive = rawReader != null || jpegReader != null
                }
                if (!maxResActive) {
                    val rawSize = s.raw?.let { s.lens.raws[it] }
                    if (wantRaw && rawSize != null) {
                        rawReader = reader(rawSize, s.raw!!.format, raw = true).also { outs += cfg(it.surface) }
                        saving = "${s.raw!!.name} · ${mp(rawSize)}"
                    }
                    if (wantJpeg || rawReader == null) {
                        // Ultra HDR: a JPEG with a gain map (JPEG_R), in the lens's normal mode only.
                        val uhdr = s.lens.ultraHdr
                        if (ultra && s.mode == Mode.PHOTO && uhdr != null) {
                            jpegReader = reader(uhdr, ImageFormat.JPEG_R, raw = false).also { outs += cfg(it.surface) }
                            ultraHdrActive = true
                            saving = if (saving.isEmpty()) "ULTRA HDR · ${mp(uhdr)}" else "$saving + ULTRA HDR"
                        } else if (s.lens.jpeg != null) {
                            jpegReader = reader(s.lens.jpeg, ImageFormat.JPEG, raw = false).also { outs += cfg(it.surface) }
                            saving = if (saving.isEmpty()) "JPEG · ${mp(s.lens.jpeg)}" else "$saving + JPEG"
                        }
                    }
                }
            }
            Mode.NIGHT -> {
                // Full frames for the merge, at most about 13 MP so a few of them fit in memory.
                val size = chooseYuv(s.lens, Int.MAX_VALUE, 13_000_000L)
                yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, NIGHT_IMAGES).apply {
                    setOnImageAvailableListener({ drainNight() }, handler)
                }.also { outs += cfg(it.surface) }
                saving = "JPEG · ${mp(size)}"
            }
            Mode.SCAN -> {
                // Preview-sized (at most 1440 × 1080), so preview plus this stream is a combination every camera supports.
                val size = chooseYuv(s.lens, 1440, 1440L * 1080)
                yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ r -> onScanFrame(r) }, handler)
                }.also { outs += cfg(it.surface) }
            }
            Mode.VIDEO -> {
                val size = s.videoSize ?: Size(1920, 1080)
                val surface = persistent ?: MediaCodec.createPersistentInputSurface().also { persistent = it }
                // The persistent surface takes its buffer size from a prepared recorder before the session uses it.
                val tmp = File(context.cacheDir, "prepare.mp4")
                try {
                    runCatching { newRecorder(size, s.hevc, null, tmp, 0, audio = false, fps = videoFps(s), hdr = useHlg).apply { prepare(); release() } }
                } finally {
                    tmp.delete()
                }
                outs += cfg(surface, tenBit = useHlg)
                hdrVideoActive = useHlg
                saving = "${if (s.hevc) "HEVC" else "H.264"} · ${size.width}×${size.height}"
            }
        }
        return outs to saving
    }

    /** The frame rate the video session uses: the chosen one where the lens can hold it at this size, else 30. */
    private fun videoFps(s: Setup): Int {
        val offered = s.lens.videoFps(s.videoSize)
        return if (s.fps in offered) s.fps else 30
    }

    /** Electronic stabilisation, except with 10-bit HDR at 4K and above (that combination crashes some phones' camera). */
    private fun eisAllowed(s: Setup): Boolean {
        val uhd = (s.videoSize?.let { it.width.toLong() * it.height } ?: 0L) >= 3840L * 2160
        return s.stabilization && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in s.lens.videoStabModes && !(hdrVideoActive && uhd)
    }

    private fun oisAllowed(s: Setup) = s.stabilization && CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in s.lens.oisModes

    private fun videoExtras(s: Setup): String {
        if (s.mode != Mode.VIDEO) return ""
        return listOfNotNull(
            "${videoFps(s)} fps",
            if (hdrVideoActive) "HDR HLG" else null,
            if (eisAllowed(s) || oisAllowed(s)) "STAB" else null,
        ).joinToString(" · ")
    }

    private fun reader(size: Size, format: Int, raw: Boolean): ImageReader =
        ImageReader.newInstance(size.width, size.height, format, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val p = pending
                val taken = p != null && (if (raw) p.raw else p.jpeg).complete(img)
                if (!taken) img.close()
            }, handler)
        }

    // ---------- Requests ----------

    private val aeWatcher = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            // A physical lens reports its own focus distance in the physical result.
            val phys = setup?.lens?.physicalId
            val own = phys?.let { runCatching { result.physicalCameraTotalResults[it] }.getOrNull() } ?: result
            focusDiopters = own.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f
            afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
            precapture?.let { p ->
                // Flash photos: after the trigger, wait until the precapture metering has started and then ended.
                // A camera that never reports the metering state gets a few frames instead.
                if (!p.triggered) return@let
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    p.started = true
                } else if (p.started || ++p.framesAfter >= PRECAPTURE_FRAMES) {
                    finishPrecapture(p)
                }
            }
        }
    }

    private fun finishPrecapture(p: Precapture) {
        if (p.done || precapture !== p) return
        p.done = true
        precapture = null
        p.run()
    }

    private fun startRepeating() {
        val sess = session ?: return
        val d = device ?: return
        val s = setup ?: return
        val preview = previewSurface ?: return
        runCatching {
            val recording = recorder != null
            val b = d.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview)
            if (recording) persistent?.let(b::addTarget)
            if (s.mode == Mode.SCAN) yuvReader?.let { b.addTarget(it.surface) }
            apply(b, s)
            if (s.mode == Mode.VIDEO) applyVideo(b, s)
            sess.setRepeatingRequest(b.build(), aeWatcher, handler)
        }
    }

    /** Frame rate and stabilisation for the video preview and recording. */
    private fun applyVideo(b: CaptureRequest.Builder, s: Setup) {
        val fps = videoFps(s)
        if (fps in s.lens.videoFps(s.videoSize)) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(fps, fps))
        if (s.lens.videoStabModes.isNotEmpty()) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (eisAllowed(s)) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        if (s.lens.oisModes.size > 1) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (oisAllowed(s)) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
    }

    /** Auto exposure with the flash, for Photo mode when the flash is on or automatic (and the torch is off). */
    private fun flashAeMode(s: Setup, m: Manual): Int? {
        if (s.mode != Mode.PHOTO || m.torch || !m.auto || !s.lens.hasFlashModes || bracketRunning) return null
        return when (flashMode) {
            FlashMode.OFF -> null
            FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH.takeIf { it in s.lens.aeModes }
            FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        }
    }

    private fun apply(b: CaptureRequest.Builder, s: Setup) {
        val m = manual
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (m.auto || !s.lens.manualSensor || s.mode == Mode.SCAN) {
            b.set(CaptureRequest.CONTROL_AE_MODE, flashAeMode(s, m) ?: CameraMetadata.CONTROL_AE_MODE_ON)
            s.lens.evRange?.let { b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, m.ev.coerceIn(it.lower, it.upper)) }
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            s.lens.iso?.let { b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso.coerceIn(it.lower, it.upper)) }
            s.lens.exposureNs?.let { b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs.coerceIn(it.lower, it.upper)) }
        }
        if (!s.lens.hasAf) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        } else if (m.focus != null && s.mode != Mode.SCAN) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, m.focus.coerceIn(0f, s.lens.minFocus))
        } else if (regions != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, if (s.mode == Mode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (m.awb in s.lens.awbModes) b.set(CaptureRequest.CONTROL_AWB_MODE, m.awb)
        // With a flash auto-exposure mode the camera fires the flash itself; FLASH_MODE only matters for the torch.
        if (s.lens.flash) b.set(CaptureRequest.FLASH_MODE, if (m.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        s.lens.zoom?.let { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, m.zoom.coerceIn(it.lower, it.upper)) }
    }

    /** Changes the flash mode; the preview request carries it, so auto exposure meters for it. */
    fun setFlash(mode: FlashMode) {
        handler.post {
            if (flashMode == mode) return@post
            flashMode = mode
            startRepeating()
        }
    }

    fun setManual(m: Manual) {
        handler.post {
            manual = m
            if (m.focus != null) regions = null
            startRepeating()
        }
    }

    /** Tap to focus and meter at a point of the preview (0..1 in the preview's portrait coordinates). */
    fun focusAt(nx: Float, ny: Float) {
        handler.post {
            val s = setup ?: return@post
            val sess = session ?: return@post
            val d = device ?: return@post
            val area = s.lens.activeArray ?: return@post
            if (!s.lens.hasAf || manual.focus != null || pending != null || night != null) return@post
            var x = nx.coerceIn(0f, 1f)
            val y = ny.coerceIn(0f, 1f)
            if (s.lens.facing == CameraMetadata.LENS_FACING_FRONT) x = 1f - x
            val (sx, sy) = when (s.lens.sensorOrientation) {
                90 -> y to 1f - x
                180 -> 1f - x to 1f - y
                270 -> 1f - y to x
                else -> x to y
            }
            val half = (min(area.width(), area.height()) * 0.07f).toInt()
            val cx = (area.left + sx * area.width()).toInt().coerceIn(area.left + half, area.right - half)
            val cy = (area.top + sy * area.height()).toInt().coerceIn(area.top + half, area.bottom - half)
            regions = arrayOf(MeteringRectangle(Rect(cx - half, cy - half, cx + half, cy + half), MeteringRectangle.METERING_WEIGHT_MAX))
            runCatching {
                val preview = previewSurface ?: return@post
                sess.stopRepeating()
                val cancel = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview); apply(this, s); set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                sess.capture(cancel.build(), null, handler)
                val trigger = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview); apply(this, s); set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                sess.capture(trigger.build(), null, handler)
            }
            startRepeating()
        }
    }

    /** Back to continuous autofocus (after the scene changes, or on a long-press). */
    fun resetFocus() {
        handler.post { regions = null; startRepeating() }
    }

    // ---------- Photos ----------

    fun capture() = capture(saveJpeg = true, onShot = null)

    /**
     * Takes one JPEG for an interval run. [save] puts it in the library; the JPEG bytes always come back
     * (for the thumbnail, and as a frame of a timelapse video). Null when the shot failed.
     */
    suspend fun captureShot(save: Boolean): Shot? {
        val done = CompletableDeferred<Shot?>()
        capture(saveJpeg = save) { done.complete(it) }
        return withTimeoutOrNull(TIMEOUT + 5_000) { done.await() }
    }

    private fun capture(
        saveJpeg: Boolean,
        onShot: ((Shot?) -> Unit)?,
        exposure: Pair<Long, Int>? = null,
        name: String? = null,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        handler.post {
            val s = setup
            val sess = session
            val d = device
            if (s == null || sess == null || d == null || (s.mode != Mode.PHOTO && s.mode != Mode.INTERVAL) || pending != null) {
                onShot?.invoke(null)
                onDone?.invoke(false)
                return@post
            }
            val jpeg = jpegReader
            val raw = if (onShot != null) null else rawReader
            if (jpeg == null && raw == null || (onShot != null && jpeg == null)) {
                onShot?.invoke(null)
                onDone?.invoke(false)
                return@post
            }
            val p = Pending(wantJpeg = jpeg != null, wantRaw = raw != null, saveJpeg = saveJpeg, onShot = onShot, name = name, onDone = onDone)
            pending = p
            state.update { it.copy(busy = true) }
            val orient = jpegOrientation(s.lens)
            val m = manual
            val flashAe = if (exposure == null) flashAeMode(s, m) else null
            // Manual exposure with the flash on: one flash pulse in the photo itself (no metering possible).
            val singleFlash = exposure == null && !m.auto && s.lens.manualSensor && s.mode == Mode.PHOTO && flashMode == FlashMode.ON && s.lens.flash && !m.torch
            val shoot = shoot@{
                if (pending !== p || session !== sess) return@shoot
                try {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    jpeg?.let { b.addTarget(it.surface) }
                    raw?.let { b.addTarget(it.surface) }
                    apply(b, s)
                    if (exposure != null) {
                        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.first)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.second)
                    }
                    if (singleFlash) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                    if (s.lens.zsl) {
                        // Zero-shutter-lag lets the camera use a frame from just before the press. Not with the flash,
                        // set exposures (bracketing, manual) or maximum resolution, where that frame would be wrong.
                        val zsl = zslAllowed && flashAe == null && !singleFlash && exposure == null && m.auto && !maxResActive && s.mode == Mode.PHOTO
                        b.set(CaptureRequest.CONTROL_ENABLE_ZSL, zsl)
                    }
                    b.set(CaptureRequest.JPEG_ORIENTATION, orient)
                    b.set(CaptureRequest.JPEG_QUALITY, s.jpegQuality.toByte())
                    if (raw != null) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    if (maxResActive) b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                    if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                    sess.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            p.result.complete(result)
                        }

                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            p.result.completeExceptionally(IllegalStateException("capture failed"))
                        }
                    }, handler)
                } catch (e: Exception) {
                    p.result.completeExceptionally(e)
                }
            }
            if (flashAe != null && previewSurface != null) {
                // Flash: let auto exposure meter with the pre-flash first (the precapture sequence), then shoot.
                val pc = Precapture(shoot)
                precapture = pc
                try {
                    val t = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    t.addTarget(previewSurface!!)
                    apply(t, s)
                    t.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    sess.capture(t.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            pc.triggered = true
                            if (result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) pc.started = true
                        }

                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            finishPrecapture(pc)
                        }
                    }, handler)
                } catch (_: Exception) {
                    finishPrecapture(pc)
                }
                // Never wait for ever: some cameras don't report the precapture state.
                handler.postDelayed({ finishPrecapture(pc) }, PRECAPTURE_WAIT_MS)
            } else {
                shoot()
            }
            io.launch { save(p, s, orient) }
        }
    }

    /**
     * Exposure bracketing: [shots] photos at the exposures of [Bracket.plan], one after another, each saved like
     * a normal photo (with "_EV-1", "_EV0", "_EV+1" in the name). Needs a lens with manual exposure control.
     */
    fun captureBracket(bracket: Bracket) {
        handler.post {
            val s = setup ?: return@post
            if (s.mode != Mode.PHOTO || pending != null || bracketRunning || bracket == Bracket.OFF) return@post
            val lens = s.lens
            val iso = lens.iso
            val ns = lens.exposureNs
            val m = manual
            val baseNs = if (!m.auto) m.exposureNs else lastAeNs
            val baseIso = if (!m.auto) m.iso else lastAeIso
            if (!lens.manualSensor || iso == null || ns == null || baseNs <= 0 || baseIso <= 0) {
                state.update { it.copy(error = R.string.bracket_unavailable) }
                return@post
            }
            val plan = Bracket.plan(baseNs, baseIso, iso.lower, iso.upper, ns.lower, ns.upper, bracket.evs)
            val base = newName()
            bracketRunning = true
            state.update { it.copy(busy = true, bracketDone = 0, bracketTotal = plan.size) }
            io.launch {
                var failed = false
                try {
                    plan.forEachIndexed { i, step ->
                        val done = CompletableDeferred<Boolean>()
                        val ev = bracket.evs[i]
                        val label = if (ev > 0) "+$ev" else ev.toString()
                        capture(saveJpeg = true, onShot = null, exposure = step, name = "${base}_EV$label", onDone = { done.complete(it) })
                        val ok = withTimeoutOrNull(TIMEOUT + 5_000) { done.await() } ?: false
                        if (!ok) failed = true
                        state.update { it.copy(bracketDone = i + 1) }
                    }
                } finally {
                    bracketRunning = false
                    state.update { it.copy(busy = false, bracketDone = 0, bracketTotal = 0, error = if (failed) R.string.err_capture else it.error) }
                }
            }
        }
    }

    private suspend fun save(p: Pending, s: Setup, orient: Int) {
        var uri: Uri? = null
        var failed = false
        var rawUsed = false
        var jpegUsed = false
        var bytes: ByteArray? = null
        try {
            val result = withTimeout(TIMEOUT) { p.result.await() }
            val name = p.name ?: newName()
            if (p.wantRaw) {
                val img = withTimeout(TIMEOUT) { p.raw.await() }.also { rawUsed = true }
                img.use { uri = Saver.saveRaw(context, it, s.lens, result, orient, name) }
                if (uri == null) failed = true
            }
            if (p.wantJpeg) {
                val img = withTimeout(TIMEOUT) { p.jpeg.await() }.also { jpegUsed = true }
                val b = img.use { Saver.jpegBytes(it) }
                bytes = b
                if (p.saveJpeg) Saver.saveJpegBytes(context, b, name)?.let { uri = it } ?: run { failed = true }
            }
        } catch (_: Exception) {
            failed = true
        } finally {
            // An image that arrived after a failure or timeout is closed, so the reader never runs out.
            if (!rawUsed && !p.raw.completeExceptionally(IllegalStateException())) runCatching { p.raw.await().close() }
            if (!jpegUsed && !p.jpeg.completeExceptionally(IllegalStateException())) runCatching { p.jpeg.await().close() }
            handler.post { if (pending === p) pending = null }
            state.update {
                it.copy(
                    busy = bracketRunning,
                    lastUri = uri ?: it.lastUri,
                    lastIsVideo = if (uri != null) false else it.lastIsVideo,
                    // A bracketing series reports one error at its end.
                    error = if (failed && p.onDone == null) R.string.err_capture else it.error,
                )
            }
            p.onShot?.invoke(if (failed) null else bytes?.let { Shot(uri, it) })
            p.onDone?.invoke(!failed)
        }
    }

    private fun jpegOrientation(l: Lens): Int {
        val dev = if (l.facing == CameraMetadata.LENS_FACING_FRONT) -orientation else orientation
        return (l.sensorOrientation + dev + 360) % 360
    }

    // ---------- Night ----------

    /**
     * Night mode: a burst of [NightPreset.frames] frames at the same exposure, aligned and merged into one JPEG
     * with less noise. Exposure starts from what auto exposure chose (or the manual settings, when set).
     */
    fun captureNight(preset: NightPreset) {
        handler.post {
            val s = setup ?: return@post
            val sess = session ?: return@post
            val d = device ?: return@post
            val reader = yuvReader ?: return@post
            if (s.mode != Mode.NIGHT || night != null || pending != null) return@post
            val n = preset.frames
            val m = manual
            val lens = s.lens
            val isoRange = lens.iso
            val nsRange = lens.exposureNs
            // Manual settings win; otherwise auto exposure's choice is spread for the preset, where the lens allows.
            val planned: Pair<Long, Int>? =
                if (m.auto && lens.manualSensor && isoRange != null && nsRange != null && lastAeNs > 0 && lastAeIso > 0) {
                    NightExposure.plan(lastAeNs, lastAeIso, isoRange.lower, isoRange.upper, nsRange.lower, nsRange.upper, preset)
                } else null
            val frameNs = planned?.first ?: if (!m.auto && lens.manualSensor) m.exposureNs else lastAeNs
            var self: NightJob? = null
            val job = NightJob(
                context, preset, n, jpegOrientation(lens), s.jpegQuality, newName() + "_NIGHT",
                onImageClosed = { handler.post { drainNight() } },
                onProgress = { k -> state.update { it.copy(nightFrames = k) } },
                onDone = { uri, merged ->
                    handler.post { if (night === self) night = null }
                    state.update {
                        it.copy(
                            busy = false, nightFrames = 0, nightTotal = 0, lastNightMerged = if (uri != null) merged else it.lastNightMerged,
                            lastUri = uri ?: it.lastUri, lastIsVideo = if (uri != null) false else it.lastIsVideo,
                            error = if (uri == null) R.string.err_capture else it.error,
                        )
                    }
                },
            )
            self = job
            night = job
            state.update { it.copy(busy = true, nightFrames = 0, nightTotal = n) }
            try {
                val requests = (0 until n).map {
                    d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        apply(this, s)
                        if (planned != null) {
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, planned.first)
                            set(CaptureRequest.SENSOR_SENSITIVITY, planned.second)
                        } else if (m.auto || !lens.manualSensor) {
                            set(CaptureRequest.CONTROL_AE_LOCK, true)
                        }
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    }.build()
                }
                if (shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                sess.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        job.frameFailed()
                    }
                }, handler)
            } catch (_: Exception) {
                job.finishNow()
                return@post
            }
            // If frames go missing, whatever arrived is merged and saved after a generous wait.
            val waitMs = n * (frameNs.coerceAtLeast(0L) / 1_000_000L) + 20_000L
            handler.postDelayed({ if (night === job) job.finishNow() }, waitMs)
        }
    }

    /**
     * Hands every waiting burst frame to the night job. A frame can't be taken while [NIGHT_IMAGES] are still
     * being worked on, so this runs again each time the job lets one go.
     */
    private fun drainNight() {
        val r = yuvReader ?: return
        while (true) {
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return
            val job = night
            if (job != null) job.offer(img) else img.close()
        }
    }

    // ---------- Scan ----------

    private fun onScanFrame(r: ImageReader) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        if (scanBusy || scanPaused) { img.close(); return }
        val w = img.width
        val h = img.height
        // One buffer, reused: a new frame is only copied when the reader thread is done with the last one (scanBusy).
        if (scanLuma.size != w * h) scanLuma = ByteArray(w * h)
        val luma = scanLuma
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            for (row in 0 until h) {
                buf.position(row * plane.rowStride)
                buf.get(luma, row * w, w)
            }
        } catch (_: Exception) {
            return
        } finally {
            img.close()
        }
        scanBusy = true
        scanExecutor.execute {
            val found = runCatching { decoder.decode(luma, w, h) }.getOrNull()
            if (found != null && !scanPaused) {
                scanPaused = true
                state.update { it.copy(scanned = found) }
            }
            scanBusy = false
        }
    }

    /** Clears the shown code and looks for the next one. */
    fun resumeScan() {
        state.update { it.copy(scanned = null) }
        scanPaused = false
    }

    // ---------- Video ----------

    private fun newRecorder(size: Size, hevc: Boolean, fd: java.io.FileDescriptor?, file: File?, rotation: Int, audio: Boolean, fps: Int, hdr: Boolean) =
        MediaRecorder(context).apply {
            if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fd != null) setOutputFile(fd) else setOutputFile(file)
            val uhd = size.width.toLong() * size.height >= 3840L * 2160
            // The bit rate grows with the frame rate (the 30 fps rates are the base).
            val base = if (uhd) 48_000_000L else 18_000_000L
            setVideoEncodingBitRate((base * fps / 30).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            setVideoFrameRate(fps)
            setVideoSize(size.width, size.height)
            setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            // 10-bit HDR: HEVC Main10, matching the camera's HLG10 output (as in Android's HDR video sample).
            if (hdr && hevc) setVideoEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            if (audio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(256_000)
                setAudioChannels(2)
            }
            setOrientationHint(rotation)
            persistent?.let { setInputSurface(it) }
        }

    fun toggleRecording() {
        handler.post { if (recorder != null) stopRecordingInternal() else startRecordingInternal() }
    }

    private fun startRecordingInternal() {
        val s = setup ?: return
        if (s.mode != Mode.VIDEO || session == null || persistent == null) return
        val size = s.videoSize ?: return
        val name = "CTRL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uri = Saver.insert(context, "$name.mp4", "video/mp4", video = true)
        if (uri == null) { state.update { it.copy(error = R.string.err_capture) }; return }
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException()
            val audio = context.hasPermission(Manifest.permission.RECORD_AUDIO)
            val r = newRecorder(size, s.hevc, pfd.fileDescriptor, null, jpegOrientation(s.lens), audio, videoFps(s), hdrVideoActive)
            r.prepare()
            recorder = r
            recordUri = uri
            recordPfd = pfd
            if (shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            startRepeating()
            r.start()
            state.update { it.copy(recording = true, recordStart = SystemClock.elapsedRealtime()) }
        } catch (_: Exception) {
            cleanupRecorder(keep = false)
            startRepeating()
            state.update { it.copy(recording = false, error = R.string.err_capture) }
        }
    }

    private fun stopRecordingInternal() {
        val r = recorder ?: return
        val ok = runCatching { r.stop() }.isSuccess
        if (shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        val uri = recordUri
        cleanupRecorder(keep = ok)
        startRepeating()
        state.update { it.copy(recording = false, lastUri = if (ok) uri else it.lastUri, lastIsVideo = ok || it.lastIsVideo) }
    }

    private fun cleanupRecorder(keep: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recordPfd?.close() }
        recordPfd = null
        recordUri?.let { if (keep) Saver.finish(context, it) else runCatching { context.contentResolver.delete(it, null, null) } }
        recordUri = null
    }

    // ---------- Closing ----------

    private fun closeInternal() {
        if (recorder != null) stopRecordingInternal()
        pending?.result?.completeExceptionally(IllegalStateException("closed"))
        pending = null
        precapture = null
        focusDiopters = -1f
        afState = -1
        // A night burst cut short still saves the frames that arrived.
        night?.finishNow()
        night = null
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { yuvReader?.close() }
        yuvReader = null
        if (meterReader != null) {
            runCatching { meterReader?.close() }
            meterReader = null
        } else {
            runCatching { previewSurface?.release() }
        }
        previewSurface = null
        regions = null
        lastAeNs = 0L
        lastAeIso = 0
        state.update { it.copy(ready = false, busy = false, recording = false, scanned = null) }
        scanPaused = false
    }

    /** Closes the camera (leaving the screen, or the app going to the background). */
    fun close() {
        openToken++
        handler.post { closeInternal() }
    }

    fun release() {
        close()
        handler.post {
            runCatching { persistent?.release() }
            persistent = null
            sound.release()
            scanExecutor.shutdown()
            thread.quitSafely()
        }
    }

    private fun newName() = "CTRL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    companion object {
        private const val TIMEOUT = 30_000L
        private const val NIGHT_IMAGES = 3
        private const val PRECAPTURE_WAIT_MS = 3_000L
        private const val PRECAPTURE_FRAMES = 8

        fun mp(s: Size): String {
            val mp = s.width.toLong() * s.height / 1_000_000.0
            return if (mp >= 100) "%.0f MP".format(Locale.US, mp) else "%.1f MP".format(Locale.US, mp)
        }
    }
}
