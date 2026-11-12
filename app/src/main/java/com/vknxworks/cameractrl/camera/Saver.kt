package com.vknxworks.cameractrl.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Saves into DCIM/CameraCtrl (photos) and Movies/CameraCtrl (videos) through MediaStore. No location is
 * ever written: CameraCtrl doesn't have the location permission.
 */
object Saver {
    fun insert(context: Context, name: String, mime: String, video: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DCIM) + "/CameraCtrl")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return runCatching { context.contentResolver.insert(coll, values) }.getOrNull()
    }

    fun finish(context: Context, uri: Uri) {
        runCatching { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) }
    }

    private inline fun write(context: Context, name: String, mime: String, block: (java.io.OutputStream) -> Unit): Uri? {
        val uri = insert(context, name, mime, video = false) ?: return null
        val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use(block) }.isSuccess
        if (!ok) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return null
        }
        finish(context, uri)
        return uri
    }

    fun saveJpeg(context: Context, image: Image, name: String): Uri? {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        return write(context, "$name.jpg", "image/jpeg") { it.write(bytes) }
    }

    /** RAW16 is written as it comes; RAW10 and RAW12 are unpacked to 16 bits per pixel first. All become DNG files. */
    fun saveRaw(context: Context, image: Image, lens: Lens, result: TotalCaptureResult, orientation: Int, name: String): Uri? {
        val res = lens.physicalId?.let { result.physicalCameraTotalResults[it] } ?: result
        return write(context, "$name.dng", "image/x-adobe-dng") { out ->
            DngCreator(lens.chars, res).use { dng ->
                dng.setOrientation(exifOrientation(orientation))
                when (image.format) {
                    ImageFormat.RAW_SENSOR -> dng.writeImage(out, image)
                    ImageFormat.RAW10, ImageFormat.RAW12 -> dng.writeByteBuffer(out, Size(image.width, image.height), unpack(image), 0)
                    else -> throw IllegalArgumentException("unsupported raw format")
                }
            }
        }
    }

    private fun exifOrientation(degrees: Int) = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Android's packed RAW layouts to plain 16-bit samples.
     * RAW10: 4 pixels in 5 bytes (the high 8 bits of each, then a byte with their low 2 bits).
     * RAW12: 2 pixels in 3 bytes (the high 8 bits of each, then a byte with their low 4 bits).
     */
    private fun unpack(image: Image): ByteBuffer {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val src = plane.buffer
        val stride = plane.rowStride
        val out = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        val row = ByteArray(stride)
        val raw10 = image.format == ImageFormat.RAW10
        for (y in 0 until h) {
            src.position(y * stride)
            val n = min(stride, src.remaining())
            src.get(row, 0, n)
            var x = 0
            var i = 0
            if (raw10) {
                while (x + 3 < w && i + 4 < n) {
                    val lo = row[i + 4].toInt() and 0xff
                    for (k in 0 until 4) {
                        val hi = row[i + k].toInt() and 0xff
                        out.putShort(((hi shl 2) or ((lo shr (2 * k)) and 0x3)).toShort())
                    }
                    x += 4
                    i += 5
                }
            } else {
                while (x + 1 < w && i + 2 < n) {
                    val lo = row[i + 2].toInt() and 0xff
                    out.putShort((((row[i].toInt() and 0xff) shl 4) or (lo and 0xf)).toShort())
                    out.putShort((((row[i + 1].toInt() and 0xff) shl 4) or ((lo shr 4) and 0xf)).toShort())
                    x += 2
                    i += 3
                }
            }
            // Pad a short last row so the buffer always holds width × height samples.
            while (x < w) { out.putShort(0); x++ }
        }
        out.rewind()
        return out
    }
}
