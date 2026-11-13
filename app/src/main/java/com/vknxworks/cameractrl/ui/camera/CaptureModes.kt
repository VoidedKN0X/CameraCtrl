package com.vknxworks.cameractrl.ui.camera

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.data.settings.AppSettings
import com.vknxworks.cameractrl.data.settings.INTERVAL_CHOICES
import com.vknxworks.cameractrl.interval.IntervalPlan
import com.vknxworks.cameractrl.interval.IntervalState
import com.vknxworks.cameractrl.interval.StopRule
import com.vknxworks.cameractrl.interval.TimelapseEncoder
import com.vknxworks.cameractrl.night.NightPreset
import com.vknxworks.cameractrl.scan.ScanContent
import com.vknxworks.cameractrl.scan.Scanned
import com.vknxworks.cameractrl.ui.common.formatDuration
import com.vknxworks.cameractrl.ui.common.launch
import com.vknxworks.cameractrl.ui.components.CtrlBottomSheet
import com.vknxworks.cameractrl.ui.components.CtrlButton
import com.vknxworks.cameractrl.ui.components.CtrlSecondaryButton
import com.vknxworks.cameractrl.ui.components.SectionLabel
import com.vknxworks.cameractrl.ui.lock.findActivity
import com.vknxworks.cameractrl.ui.theme.CtrlColors
import com.vknxworks.cameractrl.ui.theme.CtrlType
import kotlinx.coroutines.delay

// ---------- Night ----------

/** Tripod or handheld, and what night mode does, said plainly. */
@Composable
internal fun NightPanel(preset: NightPreset, enabled: Boolean, onPick: (NightPreset) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.night_handheld, NightPreset.HANDHELD.frames), preset == NightPreset.HANDHELD, enabled) { onPick(NightPreset.HANDHELD) }
            Pill(stringResource(R.string.night_tripod, NightPreset.TRIPOD.frames), preset == NightPreset.TRIPOD, enabled) { onPick(NightPreset.TRIPOD) }
        }
        Text(
            stringResource(if (preset == NightPreset.TRIPOD) R.string.night_note_tripod else R.string.night_note_handheld, preset.frames),
            style = CtrlType.Label,
            color = Dim,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// ---------- Interval ----------

private val STOP_CHOICES = listOf(
    StopRule.AfterShots(50), StopRule.AfterShots(100), StopRule.AfterShots(300), StopRule.AfterShots(600),
    StopRule.AfterTime(10 * 60_000L), StopRule.AfterTime(30 * 60_000L), StopRule.AfterTime(60 * 60_000L),
    StopRule.AfterTime(2 * 60 * 60_000L), StopRule.AfterTime(4 * 60 * 60_000L), StopRule.Never,
)

@Composable
private fun secondsLabel(s: Int): String =
    if (s >= 60 && s % 60 == 0) stringResource(R.string.unit_min, s / 60) else stringResource(R.string.unit_s, s)

@Composable
private fun stopLabel(r: StopRule): String = when (r) {
    is StopRule.AfterShots -> pluralStringResource(R.plurals.interval_after_shots, r.shots, r.shots)
    is StopRule.AfterTime -> if (r.millis >= 3_600_000L) stringResource(R.string.unit_h, (r.millis / 3_600_000L).toInt()) else stringResource(R.string.unit_min, (r.millis / 60_000L).toInt())
    StopRule.Never -> stringResource(R.string.interval_until_stopped)
}

/** Interval, when to stop, photos or a timelapse video, and what that makes. */
@Composable
internal fun IntervalPanel(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ChoiceRow(stringResource(R.string.interval_every)) {
            INTERVAL_CHOICES.forEach { s ->
                Pill(secondsLabel(s), settings.intervalSeconds == s) { onChange { it.copy(intervalSeconds = s) } }
            }
        }
        ChoiceRow(stringResource(R.string.interval_stop_after)) {
            STOP_CHOICES.forEach { r ->
                Pill(stopLabel(r), settings.intervalStop == r) { onChange { it.copy(intervalStop = r) } }
            }
        }
        ChoiceRow(stringResource(R.string.interval_save)) {
            Pill(stringResource(R.string.interval_out_video), settings.intervalVideo) { onChange { it.copy(intervalVideo = true) } }
            Pill(stringResource(R.string.interval_out_photos), !settings.intervalVideo) { onChange { it.copy(intervalVideo = false) } }
        }
        val plan = IntervalPlan(settings.intervalSeconds * 1000L, settings.intervalStop)
        val shots = plan.expectedShots
        val every = secondsLabel(settings.intervalSeconds)
        val summary = when {
            shots == null && settings.intervalVideo -> stringResource(R.string.interval_summary_open_video, every)
            shots == null -> stringResource(R.string.interval_summary_open_photos, every)
            settings.intervalVideo -> stringResource(
                R.string.interval_summary_video, shots, formatDuration(plan.expectedDurationMs ?: 0L),
                formatDuration(IntervalPlan.videoMillis(shots, TimelapseEncoder.FPS)),
            )
            else -> stringResource(R.string.interval_summary_photos, shots, formatDuration(plan.expectedDurationMs ?: 0L))
        }
        Text(summary, style = CtrlType.Status, color = Ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(stringResource(R.string.interval_note), style = CtrlType.Label, color = Dim, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun ChoiceRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CtrlType.Label, color = Dim, modifier = Modifier.padding(start = 16.dp).width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * While a run goes on: no preview (the camera belongs to the run), the last shot, progress, Stop, and a
 * dimmed screen on request. The screen stays on while this is showing.
 */
@Composable
internal fun IntervalRunScreen(run: IntervalState, onStop: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var dimmed by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val activity = context.findActivity()
    DisposableEffect(dimmed, activity) {
        val w = activity?.window
        if (w != null) {
            val lp = w.attributes
            lp.screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val cfg = run.config
    val expected = cfg?.plan?.expectedShots

    if (dimmed) {
        // Black, so the screen draws almost nothing; one quiet line that drifts a little to spare the panel.
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { dimmed = false }) {
            val drift = ((now / 60_000L) % 5).toInt()
            Text(
                stringResource(R.string.interval_dim_line, run.shots, stringResource(R.string.interval_dim_hint)),
                style = CtrlType.Label,
                color = Color(0x55FFFFFF),
                modifier = Modifier.align(Alignment.Center).padding(top = (drift * 40).dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        SectionLabel(stringResource(if (cfg?.video == true) R.string.interval_running_video else R.string.interval_running_photos), color = CtrlColors.Accent)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF111114)), contentAlignment = Alignment.Center) {
            val thumb = run.thumb
            if (thumb != null) Image(thumb.asImageBitmap(), stringResource(R.string.last_capture), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Text(
                stringResource(R.string.interval_preview_off),
                style = CtrlType.Label,
                color = Dim,
                modifier = Modifier.align(Alignment.BottomCenter).background(Scrim).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.interval_label_shots), if (expected != null) "${run.shots} / $expected" else run.shots.toString())
            if (run.finishing) {
                Stat(stringResource(R.string.interval_label_next), "–")
            } else {
                Stat(stringResource(R.string.interval_label_next), formatDuration((run.nextAt - now).coerceAtLeast(0L) + 999))
            }
            Stat(stringResource(R.string.interval_label_elapsed), formatDuration(now - run.startedAt))
        }
        if (cfg?.video == true) {
            Text(
                stringResource(R.string.interval_video_so_far, formatDuration(IntervalPlan.videoMillis(run.shots, TimelapseEncoder.FPS))),
                style = CtrlType.Status,
                color = Dim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (run.finishing) {
            Text(stringResource(R.string.interval_finishing), style = CtrlType.Status, color = Ink)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CtrlSecondaryButton(onClick = { dimmed = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.interval_dim))
                }
                CtrlButton(stringResource(R.string.interval_stop), onClick = onStop, icon = Icons.Outlined.Stop, modifier = Modifier.weight(1f))
            }
        }
    }
}


@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = CtrlType.Label, color = Dim)
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ink)
    }
}

// ---------- Scan ----------

/** A square guide in the middle of the viewfinder. */
@Composable
internal fun ScanFrame() {
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * 0.62f
        val tl = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        drawRoundRect(Color(0xCCFFFFFF), tl, Size(side, side), CornerRadius(28f, 28f), style = Stroke(width = 4f))
    }
}

/**
 * The code that was found: its content (selectable), Copy and Share. A link is shown in full and opens only
 * when Open is tapped. A Wi-Fi code offers Android's own "add network" screen (no permission needed) and
 * copying the password.
 */
@Composable
internal fun ScanResultSheet(found: Scanned, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val content = found.content
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun copy(text: String, sensitive: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(context.getString(R.string.app_name), text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.launch(Intent.createChooser(send, context.getString(R.string.scan_share_title)))
    }
    CtrlBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val title = when (content) {
                is ScanContent.Link -> R.string.scan_link
                is ScanContent.Wifi -> R.string.scan_wifi
                is ScanContent.Text -> R.string.scan_text
            }
            SectionLabel(stringResource(title) + " · " + found.format.replace('_', ' '))
            Spacer(Modifier.height(10.dp))
            when (content) {
                is ScanContent.Link -> {
                    SelectionContainer {
                        Text(content.url, style = CtrlType.Data, color = CtrlColors.Text, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                    Text(stringResource(R.string.scan_open_note), style = CtrlType.Label, color = CtrlColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    CtrlButton(stringResource(R.string.scan_open), onClick = {
                        context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(content.url)).addCategory(Intent.CATEGORY_BROWSABLE))
                    }, icon = Icons.Outlined.OpenInBrowser, modifier = Modifier.fillMaxWidth())
                }
                is ScanContent.Wifi -> {
                    Field(stringResource(R.string.scan_wifi_name), content.ssid)
                    Field(stringResource(R.string.scan_wifi_security), securityLabel(content.security))
                    content.password?.let { Field(stringResource(R.string.scan_wifi_password), it) }
                    if (content.hidden) Text(stringResource(R.string.scan_wifi_hidden), style = CtrlType.Label, color = CtrlColors.Muted)
                    Spacer(Modifier.height(14.dp))
                    val suggestion = remember(content) { wifiSuggestion(content) }
                    if (suggestion != null) {
                        CtrlButton(stringResource(R.string.scan_wifi_connect), onClick = {
                            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                            context.launch(intent)
                        }, icon = Icons.Outlined.Wifi, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.scan_wifi_note), style = CtrlType.Label, color = CtrlColors.Muted, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Text(stringResource(R.string.scan_wifi_unsupported), style = CtrlType.Label, color = CtrlColors.Muted)
                    }
                    content.password?.let { pw ->
                        Spacer(Modifier.height(10.dp))
                        CtrlSecondaryButton(onClick = { copy(pw, sensitive = true) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.scan_wifi_copy))
                        }
                    }
                }
                is ScanContent.Text -> {
                    SelectionContainer {
                        Text(content.raw, style = CtrlType.Data, color = CtrlColors.Text, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CtrlSecondaryButton(onClick = { copy(content.raw, sensitive = content is ScanContent.Wifi) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_copy))
                }
                CtrlSecondaryButton(onClick = { share(content.raw) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_share))
                }
            }
            Spacer(Modifier.height(10.dp))
            CtrlSecondaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_again)) }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = CtrlType.Label, color = CtrlColors.Muted)
        SelectionContainer { Text(value, style = CtrlType.Data, color = CtrlColors.Text) }
    }
}

@Composable
private fun securityLabel(s: ScanContent.Security) = stringResource(
    when (s) {
        ScanContent.Security.OPEN -> R.string.scan_wifi_sec_open
        ScanContent.Security.WEP -> R.string.scan_wifi_sec_wep
        ScanContent.Security.WPA -> R.string.scan_wifi_sec_wpa
        ScanContent.Security.SAE -> R.string.scan_wifi_sec_sae
        ScanContent.Security.UNKNOWN -> R.string.scan_wifi_sec_unknown
    },
)

/**
 * A network suggestion for Android's "add Wi-Fi network" screen (Settings.ACTION_WIFI_ADD_NETWORKS). That
 * screen asks the user and needs no permission from CameraCtrl. WEP and unknown types can't be suggested.
 */
private fun wifiSuggestion(w: ScanContent.Wifi): WifiNetworkSuggestion? = runCatching {
    val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid)
    when (w.security) {
        ScanContent.Security.OPEN -> Unit
        ScanContent.Security.WPA -> b.setWpa2Passphrase(w.password ?: return null)
        ScanContent.Security.SAE -> b.setWpa3Passphrase(w.password ?: return null)
        else -> return null
    }
    if (w.hidden) b.setIsHiddenSsid(true)
    b.build()
}.getOrNull()
