package com.vknxworks.cameractrl.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.PhotoFormat
import com.vknxworks.cameractrl.camera.RawProfile
import com.vknxworks.cameractrl.container
import com.vknxworks.cameractrl.data.lock.LockVault
import com.vknxworks.cameractrl.data.settings.AppSettings
import com.vknxworks.cameractrl.data.settings.LockTimeout
import com.vknxworks.cameractrl.ui.components.Group
import com.vknxworks.cameractrl.ui.components.GroupChoice
import com.vknxworks.cameractrl.ui.components.GroupRow
import com.vknxworks.cameractrl.ui.components.GroupSwitch
import com.vknxworks.cameractrl.ui.components.ScreenTopBar
import com.vknxworks.cameractrl.ui.lock.AppLock
import com.vknxworks.cameractrl.ui.lock.PinRequest
import com.vknxworks.cameractrl.ui.lock.findActivity
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). CameraCtrl follows the phone's dark theme. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch { c.settings.update(transform) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(stringResource(R.string.group_photo), footer = stringResource(R.string.photo_footer)) {
                GroupChoice(
                    stringResource(R.string.photo_format),
                    listOf(
                        PhotoFormat.RAW_JPEG to stringResource(R.string.fmt_raw_jpeg_long),
                        PhotoFormat.RAW to stringResource(R.string.fmt_raw_long),
                        PhotoFormat.JPEG to stringResource(R.string.fmt_jpeg_long),
                    ),
                    settings.format,
                    icon = Icons.Outlined.Image,
                ) { v -> set { it.copy(format = v) } }
                GroupChoice(
                    stringResource(R.string.raw_profile),
                    listOf<Pair<RawProfile?, String>>(
                        null to stringResource(R.string.raw_auto),
                        RawProfile.RAW16 to stringResource(R.string.raw16),
                        RawProfile.RAW12 to stringResource(R.string.raw12),
                        RawProfile.RAW10 to stringResource(R.string.raw10),
                    ),
                    settings.rawProfile,
                    icon = Icons.Outlined.RawOn,
                ) { v -> set { it.copy(rawProfile = v) } }
                GroupSwitch(stringResource(R.string.max_res_setting), stringResource(R.string.max_res_sub), settings.maxRes, Icons.Outlined.PhotoSizeSelectLarge) { on -> set { it.copy(maxRes = on) } }
                GroupChoice(stringResource(R.string.jpeg_quality), listOf(90, 95, 100).map { it to "$it %" }, settings.jpegQuality, icon = Icons.Outlined.HighQuality) { v -> set { it.copy(jpegQuality = v) } }
                GroupChoice(stringResource(R.string.timer), listOf(0 to stringResource(R.string.timer_off), 3 to "3 s", 10 to "10 s"), settings.timer, icon = Icons.Outlined.Timer) { v -> set { it.copy(timer = v) } }
                GroupSwitch(stringResource(R.string.grid), stringResource(R.string.grid_sub), settings.grid, Icons.Outlined.Grid3x3) { on -> set { it.copy(grid = on) } }
                GroupSwitch(stringResource(R.string.shutter_sound), stringResource(R.string.shutter_sound_sub), settings.shutterSound, Icons.Outlined.VolumeUp) { on -> set { it.copy(shutterSound = on) } }
            }
            Group(stringResource(R.string.group_video)) {
                GroupChoice(stringResource(R.string.video_quality), listOf(true to "4K (2160p)", false to "1080p"), settings.videoUhd, icon = Icons.Outlined.Videocam) { v -> set { it.copy(videoUhd = v) } }
                GroupChoice(stringResource(R.string.video_codec), listOf(true to stringResource(R.string.codec_hevc), false to stringResource(R.string.codec_avc)), settings.hevc, icon = Icons.Outlined.Movie) { v -> set { it.copy(hevc = v) } }
            }
            PrivacyAndSecurityGroup(settings)
        }
    }
}
/** Privacy and security: the shared Ctrl series app lock, then hiding CameraCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}
