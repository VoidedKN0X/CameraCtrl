package com.vknxworks.cameractrl.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import kotlin.math.hypot

/**
 * The RAW outputs Android defines. Every lens is checked for each of them at runtime; nothing is assumed
 * per phone model. RAW_PRIVATE is listed when a lens offers it, but its layout is private to the phone
 * maker, so it can't be turned into a DNG and isn't offered for saving.
 */
enum class RawProfile(val format: Int, val saveable: Boolean) {
    RAW16(ImageFormat.RAW_SENSOR, true),
    RAW12(ImageFormat.RAW12, true),
    RAW10(ImageFormat.RAW10, true),
    RAW_PRIVATE(ImageFormat.RAW_PRIVATE, false),
}

/** One camera you can shoot with: a camera ID, or a physical lens of a multi-camera opened through it. */
data class Lens(
    /** The camera ID that is opened. */
    val id: String,
    /** Set when this lens is a physical camera reached through the logical camera [id]. */
    val physicalId: String?,
    /** The physical lenses of a logical multi-camera (empty for single cameras). */
    val physicalIds: List<String>,
    val facing: Int,
    val focalMm: Float,
    /** 35 mm equivalent focal length. */
    val equivMm: Int,
    val sensorMm: SizeF?,
    val pixelArray: Size?,
    val cfa: Int?,
    val sensorOrientation: Int,
    /** Largest size of each RAW profile in the normal (binned) mode. */
    val raws: Map<RawProfile, Size>,
    /** Largest size of each RAW profile in the maximum-resolution mode (Android's ultra high resolution sensor mode). */
    val maxResRaws: Map<RawProfile, Size>,
    val jpeg: Size?,
    val maxResJpeg: Size?,
    val previewSizes: List<Size>,
    val videoSizes: List<Size>,
    /** YUV output sizes in the normal mode (QR scanning, night mode, the interval run's metering stream). */
    val yuvSizes: List<Size>,
    val tenBit: Boolean,
    val manualSensor: Boolean,
    val iso: Range<Int>?,
    val exposureNs: Range<Long>?,
    /** Closest focus in diopters; 0 means fixed focus. */
    val minFocus: Float,
    val evRange: Range<Int>?,
    val evStep: Rational?,
    val awbModes: List<Int>,
    val zoom: Range<Float>?,
    val flash: Boolean,
    /** Auto-exposure modes of the camera that is opened (the flash modes are among them when it has a flash). */
    val aeModes: List<Int>,
    /** Electronic video stabilisation modes (CONTROL_VIDEO_STABILIZATION_MODE) of the camera that is opened. */
    val videoStabModes: List<Int>,
    /** Optical stabilisation modes of this lens. */
    val oisModes: List<Int>,
    /** Frame-rate ranges auto exposure can hold, of the camera that is opened. */
    val fpsRanges: List<Range<Int>>,
    /** Shortest frame time (ns) of each video size, so a frame rate is only offered where the size can keep up. */
    val videoMinFrameNs: Map<Size, Long>,
    /** 10-bit HLG video (DynamicRangeProfiles.HLG10) is offered. */
    val hlg10: Boolean,
    /** Largest Ultra HDR (JPEG_R: JPEG with a gain map) size, where the lens offers it. */
    val ultraHdr: Size?,
    /** The camera may use zero-shutter-lag for still photos (CONTROL_ENABLE_ZSL is an accepted request key). */
    val zsl: Boolean,
    /** Focus distances are in real diopters (approximate or calibrated), so they can be compared between lenses. */
    val focusCalibrated: Boolean,
    val hardwareLevel: Int,
    /** Active array of the camera that is opened (for tap-to-focus regions). */
    val activeArray: Rect?,
    /** Characteristics of the lens itself (for writing DNG files). */
    val chars: CameraCharacteristics,
) {
    val key: String get() = if (physicalId != null) "$id/$physicalId" else id
    val supportsMaxRes: Boolean get() = maxResRaws.isNotEmpty() || maxResJpeg != null
    val hasAf: Boolean get() = minFocus > 0f
    val logical: Boolean get() = physicalIds.isNotEmpty()
    val saveableRaws: List<RawProfile> get() = raws.keys.filter { it.saveable }
    val hasFlashModes: Boolean get() = flash && CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH in aeModes

    /** Video frame rates (of [VIDEO_FPS]) this lens can record at [size]: auto exposure can hold it and the size keeps up. */
    fun videoFps(size: Size?): List<Int> = VIDEO_FPS.filter { fps ->
        val held = fpsRanges.any { it.lower == fps && it.upper == fps }
        val minNs = size?.let { videoMinFrameNs[it] } ?: 0L
        held && (minNs == 0L || minNs <= 1_000_000_000L / fps + 50_000L)
    }
}

/** Frame rates offered for video, where the lens allows them. */
val VIDEO_FPS = listOf(24, 30, 60)

object LensDetector {
    fun detect(context: Context): List<Lens> {
        val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val out = ArrayList<Lens>()
        for (id in ids) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (!ch.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            out += build(id, null, ch, ch, active)
            // Physical lenses that aren't cameras of their own are reached through the logical camera.
            for (p in ch.physicalCameraIds) {
                if (p in ids) continue
                val pc = runCatching { cm.getCameraCharacteristics(p) }.getOrNull() ?: continue
                out += build(id, p, pc, ch, active)
            }
        }
        return out
    }

    private fun CameraCharacteristics.has(cap: Int) =
        get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(cap) == true

    private fun StreamConfigurationMap.largest(format: Int): Size? =
        if (format !in outputFormats) null
        else runCatching { getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height } }.getOrNull()

    private fun build(id: String, physical: String?, ch: CameraCharacteristics, opened: CameraCharacteristics, active: Rect?): Lens {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawCap = ch.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val raws = LinkedHashMap<RawProfile, Size>()
        if (map != null) for (p in RawProfile.entries) {
            // RAW_SENSOR needs the RAW capability; the packed formats are reported only through the stream map.
            if (p == RawProfile.RAW16 && !rawCap) continue
            map.largest(p.format)?.let { raws[p] = it }
        }
        val ultra = ch.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        val maxMap = if (ultra) ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) else null
        val maxRaws = LinkedHashMap<RawProfile, Size>()
        var maxJpeg: Size? = null
        if (maxMap != null) {
            for (p in RawProfile.entries) maxMap.largest(p.format)?.let { maxRaws[p] = it }
            maxJpeg = maxMap.largest(ImageFormat.JPEG)
        }
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (sensor != null && focal > 0f) (focal * 43.27f / hypot(sensor.width, sensor.height)).toInt() else 0
        val tenBit = ch.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
        val hlg = tenBit && runCatching {
            ch.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        }.getOrDefault(false)
        val videoSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        val videoFrames = HashMap<Size, Long>()
        if (map != null) for (v in videoSizes) {
            runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, v) }.getOrNull()?.let { videoFrames[v] = it }
        }
        val requestKeys = runCatching { opened.availableCaptureRequestKeys }.getOrNull().orEmpty()
        return Lens(
            id = id,
            physicalId = physical,
            physicalIds = if (physical == null) ch.physicalCameraIds.toList() else emptyList(),
            facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK,
            focalMm = focal,
            equivMm = equiv,
            sensorMm = sensor,
            pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            raws = raws,
            maxResRaws = maxRaws,
            jpeg = map?.largest(ImageFormat.JPEG),
            maxResJpeg = maxJpeg,
            previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty(),
            videoSizes = videoSizes,
            yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty(),
            tenBit = tenBit,
            manualSensor = ch.has(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = opened.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            evStep = opened.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP),
            awbModes = opened.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList().orEmpty(),
            // Zoom ratio only applies to the camera that is opened as a whole, not to one physical lens.
            zoom = if (physical == null) opened.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null,
            flash = opened.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            aeModes = opened.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty(),
            videoStabModes = opened.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty(),
            oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            fpsRanges = opened.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
            videoMinFrameNs = videoFrames,
            hlg10 = hlg,
            ultraHdr = map?.largest(ImageFormat.JPEG_R),
            zsl = CaptureRequest.CONTROL_ENABLE_ZSL in requestKeys,
            focusCalibrated = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION).let {
                it == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE || it == CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED
            },
            hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            activeArray = active,
            chars = ch,
        )
    }
}

/** The RAW profile to shoot with: the one chosen in Settings if this lens has it, otherwise the best one it has. */
fun Lens.resolveRaw(choice: RawProfile?, maxRes: Boolean): RawProfile? {
    val available = (if (maxRes && supportsMaxRes) maxResRaws.keys else raws.keys).filter { it.saveable }
    if (choice != null && choice in available) return choice
    return listOf(RawProfile.RAW16, RawProfile.RAW12, RawProfile.RAW10).firstOrNull { it in available }
}
