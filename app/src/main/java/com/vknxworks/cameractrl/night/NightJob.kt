package com.vknxworks.cameractrl.night

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Build
import com.vknxworks.cameractrl.camera.Saver
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One night shot: the burst's YUV frames arrive one by one, are aligned to the first and added to the
 * merge, then the result is saved as a JPEG. All the work runs on its own thread, one frame at a time,
 * so at most two full frames (the reference and the current one) are in memory.
 */
class NightJob(
    private val context: Context,
    private val preset: NightPreset,
    val expected: Int,
    private val orientation: Int,
    private val jpegQuality: Int,
    private val name: String,
    /** A frame was let go, so the camera's image reader can hand over the next one. */
    private val onImageClosed: () -> Unit,
    private val onProgress: (received: Int) -> Unit,
    private val onDone: (uri: Uri?, merged: Int) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "CameraCtrl-night").apply { priority = Thread.NORM_PRIORITY } }
    private var ref: Frame? = null
    private var refPyramid: List<Plane>? = null
    private var merger: Merger? = null
    private var scratch: Frame? = null
    private var received = 0
    private var failed = 0
    @Volatile private var finished = false

    /** Takes [image] (closed here, after copying). Called on the camera thread. */
    fun offer(image: Image) {
        if (finished) {
            image.close()
            onImageClosed()
            return
        }
        val queued = later {
            try {
                if (!finished) process(image) else image.close()
            } catch (_: Throwable) {
                runCatching { image.close() }
                failed++
            }
            onImageClosed()
            maybeFinish()
        }
        if (!queued) {
            image.close()
            onImageClosed()
        }
    }

    /** Runs [block] on the worker; false once the job is over and the worker has stopped. */
    private fun later(block: () -> Unit): Boolean = try {
        worker.execute(block)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    /** A frame of the burst failed; the merge goes on without it. */
    fun frameFailed() {
        later {
            failed++
            maybeFinish()
        }
    }

    /** Saves whatever arrived (after a timeout). */
    fun finishNow() {
        later { finish() }
    }

    private fun process(image: Image) {
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        val target = if (ref == null) Frame(w, h) else scratch?.takeIf { it.width == w && it.height == h } ?: Frame(w, h).also { scratch = it }
        image.use { copyYuv(it, target) }
        received++
        onProgress(received)
        val r = ref
        if (r == null) {
            ref = target
            refPyramid = Align.pyramid(target.luma)
            merger = Merger(target, maxFrames = 16)
            return
        }
        val shift = Align.estimate(refPyramid!!, Align.pyramid(target.luma), preset.maxShift)
        // A frame that doesn't match (the phone moved a lot, or something big changed) is left out.
        if (shift.error <= preset.maxError) merger!!.add(target, shift, Merger.thresholdFor(shift.error))
    }

    private fun maybeFinish() {
        if (received + failed >= expected) finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        val m = merger
        var uri: Uri? = null
        val merged = m?.count ?: 0
        if (m != null) {
            val r = ref!!
            uri = runCatching { save(m.toNv21(), r.width, r.height, merged) }.getOrNull()
        }
        ref = null
        refPyramid = null
        merger = null
        scratch = null
        worker.shutdown()
        onDone(uri, merged)
    }

    private fun save(nv21: ByteArray, w: Int, h: Int, frames: Int): Uri? {
        val out = ByteArrayOutputStream(w * h / 3)
        if (!YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), jpegQuality, out)) return null
        val tmp = File(context.cacheDir, "night-$name.jpg")
        try {
            tmp.writeBytes(out.toByteArray())
            val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(orientation).toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                setAttribute(ExifInterface.TAG_DATETIME, now)
                setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                // Honest about what this file is: several photos merged into one.
                setAttribute(ExifInterface.TAG_SOFTWARE, "CameraCtrl night mode ($frames-frame merge)")
                saveAttributes()
            }
            return Saver.saveJpegFile(context, tmp, "$name.jpg")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        fun exifOrientation(degrees: Int) = when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        /** Copies a YUV_420_888 image into [dst] (NV21 layout), whatever the planes' strides. */
        fun copyYuv(image: Image, dst: Frame) {
            require(image.format == ImageFormat.YUV_420_888)
            val w = dst.width
            val h = dst.height
            val yp = image.planes[0]
            val yb = yp.buffer
            val ys = yp.rowStride
            for (row in 0 until h) {
                yb.position(row * ys)
                yb.get(dst.y, row * w, w)
            }
            val up = image.planes[1]
            val vp = image.planes[2]
            val ub = up.buffer
            val vb = vp.buffer
            val cw = w / 2
            val ps = up.pixelStride
            val rs = up.rowStride
            val urow = ByteArray(rs)
            val vrow = ByteArray(vp.rowStride)
            for (row in 0 until h / 2) {
                // The last row can be shorter than the row stride.
                val un = minOf(rs, ub.limit() - row * rs)
                val vn = minOf(vp.rowStride, vb.limit() - row * vp.rowStride)
                ub.position(row * rs)
                ub.get(urow, 0, un)
                vb.position(row * vp.rowStride)
                vb.get(vrow, 0, vn)
                val o = row * w
                val vps = vp.pixelStride
                for (x in 0 until cw) {
                    dst.vu[o + x * 2] = vrow[x * vps]
                    dst.vu[o + x * 2 + 1] = urow[x * ps]
                }
            }
        }
    }
}
