package com.vknxworks.cameractrl.ui.camera

import android.hardware.camera2.CameraMetadata
import android.util.Size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.CameraController
import com.vknxworks.cameractrl.camera.CloseUp
import com.vknxworks.cameractrl.camera.Lens
import com.vknxworks.cameractrl.camera.LensDetector
import com.vknxworks.cameractrl.camera.RawProfile
import com.vknxworks.cameractrl.camera.focusLens
import com.vknxworks.cameractrl.ui.components.Group
import com.vknxworks.cameractrl.ui.components.GroupRow
import com.vknxworks.cameractrl.ui.components.ScreenTopBar
import com.vknxworks.cameractrl.ui.theme.CtrlColors
import com.vknxworks.cameractrl.ui.theme.CtrlType
import com.vknxworks.cameractrl.ui.theme.Gutter

/**
 * Every camera mode Android reports on this phone, lens by lens: the RAW profiles, the maximum-resolution
 * mode, JPEG and video sizes and manual control. It's read from the phone at runtime; nothing here is
 * specific to one phone model, and only standard Android modes are listed.
 */
@Composable
fun ModesScreen() {
    val context = LocalContext.current
    val lenses = remember { LensDetector.detect(context) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.modes_title))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.modes_intro),
                style = CtrlType.Data,
                color = CtrlColors.Muted,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
            )
            lenses.forEach { l -> LensGroup(l, lenses.filter { it.facing == l.facing }) }
        }
    }
}

@Composable
private fun LensGroup(l: Lens, sameSide: List<Lens>) {
    val side = stringResource(
        when (l.facing) {
            CameraMetadata.LENS_FACING_FRONT -> R.string.lens_front
            CameraMetadata.LENS_FACING_EXTERNAL -> R.string.lens_external
            else -> R.string.lens_back
        },
    )
    val name = if (l.physicalId != null) stringResource(R.string.cam_physical, l.physicalId, l.id) else stringResource(R.string.cam_id, l.id)
    val focal = if (l.equivMm > 0) stringResource(R.string.focal, l.equivMm, "%.2f".format(java.util.Locale.US, l.focalMm)) else ""
    val footer = when {
        l.logical -> stringResource(R.string.kind_logical, l.physicalIds.joinToString(", "))
        l.physicalId != null -> stringResource(R.string.kind_physical)
        else -> null
    }
    val names = rawNames()
    Group("$side · $name", footer = footer) {
        GroupRow(stringResource(R.string.row_lens), focal.ifEmpty { "–" }, icon = Icons.Outlined.Camera)
        GroupRow(
            stringResource(R.string.row_sensor),
            listOfNotNull(
                l.pixelArray?.let { "${size(it)} (${CameraController.mp(it)})" },
                l.sensorMm?.let { "%.1f × %.1f mm".format(java.util.Locale.US, it.width, it.height) },
                l.cfa?.let { cfaName(it) },
            ).joinToString(" · ").ifEmpty { "–" },
            icon = Icons.Outlined.Memory,
        )
        GroupRow(
            stringResource(R.string.row_raw),
            if (l.raws.isEmpty()) stringResource(R.string.not_offered)
            else l.raws.entries.joinToString("\n") { (p, s) -> rawLine(names, p, s) },
            icon = Icons.Outlined.RawOn,
        )
        GroupRow(
            stringResource(R.string.row_maxres),
            if (!l.supportsMaxRes) stringResource(R.string.not_offered)
            else (l.maxResRaws.entries.map { (p, s) -> rawLine(names, p, s) } + listOfNotNull(l.maxResJpeg?.let { "JPEG · ${size(it)} (${CameraController.mp(it)})" })).joinToString("\n"),
            icon = Icons.Outlined.PhotoSizeSelectLarge,
        )
        GroupRow(stringResource(R.string.row_jpeg), l.jpeg?.let { "${size(it)} (${CameraController.mp(it)})" } ?: stringResource(R.string.not_offered), icon = Icons.Outlined.Image)
        GroupRow(
            stringResource(R.string.row_video),
            listOfNotNull(
                videoTiers(l.videoSizes).ifEmpty { null },
                l.videoFps(null).takeIf { it.isNotEmpty() }?.joinToString("/", postfix = " fps"),
                if (l.hlg10) stringResource(R.string.ten_bit_hlg) else if (l.tenBit) stringResource(R.string.ten_bit) else null,
                if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in l.videoStabModes || CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in l.oisModes) stringResource(R.string.stabilization) else null,
            ).joinToString(" · ").ifEmpty { stringResource(R.string.not_offered) },
            icon = Icons.Outlined.Videocam,
        )
        GroupRow(
            stringResource(R.string.row_manual),
            if (!l.manualSensor) stringResource(R.string.not_offered)
            else listOfNotNull(
                l.iso?.let { "ISO ${it.lower}–${it.upper}" },
                l.exposureNs?.let { "${shutterLabel(it.lower)} – ${shutterLabel(it.upper)}" },
                if (l.hasAf) stringResource(R.string.closest_focus, focusLabel(l.minFocus)) else stringResource(R.string.fixed_focus),
                if (l.hasAf && !l.focusCalibrated) stringResource(R.string.focus_uncalibrated) else null,
            ).joinToString(" · "),
            icon = Icons.Outlined.Tune,
        )
        GroupRow(
            stringResource(R.string.row_extras),
            listOfNotNull(
                if (l.hasFlashModes) stringResource(R.string.flash) else null,
                l.ultraHdr?.let { stringResource(R.string.extra_ultra_hdr, CameraController.mp(it)) },
                if (l.zsl) stringResource(R.string.zsl) else null,
            ).joinToString(" · ").ifEmpty { stringResource(R.string.not_offered) },
            icon = Icons.Outlined.AutoAwesome,
        )
        GroupRow(stringResource(R.string.row_closeup), closeUpStatus(l, sameSide), icon = Icons.Outlined.CenterFocusStrong)
        GroupRow(stringResource(R.string.row_level), levelName(l.hardwareLevel), icon = Icons.Outlined.CameraAlt)
    }
}

/**
 * What close-up switching can do with [l], of the lenses on its side ([side]): the same decision CloseUp makes,
 * from AUTO as it starts (before the phone says which of its lenses is active), so it can be checked on a screenshot.
 */
@Composable
private fun closeUpStatus(l: Lens, side: List<Lens>): String {
    val others = side.filter { !it.logical }
    if (l.logical) {
        val current = l.focusLens()
        val target = CloseUp.closerLens(current, others.filter { it.key !in CloseUp.unopenable }.map { it.focusLens() })
        val zoomMin = l.zoom?.lower?.takeIf { it < 0.95f }
        val zoom = zoomMin?.let { "%.1f".format(java.util.Locale.US, it) }
        return when {
            target != null && zoom != null -> stringResource(R.string.closeup_auto_lens_zoom, target.key, zoom)
            target != null -> stringResource(R.string.closeup_auto_lens, target.key)
            zoom != null -> stringResource(R.string.closeup_auto_zoom, zoom)
            else -> stringResource(R.string.closeup_auto_none)
        }
    }
    val lines = ArrayList<String>()
    lines += when {
        !l.hasAf -> stringResource(R.string.closeup_no_af)
        l.key in CloseUp.unopenable -> stringResource(R.string.closeup_unopenable)
        l.focusCalibrated -> stringResource(R.string.closeup_ok_calibrated)
        l.equivMm > 0 -> stringResource(R.string.closeup_ok_width)
        else -> stringResource(R.string.closeup_no_width)
    }
    if (l.hasAf) {
        val target = CloseUp.closerLens(l.focusLens(), others.filter { it.key !in CloseUp.unopenable }.map { it.focusLens() })
        lines += if (target != null) stringResource(R.string.closeup_from_lens, target.key) else stringResource(R.string.closeup_from_none)
    }
    return lines.joinToString("\n")
}

@Composable
private fun rawNames(): Map<RawProfile, String> = mapOf(
    RawProfile.RAW16 to stringResource(R.string.raw16),
    RawProfile.RAW12 to stringResource(R.string.raw12),
    RawProfile.RAW10 to stringResource(R.string.raw10),
    RawProfile.RAW_PRIVATE to stringResource(R.string.raw_private),
)

private fun rawLine(names: Map<RawProfile, String>, p: RawProfile, s: Size) = "${names[p]} · ${size(s)} (${CameraController.mp(s)})"

private fun size(s: Size) = "${s.width} × ${s.height}"

private fun videoTiers(sizes: List<Size>): String {
    val tiers = listOf(7680 to "8K", 3840 to "4K", 1920 to "1080p", 1280 to "720p")
    return tiers.filter { (w, _) -> sizes.any { it.width >= w } }.take(2).joinToString(", ") { it.second }
}

private fun cfaName(cfa: Int) = when (cfa) {
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "Mono"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
    else -> "CFA $cfa"
}

private fun levelName(level: Int) = when (level) {
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "–"
}
