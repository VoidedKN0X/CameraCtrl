package com.vknxworks.cameractrl.interval

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max

/**
 * Builds a timelapse video from the run's JPEG shots with MediaCodec, one frame per shot, as the shots come
 * in (nothing is kept in between). Every frame gets its own timestamp at [fps], so the video plays at an even
 * speed however long the interval is. Each shot is scaled to fill the frame (cropped at the edges if its
 * shape differs). The frame size and orientation follow the first shot. Not thread-safe: one caller thread.
 */
class TimelapseEncoder(private val file: File, private val uhd: Boolean, private val hevc: Boolean, private val fps: Int = FPS) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxing = false
    private var width = 0
    private var height = 0
    private val info = MediaCodec.BufferInfo()
    private var argb = IntArray(0)
    private var yPlane = ByteArray(0)
    private var uPlane = ByteArray(0)
    private var vPlane = ByteArray(0)
    private var canvasBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Frames written so far. */
    var frames = 0
        private set

    /** Adds one shot. False when it couldn't be decoded or encoded (the video goes on without it). */
    fun add(jpeg: ByteArray): Boolean {
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val upright = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
        if (codec == null && !start(landscape = upright.first >= upright.second)) return false
        val w = width
        val h = height
        // Decode at no less than the frame size (a power-of-two step down from the full photo).
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortSide / (sample * 2) >= minOf(w, h)) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        try {
            val target = canvasBitmap ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvasBitmap = it }
            val m = Matrix()
            m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
            m.postRotate(rotation.toFloat())
            val rw = if (rotation % 180 == 0) bmp.width else bmp.height
            val rh = if (rotation % 180 == 0) bmp.height else bmp.width
            val scale = max(w.toFloat() / rw, h.toFloat() / rh)
            m.postScale(scale, scale)
            m.postTranslate(w / 2f, h / 2f)
            val canvas = Canvas(target)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bmp, m, paint)
            target.getPixels(argb, 0, w, 0, 0, w, h)
        } finally {
            bmp.recycle()
        }
        Yuv.argbToI420(argb, w, h, yPlane, uPlane, vPlane)
        return queue(frames.toLong() * 1_000_000L / fps, endOfStream = false).also { if (it) frames++ }
    }

    private fun start(landscape: Boolean): Boolean {
        val candidates = buildList {
            if (hevc && uhd) add(Triple(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160))
            if (uhd) add(Triple(MediaFormat.MIMETYPE_VIDEO_AVC, 3840, 2160))
            if (hevc) add(Triple(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080))
            add(Triple(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080))
        }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for ((mime, long, short) in candidates) {
            val w = if (landscape) long else short
            val h = if (landscape) short else long
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, if (long >= 3840) 40_000_000 else 16_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val name = list.findEncoderForFormat(format) ?: continue
            val c = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            val ok = runCatching { c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); c.start() }.isSuccess
            if (!ok) {
                runCatching { c.release() }
                continue
            }
            codec = c
            width = w
            height = h
            argb = IntArray(w * h)
            yPlane = ByteArray(w * h)
            uPlane = ByteArray(w * h / 4)
            vPlane = ByteArray(w * h / 4)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            return true
        }
        return false
    }

    private fun queue(ptsUs: Long, endOfStream: Boolean): Boolean {
        val c = codec ?: return false
        var index: Int
        var tries = 0
        while (true) {
            index = c.dequeueInputBuffer(10_000)
            if (index >= 0) break
            drain(false)
            if (++tries > 500) return false
        }
        if (endOfStream) {
            c.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        val image = c.getInputImage(index) ?: return false
        writePlanes(image)
        c.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
        drain(false)
        return true
    }

    /** Copies the I420 planes into the encoder's input image, whatever its strides. */
    private fun writePlanes(image: Image) {
        val w = width
        val h = height
        val planes = image.planes
        fun put(planeIndex: Int, src: ByteArray, pw: Int, ph: Int) {
            val p = planes[planeIndex]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            if (ps == 1) {
                for (row in 0 until ph) {
                    buf.position(row * rs)
                    buf.put(src, row * pw, pw)
                }
            } else {
                for (row in 0 until ph) {
                    val o = row * rs
                    val so = row * pw
                    for (x in 0 until pw) buf.put(o + x * ps, src[so + x])
                }
            }
        }
        put(0, yPlane, w, h)
        put(1, uPlane, w / 2, h / 2)
        put(2, vPlane, w / 2, h / 2)
    }

    private fun drain(untilEnd: Boolean) {
        val c = codec ?: return
        var waits = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                // At the end, give the encoder up to about 10 s to hand over its last frames.
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || ++waits > 1_000) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (!muxing) {
                        track = m.addTrack(c.outputFormat)
                        m.start()
                        muxing = true
                    }
                }
                index >= 0 -> {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /** Finishes the file. True when it holds a playable video (at least one frame). */
    fun finish(): Boolean {
        val c = codec
        var ok = false
        try {
            if (c != null && frames > 0) {
                queue(frames.toLong() * 1_000_000L / fps, endOfStream = true)
                drain(true)
                ok = muxing
            }
        } catch (_: Exception) {
            ok = false
        } finally {
            runCatching { c?.stop() }
            runCatching { c?.release() }
            codec = null
            if (muxing) ok = runCatching { muxer?.stop() }.isSuccess && ok
            runCatching { muxer?.release() }
            muxer = null
            canvasBitmap?.recycle()
            canvasBitmap = null
        }
        return ok
    }

    companion object {
        const val FPS = 30
    }
}
