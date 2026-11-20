package com.vknxworks.cameractrl.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.CameraController
import com.vknxworks.cameractrl.camera.Lens
import com.vknxworks.cameractrl.camera.LensDetector
import com.vknxworks.cameractrl.camera.Manual
import com.vknxworks.cameractrl.camera.Mode
import com.vknxworks.cameractrl.camera.PhotoFormat
import com.vknxworks.cameractrl.camera.Setup
import com.vknxworks.cameractrl.camera.resolveRaw
import com.vknxworks.cameractrl.container
import com.vknxworks.cameractrl.interval.IntervalConfig
import com.vknxworks.cameractrl.interval.IntervalPlan
import com.vknxworks.cameractrl.interval.IntervalState
import com.vknxworks.cameractrl.ui.common.formatDuration
import com.vknxworks.cameractrl.ui.common.hasPermission
import com.vknxworks.cameractrl.ui.common.launch
import com.vknxworks.cameractrl.ui.components.MessageView
import com.vknxworks.cameractrl.ui.components.ProfileButton
import com.vknxworks.cameractrl.ui.lock.findActivity
import com.vknxworks.cameractrl.ui.nav.LocalNavigator
import com.vknxworks.cameractrl.ui.nav.ModesRoute
import com.vknxworks.cameractrl.ui.theme.CtrlColors
import com.vknxworks.cameractrl.ui.theme.CtrlType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal val Ink = Color(0xFFF2F2EE)
internal val Dim = Color(0xB3F2F2EE)
internal val Scrim = Color(0x66000000)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        LaunchedEffect(Unit) { ask.launch(Manifest.permission.CAMERA) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) { ProfileButton() }
            MessageView(
                Icons.Outlined.PhotoCamera,
                stringResource(R.string.cam_permission_title),
                stringResource(R.string.cam_permission_body),
                actionLabel = stringResource(R.string.allow_camera),
                onAction = { ask.launch(Manifest.permission.CAMERA) },
            )
        }
        return
    }
    // Reading every lens means many calls to the camera service: done off the main thread, so the first frame isn't
    // held up. Nothing below (the "no camera" message, the viewfinder, opening the camera) runs before the list is in.
    val detected by produceState<List<Lens>?>(null) {
        value = withContext(Dispatchers.Default) { LensDetector.detect(context.applicationContext) }
    }
    val lenses = detected
    if (lenses == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (lenses.isEmpty()) {
        MessageView(Icons.Outlined.PhotoCamera, stringResource(R.string.no_camera))
        return
    }
    // An interval run has the camera (in its foreground service): show the run, not the viewfinder.
    val run by context.container.interval.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(Mode.PHOTO) }
    if (run.active) {
        IntervalRunScreen(run, onStop = { context.container.interval.stop() })
        return
    }
    Viewfinder(lenses, mode, run) { mode = it }
}

@Composable
private fun Viewfinder(lenses: List<Lens>, mode: Mode, run: IntervalState, setMode: (Mode) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val controller = remember { CameraController(context.applicationContext) }
    val cam by controller.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { controller.release() } }
    controller.shutterSound = settings.shutterSound

    // A camera is held upright; the controls stay put and saved photos get the right orientation.
    val activity = context.findActivity()
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) controller.orientation = ((o + 45) / 90 * 90) % 360
            }
        }
        listener.enable()
        onDispose {
            listener.disable()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var front by rememberSaveable { mutableStateOf(false) }
    var lensKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pro by rememberSaveable { mutableStateOf(false) }
    var manual by remember { mutableStateOf(Manual()) }

    val group = lenses.filter { (it.facing == CameraMetadata.LENS_FACING_FRONT) == front }.ifEmpty { lenses }
    val lens = group.firstOrNull { it.key == lensKey } ?: group.firstOrNull { it.logical } ?: group.first()
    val maxRes = settings.maxRes && lens.supportsMaxRes && mode == Mode.PHOTO
    // Only Photo saves RAW; night, interval and scan work with JPEG (or nothing at all).
    val raw = if (mode == Mode.PHOTO) lens.resolveRaw(settings.rawProfile, maxRes) else null
    val format = if (mode == Mode.PHOTO) settings.format else PhotoFormat.JPEG
    val videoSize = remember(lens, settings.videoUhd) { chooseVideo(lens, settings.videoUhd) }
    val setup = Setup(lens, mode, format, raw, maxRes, videoSize, settings.hevc, settings.jpegQuality)

    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> { resumed = false; controller.close() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(setup, texture, resumed) {
        val t = texture
        if (t != null && resumed) controller.open(setup, t)
    }
    LaunchedEffect(manual) { controller.setManual(manual) }
    // A new lens starts with its own zoom and focus.
    LaunchedEffect(lens.key) { manual = manual.copy(zoom = 1f, focus = null) }

    val askAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(mode) {
        if (mode == Mode.VIDEO && !context.hasPermission(Manifest.permission.RECORD_AUDIO)) askAudio.launch(Manifest.permission.RECORD_AUDIO)
    }

    var countdown by remember { mutableIntStateOf(0) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusAt by remember { mutableLongStateOf(0L) }

    fun startInterval() {
        val plan = IntervalPlan(settings.intervalSeconds * 1000L, settings.intervalStop)
        val config = IntervalConfig(
            setup = setup.copy(mode = Mode.INTERVAL, format = PhotoFormat.JPEG, raw = null, maxRes = false),
            manual = manual,
            orientation = controller.orientation,
            plan = plan,
            video = settings.intervalVideo,
            uhd = settings.videoUhd,
            hevc = settings.hevc,
            shutterSound = settings.shutterSound,
        )
        // The run opens the camera itself, in its foreground service.
        controller.close()
        c.interval.start(config)
    }

    fun shoot() {
        when (mode) {
            Mode.VIDEO -> { controller.toggleRecording(); return }
            Mode.INTERVAL -> { if (cam.ready) startInterval(); return }
            Mode.SCAN -> return
            else -> Unit
        }
        if (cam.busy || countdown > 0) return
        val take = { if (mode == Mode.NIGHT) controller.captureNight(settings.nightPreset) else controller.capture() }
        if (settings.timer > 0) {
            scope.launch {
                for (i in settings.timer downTo 1) { countdown = i; delay(1000) }
                countdown = 0
                take()
            }
        } else take()
    }

    // The last interval run's result, shown for a moment.
    LaunchedEffect(run.resultUri, run.error, run.resultShots) {
        if (run.resultUri != null || run.error != null) {
            delay(8_000)
            c.interval.clearResult()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // ---------- Top bar ----------
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (mode == Mode.PHOTO) {
                    val label = when (settings.format) {
                        PhotoFormat.JPEG -> stringResource(R.string.fmt_jpeg)
                        PhotoFormat.RAW_JPEG -> stringResource(R.string.fmt_raw_jpeg)
                        PhotoFormat.RAW -> stringResource(R.string.fmt_raw)
                    }
                    Pill(label, active = settings.format != PhotoFormat.JPEG && raw != null) {
                        val next = PhotoFormat.entries[(settings.format.ordinal + 1) % PhotoFormat.entries.size]
                        scope.launch { c.settings.update { it.copy(format = next) } }
                    }
                    if (lens.supportsMaxRes) {
                        Pill(stringResource(R.string.max_res), active = settings.maxRes) {
                            scope.launch { c.settings.update { it.copy(maxRes = !it.maxRes) } }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (mode == Mode.PHOTO || mode == Mode.NIGHT) {
                    IconToggle(if (settings.timer > 0) Icons.Outlined.Timer else Icons.Outlined.TimerOff, stringResource(R.string.timer), settings.timer > 0, badge = settings.timer.takeIf { it > 0 }?.toString()) {
                        val next = when (settings.timer) { 0 -> 3; 3 -> 10; else -> 0 }
                        scope.launch { c.settings.update { it.copy(timer = next) } }
                    }
                }
                IconToggle(if (settings.grid) Icons.Outlined.Grid3x3 else Icons.Outlined.GridOff, stringResource(R.string.grid), settings.grid) {
                    scope.launch { c.settings.update { it.copy(grid = !it.grid) } }
                }
                if (lens.flash) {
                    IconToggle(if (manual.torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff, stringResource(R.string.torch), manual.torch) {
                        manual = manual.copy(torch = !manual.torch)
                    }
                }
                IconToggle(Icons.Outlined.Info, stringResource(R.string.modes_title), false) { nav.navigate(ModesRoute) }
                ProfileButton()
            }

            // ---------- Preview ----------
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratio = if (mode == Mode.VIDEO) {
                    videoSize?.let { it.height.toFloat() / it.width } ?: (9f / 16f)
                } else {
                    lens.pixelArray?.let { minOf(it.width, it.height).toFloat() / maxOf(it.width, it.height) } ?: 0.75f
                }
                Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = false)) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                keepScreenOn = true
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st }
                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { texture = null; controller.close(); return true }
                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (settings.grid && mode != Mode.SCAN) GridOverlay()
                    if (mode == Mode.SCAN) ScanFrame()
                    Box(
                        Modifier.fillMaxSize()
                            .pointerInputTap(lens.key) { o, w, h ->
                                focusPoint = o
                                focusAt = SystemClock.elapsedRealtime()
                                controller.focusAt(o.x / w, o.y / h)
                            }
                            .pointerInputZoom(lens.key) { factor ->
                                lens.zoom?.let { r -> manual = manual.copy(zoom = (manual.zoom * factor).coerceIn(r.lower, r.upper)) }
                            },
                    )
                    FocusRing(focusPoint, focusAt)
                    if (countdown > 0) {
                        Text(countdown.toString(), style = CtrlType.Hero, color = Ink, modifier = Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (cam.recording) RecordingClock(cam.recordStart)
                        cam.error?.let { e -> Tag(stringResource(e), Color(0xCCB3261E)) }
                        if (mode == Mode.PHOTO && settings.format != PhotoFormat.JPEG && raw == null) Tag(stringResource(R.string.no_raw_here), Scrim)
                        if (mode == Mode.NIGHT && cam.nightTotal > 0) {
                            val merging = cam.nightFrames >= cam.nightTotal
                            Tag(
                                if (merging) stringResource(R.string.night_merging, cam.nightTotal)
                                else stringResource(R.string.night_capturing, cam.nightFrames, cam.nightTotal),
                                CtrlColors.Accent.copy(alpha = 0.85f),
                            )
                        } else if (mode == Mode.NIGHT && cam.lastNightMerged > 0) {
                            Tag(stringResource(R.string.night_last, cam.lastNightMerged), Scrim)
                        }
                        if (mode == Mode.SCAN && cam.scanned == null) Tag(stringResource(R.string.scan_hint), Scrim)
                        run.error?.let { e -> Tag(stringResource(e), Color(0xCCB3261E)) }
                        if (run.resultUri != null) {
                            Tag(
                                if (run.resultIsVideo) stringResource(R.string.interval_saved_video, run.resultShots)
                                else pluralStringResource(R.plurals.interval_saved_photos, run.resultShots, run.resultShots),
                                Scrim,
                            )
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lens.zoom != null && abs(manual.zoom - 1f) > 0.04f) Tag("%.1f×".format(Locale.US, manual.zoom), Scrim)
                        if (cam.saving.isNotEmpty()) Tag(cam.saving, Scrim)
                    }
                }
            }

            // ---------- Lenses ----------
            LensRow(group, lens) { l -> lensKey = l.key }

            // ---------- Pro controls, or the mode's own options ----------
            AnimatedVisibility(pro && mode != Mode.SCAN, enter = fadeIn(), exit = fadeOut()) {
                ProPanel(lens, manual, mode) { manual = it }
            }
            if (!pro && mode == Mode.NIGHT) {
                NightPanel(settings.nightPreset, enabled = !cam.busy) { p -> scope.launch { c.settings.update { it.copy(nightPreset = p) } } }
            }
            if (!pro && mode == Mode.INTERVAL) {
                IntervalPanel(settings) { t -> scope.launch { c.settings.update(t) } }
            }

            // ---------- Shutter row ----------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val runResult = run.resultUri
                if (cam.lastUri == null && runResult != null) LastCapture(runResult, run.resultIsVideo) else LastCapture(cam.lastUri, cam.lastIsVideo)
                if (mode == Mode.SCAN) Spacer(Modifier.size(78.dp))
                else Shutter(mode, cam.recording, enabled = cam.ready && !cam.busy, onClick = ::shoot)
                RoundIcon(Icons.Outlined.Cameraswitch, stringResource(R.string.switch_camera), enabled = !cam.recording && !cam.busy && lenses.any { (it.facing == CameraMetadata.LENS_FACING_FRONT) != front }) {
                    front = !front
                    lensKey = null
                }
            }

            // ---------- Modes ----------
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val free = !cam.recording && !cam.busy
                ModeTab(stringResource(R.string.mode_photo), mode == Mode.PHOTO, enabled = free) { setMode(Mode.PHOTO) }
                ModeTab(stringResource(R.string.mode_night), mode == Mode.NIGHT, enabled = free) { setMode(Mode.NIGHT) }
                ModeTab(stringResource(R.string.mode_video), mode == Mode.VIDEO, enabled = free) { setMode(Mode.VIDEO) }
                ModeTab(stringResource(R.string.mode_interval), mode == Mode.INTERVAL, enabled = free) { setMode(Mode.INTERVAL) }
                ModeTab(stringResource(R.string.mode_scan), mode == Mode.SCAN, enabled = free) { setMode(Mode.SCAN) }
                if (mode != Mode.SCAN) ModeTab(stringResource(R.string.mode_pro), pro, enabled = true) { pro = !pro }
            }
        }
    }

    // ---------- A scanned code ----------
    cam.scanned?.let { found ->
        if (mode == Mode.SCAN) ScanResultSheet(found, onDismiss = { controller.resumeScan() })
    }
}

private fun chooseVideo(lens: Lens, uhd: Boolean): Size? {
    val exact = lens.videoSizes.filter { it.width * 9 == it.height * 16 }
    val wide = exact.ifEmpty { lens.videoSizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f } }
    val limit = if (uhd) 3840 else 1920
    return wide.filter { it.width <= limit }.maxByOrNull { it.width * it.height } ?: lens.videoSizes.maxByOrNull { it.width * it.height }
}

// ---------- Gestures ----------

private fun Modifier.pointerInputTap(key: Any, onTap: (Offset, Float, Float) -> Unit) = pointerInput(key) {
    detectTapGestures(onTap = { o -> onTap(o, size.width.toFloat(), size.height.toFloat()) })
}

private fun Modifier.pointerInputZoom(key: Any, onZoom: (Float) -> Unit) = pointerInput("zoom$key") {
    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) }
}

// ---------- Overlays ----------

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color(0x66FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.5f)
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1.5f)
        }
    }
}

@Composable
private fun FocusRing(point: Offset?, at: Long) {
    if (point == null) return
    var visible by remember(at) { mutableStateOf(true) }
    LaunchedEffect(at) { delay(1400); visible = false }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "focus")
    val scale by animateFloatAsState(if (visible) 1f else 0.8f, label = "focusScale")
    val half = with(LocalDensity.current) { (32.dp * scale).roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(point.x.roundToInt() - half, point.y.roundToInt() - half) }
            .size(64.dp * scale)
            .alpha(alpha)
            .border(BorderStroke(1.5.dp, Ink), CircleShape),
    )
}

@Composable
private fun RecordingClock(start: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCCB3261E)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Ink))
        Spacer(Modifier.width(6.dp))
        Text(formatDuration(now - start), style = CtrlType.Status, color = Ink)
    }
}

@Composable
internal fun Tag(text: String, bg: Color) {
    Text(
        text,
        style = CtrlType.Status,
        color = Ink,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ---------- Controls ----------

@Composable
internal fun Pill(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = CtrlType.LabelStrong,
        color = if (active) CtrlColors.OnAccent else Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) CtrlColors.Accent else Color(0x33FFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun IconToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, badge: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (active) CtrlColors.Accent else Ink, modifier = Modifier.size(22.dp))
        if (badge != null) {
            Text(badge, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Ink) }
}

@Composable
private fun Shutter(mode: Mode, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when {
            mode == Mode.NIGHT -> R.string.take_night_photo
            mode == Mode.INTERVAL -> R.string.start_interval
            mode != Mode.VIDEO -> R.string.take_photo
            recording -> R.string.stop_recording
            else -> R.string.start_recording
        },
    )
    val inner by animateFloatAsState(if (recording) 0.45f else 0.82f, label = "shutter")
    Box(
        Modifier.size(78.dp).clip(CircleShape).border(BorderStroke(4.dp, Ink), CircleShape)
            .clickable(enabled = enabled || recording, onClick = onClick, onClickLabel = label)
            .alpha(if (enabled || recording) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(78.dp * inner)
                .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (mode == Mode.VIDEO) Color(0xFFE5483B) else Ink),
        )
        if (mode == Mode.VIDEO && !recording) Icon(Icons.Outlined.Videocam, label, tint = Ink, modifier = Modifier.size(26.dp))
        if (mode == Mode.NIGHT) Icon(Icons.Outlined.NightsStay, label, tint = Color.Black, modifier = Modifier.size(26.dp))
        if (mode == Mode.INTERVAL) Icon(Icons.Outlined.Timelapse, label, tint = Color.Black, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun LastCapture(uri: android.net.Uri?, video: Boolean) {
    val context = LocalContext.current
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        thumb = uri?.let { u -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(u, Size(192, 192), null) }.getOrNull() } }
    }
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF))
            .clickable(enabled = uri != null) {
                val u = uri ?: return@clickable
                context.launch(Intent(Intent.ACTION_VIEW).setDataAndType(u, if (video) "video/*" else "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            },
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) Image(t.asImageBitmap(), stringResource(R.string.last_capture), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (uri != null) Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.last_capture), tint = Ink)
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = CtrlType.LabelStrong,
        color = if (selected) CtrlColors.Accent else Dim,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (enabled) 1f else 0.4f),
    )
}

/** The lenses on this side, by zoom factor relative to the main camera. AUTO is the phone's multi-camera. */
@Composable
private fun LensRow(group: List<Lens>, current: Lens, onPick: (Lens) -> Unit) {
    if (group.size < 2) return
    val main = group.firstOrNull { it.logical }?.equivMm?.takeIf { it > 0 }
        ?: group.minByOrNull { abs(it.equivMm - 24) }?.equivMm?.takeIf { it > 0 } ?: 24
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        group.sortedWith(compareBy({ !it.logical }, { it.equivMm })).forEach { l ->
            val label = if (l.logical) stringResource(R.string.lens_auto)
            else if (l.equivMm > 0) ratioLabel(l.equivMm.toFloat() / main) else l.key
            val selected = l.key == current.key
            Box(
                Modifier.size(width = if (l.logical) 56.dp else 44.dp, height = 44.dp).clip(CircleShape)
                    .background(if (selected) Color(0x55FFFFFF) else Color(0x22FFFFFF))
                    .clickable { onPick(l) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = CtrlType.Status, color = if (selected) CtrlColors.Accent else Ink)
            }
        }
    }
}

private fun ratioLabel(r: Float): String {
    val s = "%.1f".format(Locale.US, r).removeSuffix(".0")
    return s.removePrefix("0") + "×"
}

// ---------- Pro ----------

private enum class ProTab { ISO, SHUTTER, FOCUS, WB, EV }

@Composable
private fun ProPanel(lens: Lens, m: Manual, mode: Mode, onChange: (Manual) -> Unit) {
    var tab by remember { mutableStateOf(ProTab.ISO) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ProTab.entries.forEach { t ->
                val value = when (t) {
                    ProTab.ISO -> if (m.auto) stringResource(R.string.auto) else m.iso.toString()
                    ProTab.SHUTTER -> if (m.auto) stringResource(R.string.auto) else shutterLabel(m.exposureNs)
                    ProTab.FOCUS -> m.focus?.let { focusLabel(it) } ?: stringResource(R.string.af)
                    ProTab.WB -> stringResource(wbLabel(m.awb))
                    ProTab.EV -> evLabel(lens, m.ev)
                }
                val name = stringResource(
                    when (t) {
                        ProTab.ISO -> R.string.pro_iso
                        ProTab.SHUTTER -> R.string.pro_shutter
                        ProTab.FOCUS -> R.string.pro_focus
                        ProTab.WB -> R.string.pro_wb
                        ProTab.EV -> R.string.pro_ev
                    },
                )
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { tab = t }.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(name, style = CtrlType.Label, color = if (tab == t) CtrlColors.Accent else Dim)
                    Text(value, style = CtrlType.Status, color = Ink)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        val manualOk = lens.manualSensor && lens.iso != null && lens.exposureNs != null
        when (tab) {
            ProTab.ISO, ProTab.SHUTTER -> if (!manualOk) {
                Text(stringResource(R.string.no_manual), style = CtrlType.Data, color = Dim, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.auto), active = m.auto) { onChange(m.copy(auto = !m.auto)) }
                    Spacer(Modifier.width(12.dp))
                    if (tab == ProTab.ISO) {
                        val r = lens.iso!!
                        LogSlider(m.iso.toDouble(), r.lower.toDouble(), r.upper.toDouble(), enabled = true) { v -> onChange(m.copy(auto = false, iso = v.roundToInt())) }
                    } else {
                        val r = lens.exposureNs!!
                        val upper = minOf(r.upper, 30_000_000_000L).toDouble()
                        LogSlider(m.exposureNs.toDouble(), r.lower.toDouble(), upper, enabled = true) { v -> onChange(m.copy(auto = false, exposureNs = v.roundToLong())) }
                    }
                }
            }
            ProTab.FOCUS -> if (!lens.hasAf) {
                Text(stringResource(R.string.fixed_focus), style = CtrlType.Data, color = Dim, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(stringResource(R.string.af), active = m.focus == null) { onChange(m.copy(focus = if (m.focus == null) 0f else null)) }
                    Spacer(Modifier.width(12.dp))
                    var v by remember(m.focus) { mutableFloatStateOf(m.focus ?: 0f) }
                    Slider(
                        value = v,
                        onValueChange = { v = it; onChange(m.copy(focus = it)) },
                        valueRange = 0f..lens.minFocus,
                        colors = sliderColors(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ProTab.WB -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WB_ORDER.filter { it in lens.awbModes }.forEach { mode ->
                    Pill(stringResource(wbLabel(mode)), active = m.awb == mode) { onChange(m.copy(awb = mode)) }
                }
            }
            ProTab.EV -> {
                val r = lens.evRange
                if (r == null || r.lower == r.upper || !m.auto) {
                    Text(stringResource(if (!m.auto) R.string.ev_manual else R.string.no_ev), style = CtrlType.Data, color = Dim, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    var v by remember(m.ev) { mutableFloatStateOf(m.ev.toFloat()) }
                    Slider(
                        value = v,
                        onValueChange = { v = it; onChange(m.copy(ev = it.roundToInt())) },
                        valueRange = r.lower.toFloat()..r.upper.toFloat(),
                        steps = (r.upper - r.lower - 1).coerceAtLeast(0),
                        colors = sliderColors(),
                    )
                }
            }
        }
        if (mode == Mode.VIDEO && !m.auto) {
            Text(stringResource(R.string.video_manual_note), style = CtrlType.Label, color = Dim)
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = CtrlColors.Accent,
    activeTrackColor = CtrlColors.Accent,
    inactiveTrackColor = Color(0x44FFFFFF),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

/** A slider on a logarithmic scale (ISO and shutter speed feel even across their range). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LogSlider(value: Double, lower: Double, upper: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    val span = ln(upper / lower)
    var t by remember(value, lower, upper) { mutableFloatStateOf((ln(value.coerceIn(lower, upper) / lower) / span).toFloat()) }
    Slider(
        value = t,
        onValueChange = { t = it; onChange(lower * (upper / lower).pow(it.toDouble())) },
        enabled = enabled,
        colors = sliderColors(),
        modifier = Modifier.weight(1f),
    )
}

private val WB_ORDER = listOf(
    CameraMetadata.CONTROL_AWB_MODE_AUTO,
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    CameraMetadata.CONTROL_AWB_MODE_SHADE,
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT,
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT,
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
)

private fun wbLabel(mode: Int) = when (mode) {
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> R.string.wb_daylight
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> R.string.wb_cloudy
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> R.string.wb_shade
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> R.string.wb_twilight
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> R.string.wb_fluorescent
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> R.string.wb_warm_fluorescent
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> R.string.wb_incandescent
    else -> R.string.auto
}

fun shutterLabel(ns: Long): String =
    if (ns >= 300_000_000L) "%.1fs".format(Locale.US, ns / 1e9) else "1/" + (1e9 / ns).roundToInt()

fun focusLabel(diopters: Float): String =
    if (diopters < 0.01f) "∞" else "%.2f m".format(Locale.US, 1f / diopters)

private fun evLabel(lens: Lens, ev: Int): String {
    val step = lens.evStep?.toFloat() ?: return "0"
    val v = ev * step
    return if (abs(v) < 0.01f) "0" else "%+.1f".format(Locale.US, v)
}
