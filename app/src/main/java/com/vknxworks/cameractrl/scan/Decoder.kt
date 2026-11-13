package com.vknxworks.cameractrl.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** A decoded code: its text and the barcode format's name (QR_CODE, EAN_13…). */
data class Scanned(val text: String, val format: String) {
    val content: ScanContent by lazy { ScanContent.parse(text) }
}

/**
 * QR codes and barcodes with ZXing (Apache-2.0), entirely on the phone. Takes the camera's luma plane.
 * Not thread-safe: one decoder per analysis thread.
 */
class Decoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.CODABAR,
                ),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }
    private var rotated = ByteArray(0)

    /**
     * Decodes [luma] (width × height, no padding). Barcodes are read along the rows, and the sensor's rows run
     * across a phone held upright, so a frame that has nothing is tried once more turned by 90°.
     */
    fun decode(luma: ByteArray, width: Int, height: Int): Scanned? {
        tryDecode(luma, width, height)?.let { return it }
        if (rotated.size < width * height) rotated = ByteArray(width * height)
        // Rotate clockwise: (x, y) -> (height - 1 - y, x).
        for (y in 0 until height) {
            val row = y * width
            val nx = height - 1 - y
            for (x in 0 until width) rotated[x * height + nx] = luma[row + x]
        }
        return tryDecode(rotated, height, width)
    }

    private fun tryDecode(data: ByteArray, w: Int, h: Int): Scanned? = try {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val r = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        r.text?.takeIf { it.isNotEmpty() }?.let { Scanned(it, r.barcodeFormat.name) }
    } catch (_: ReaderException) {
        null
    } catch (_: RuntimeException) {
        null
    } finally {
        reader.reset()
    }
}
