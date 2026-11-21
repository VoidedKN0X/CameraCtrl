package com.vknxworks.cameractrl.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.ExifInterface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vknxworks.cameractrl.R
import com.vknxworks.cameractrl.camera.FrameAssist
import com.vknxworks.cameractrl.camera.Level
import com.vknxworks.cameractrl.ui.components.CtrlButton
import com.vknxworks.cameractrl.ui.components.CtrlSecondaryButton
import com.vknxworks.cameractrl.ui.theme.CtrlColors
import com.vknxworks.cameractrl.ui.theme.CtrlType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The latest histogram and focus-peaking mask, worked out from a small copy of the preview. */
internal class FrameAids(val histogram: IntArray?, val peaking: Bitmap?)

/** Width of the preview copy the aids are worked out from (its height follows the preview's shape). */
private const val ANALYSIS_WIDTH = 240

/**
 * Histogram and focus peaking. A few times a second, the preview on screen is copied at [ANALYSIS_WIDTH] pixels
 * wide (TextureView.getBitmap, no extra camera stream, so every lens and mode keeps its outputs) and worked out
 * off the main thread. Runs only while one of them is on and the camera is open.
 */
@Composable
internal fun rememberFrameAids(view: TextureView?, histogram: Boolean, peaking: Boolean, ready: Boolean): FrameAids {
    var aids by remember { mutableStateOf(FrameAids(null, null)) }
    val peakColor = CtrlColors.Accent.toArgb()
    LaunchedEffect(view, histogram, peaking, ready) {
        aids = FrameAids(null, null)
        if (view == null || !ready || (!histogram && !peaking)) return@LaunchedEffect
        var frame: Bitmap? = null
        var pixels = IntArray(0)
        while (true) {
            delay(if (peaking) 150L else 300L)
            val vw = view.width
            val vh = view.height
            if (!view.isAvailable || vw <= 0 || vh <= 0) continue
            val w = ANALYSIS_WIDTH
            val h = (ANALYSIS_WIDTH.toLong() * vh / vw).toInt().coerceIn(1, 4 * ANALYSIS_WIDTH)
            val bmp = frame?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame = it }
            if (runCatching { view.getBitmap(bmp) }.isFailure) continue
            if (pixels.size != w * h) pixels = IntArray(w * h)
            val px = pixels
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            aids = withContext(Dispatchers.Default) {
                val hist = if (histogram) FrameAssist.histogram(px) else null
                val peak = if (peaking) {
                    val out = IntArray(w * h)
                    FrameAssist.peaking(px, w, h, out, peakColor)
                    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                } else null
                FrameAids(hist, peak)
            }
        }
    }
    return aids
}

/** A small brightness histogram: shadows on the left, highlights on the right. */
@Composable
internal fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.histogram)
    val accent = CtrlColors.Accent
    Canvas(modifier.size(120.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Scrim).semantics { contentDescription = label }) {
        // Square roots keep small but present tones visible next to a big peak.
        val top = bins.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val peak = sqrt(top.toFloat())
        val step = size.width / bins.size
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { i, n ->
                val y = size.height - size.height * 0.9f * sqrt(n.toFloat()) / peak
                lineTo(i * step, y)
                lineTo((i + 1) * step, y)
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Ink.copy(alpha = 0.8f))
        // Clipping warnings: something is pure black or pure white.
        if (bins.first() > top / 8) drawLine(accent, Offset(1f, 0f), Offset(1f, size.height), 3f)
        if (bins.last() > top / 8) drawLine(accent, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 3f)
    }
}

/**
 * The level: the phone's tilt from the accelerometer (gravity), or null when it's off or the phone lies flat.
 * Listens only while [enabled].
 */
@Composable
internal fun rememberTilt(enabled: Boolean): Float? {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sm == null || sensor == null) {
            tilt = null
            return@DisposableEffect onDispose { }
        }
        var smooth: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val t = Level.tilt(e.values[0], e.values[1], e.values[2])
                val prev = smooth
                // Smoothed, except across the jump from one side to the other (turning past 45°).
                smooth = if (t == null) null else if (prev == null || abs(t - prev) > 30f) t else prev + (t - prev) * 0.25f
                // Tenths of a degree are plenty; fewer updates, less redrawing.
                tilt = smooth?.let { (it * 10f).roundToInt() / 10f }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sm.unregisterListener(listener)
            tilt = null
        }
    }
    return tilt
}

/** A horizon line through the middle that stays level with the world; it turns accent-coloured when level. */
@Composable
internal fun LevelOverlay(tilt: Float) {
    val level = abs(tilt) < 1f
    val color = if (level) CtrlColors.Accent else Ink
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val half = size.width * 0.22f
        // Fixed marks at both ends show where level is.
        drawLine(Dim, Offset(c.x - half - 28f, c.y), Offset(c.x - half - 8f, c.y), 3f)
        drawLine(Dim, Offset(c.x + half + 8f, c.y), Offset(c.x + half + 28f, c.y), 3f)
        rotate(tilt, c) {
            drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), if (level) 4f else 3f)
        }
    }
}

/** Another app asked for one photo: show it, with "Retake" and "Use photo". */
@Composable
internal fun PhotoReview(jpeg: ByteArray, onRetake: () -> Unit, onUse: () -> Unit) {
    val image by produceState<Bitmap?>(null, jpeg) {
        value = withContext(Dispatchers.Default) { runCatching { decodeUpright(jpeg, 2048) }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        image?.let {
            Image(it.asImageBitmap(), stringResource(R.string.review_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.review_note), style = CtrlType.Label, color = Dim, modifier = Modifier.padding(bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CtrlSecondaryButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.review_retake)) }
                CtrlButton(stringResource(R.string.review_use), onClick = onUse, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Decodes a JPEG at most [maxSide] pixels on its long side, turned upright by its EXIF orientation. */
internal fun decodeUpright(jpeg: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
    if (degrees == 0 && scale >= 1f) return bmp
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(degrees.toFloat())
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
