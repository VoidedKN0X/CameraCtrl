package com.vknxworks.cameractrl.interval

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.CameraController
import com.vknxworks.cameractrl.camera.Manual
import com.vknxworks.cameractrl.camera.Saver
import com.vknxworks.cameractrl.camera.Setup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** What an interval run shoots with and how. */
data class IntervalConfig(
    val setup: Setup,
    val manual: Manual,
    /** Device rotation when the run started (the photos keep it). */
    val orientation: Int,
    val plan: IntervalPlan,
    /** True: one timelapse video. False: every shot saved as a JPEG photo. */
    val video: Boolean,
    val uhd: Boolean,
    val hevc: Boolean,
    val shutterSound: Boolean,
)

data class IntervalState(
    /** A run is going (or its video is being finished). */
    val active: Boolean = false,
    val config: IntervalConfig? = null,
    val startedAt: Long = 0L,
    val shots: Int = 0,
    val nextAt: Long = 0L,
    val thumb: Bitmap? = null,
    /** The video is being finished after the last shot. */
    val finishing: Boolean = false,
    /** A string resource explaining why the run stopped early. */
    val error: Int? = null,
    /** What the last run left: the video, or its last photo. */
    val resultUri: Uri? = null,
    val resultIsVideo: Boolean = false,
    val resultShots: Int = 0,
)

/**
 * Runs interval shooting and timelapses in the foreground service ([IntervalService]), with its own camera
 * session without a preview, so a run goes on when the screen is dimmed, turned off or the app is locked.
 * One run at a time, for the whole process.
 */
class IntervalRunner(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(IntervalState())
    val state: StateFlow<IntervalState> = _state
    private var job: Job? = null
    private var pendingConfig: IntervalConfig? = null
    private val encoderThread = Executors.newSingleThreadExecutor { r -> Thread(r, "CameraCtrl-timelapse") }.asCoroutineDispatcher()

    /** Starts a run: the service is started, and it calls [begin]. */
    fun start(config: IntervalConfig) {
        if (_state.value.active) return
        pendingConfig = config
        _state.value = IntervalState(active = true, config = config, startedAt = SystemClock.elapsedRealtime(), nextAt = SystemClock.elapsedRealtime())
        val ok = runCatching { context.startForegroundService(Intent(context, IntervalService::class.java)) }.isSuccess
        if (!ok) failToStart()
    }

    /** The service couldn't start in the foreground (Android refused it). */
    fun failToStart() {
        pendingConfig = null
        _state.value = IntervalState(error = R.string.interval_err_start)
    }

    /** Called by the service once it is in the foreground. */
    fun begin(onEnd: () -> Unit) {
        val cfg = pendingConfig ?: run { onEnd(); return }
        pendingConfig = null
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                run(cfg)
            } finally {
                onEnd()
            }
        }
    }

    /** Stops the run after the shot in progress; a timelapse video is finished and saved. */
    fun stop() {
        job?.cancel()
    }

    /** Clears the last run's result message. */
    fun clearResult() {
        if (!_state.value.active) _state.value = IntervalState()
    }

    private suspend fun run(cfg: IntervalConfig) {
        val cam = CameraController(context)
        cam.shutterSound = cfg.shutterSound
        cam.orientation = cfg.orientation
        val name = "CTRL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_TIMELAPSE"
        val tmp = File(context.cacheDir, "$name.mp4")
        val encoder = if (cfg.video) TimelapseEncoder(tmp, cfg.uhd, cfg.hevc) else null
        var shots = 0
        var lastUri: Uri? = null
        var error: Int? = null
        try {
            cam.setManual(cfg.manual)
            // The viewfinder has just let go of the camera; if it's still closing, try again shortly.
            var ready: com.vknxworks.cameractrl.camera.CamState? = null
            for (attempt in 0 until 3) {
                if (attempt > 0) delay(1_000)
                cam.state.update { it.copy(ready = false, error = null) }
                cam.open(cfg.setup, null)
                ready = withTimeoutOrNull(15_000) { cam.state.first { it.ready || it.error != null } }
                if (ready?.ready == true) break
            }
            if (ready == null || !ready.ready) {
                error = ready?.error ?: R.string.err_open
                return
            }
            // Give auto exposure and focus a moment to settle on the scene.
            delay(800)
            val plan = cfg.plan
            val start = SystemClock.elapsedRealtime()
            _state.update { it.copy(startedAt = start, nextAt = start) }
            var slot = 0L
            var failures = 0
            while (!plan.finished(slot, shots)) {
                val due = plan.slotTime(start, slot)
                _state.update { it.copy(nextAt = due) }
                while (true) {
                    val wait = due - SystemClock.elapsedRealtime()
                    if (wait <= 0) break
                    delay(minOf(wait, 250L))
                }
                val shot = cam.captureShot(save = !cfg.video)
                if (shot == null) {
                    // The camera was taken by another app, or storage is full: stop after a few failures in a row.
                    if (++failures >= 3) {
                        error = cam.state.value.error ?: R.string.err_capture
                        break
                    }
                } else {
                    failures = 0
                    if (encoder != null) {
                        val added = withContext(encoderThread) { encoder.add(shot.jpeg) }
                        if (added) shots++
                    } else {
                        shots++
                        lastUri = shot.uri
                    }
                    val thumb = withContext(encoderThread) { thumbnail(shot.jpeg) }
                    _state.update { it.copy(shots = shots, thumb = thumb ?: it.thumb) }
                }
                slot = plan.nextSlot(start, slot, SystemClock.elapsedRealtime())
            }
        } finally {
            withContext(NonCancellable) {
                cam.release()
                var resultUri = lastUri
                var isVideo = false
                if (encoder != null) {
                    _state.update { it.copy(finishing = true) }
                    val made = withContext(encoderThread) { encoder.finish() }
                    if (made) {
                        resultUri = withContext(encoderThread) { Saver.saveVideoFile(context, tmp, "$name.mp4") }
                        isVideo = resultUri != null
                        if (resultUri == null && error == null) error = R.string.err_capture
                    }
                    tmp.delete()
                }
                _state.value = IntervalState(error = error, resultUri = resultUri, resultIsVideo = isVideo, resultShots = shots)
            }
        }
    }

    /** A small picture of the last shot for the run screen. */
    private fun thumbnail(jpeg: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (android.media.ExifInterface(java.io.ByteArrayInputStream(jpeg)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation) }, true).also { if (it !== bmp) bmp.recycle() }
    }.getOrNull()
}
