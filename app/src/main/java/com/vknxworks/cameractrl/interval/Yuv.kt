package com.vknxworks.cameractrl.interval

/** Colour conversion for the timelapse encoder (plain Kotlin, unit-tested). */
object Yuv {
    /**
     * ARGB pixels to planar YUV 4:2:0 (BT.601, limited range: what video encoders expect). Chroma is the
     * average of each 2 × 2 block. [width] and [height] are even.
     */
    fun argbToI420(argb: IntArray, width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        require(width % 2 == 0 && height % 2 == 0)
        require(argb.size >= width * height && y.size >= width * height && u.size >= width * height / 4 && v.size >= width * height / 4)
        for (i in 0 until width * height) {
            val p = argb[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            y[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
        }
        val cw = width / 2
        for (cy in 0 until height / 2) {
            for (cx in 0 until cw) {
                var r = 0
                var g = 0
                var b = 0
                for (j in 0..1) for (k in 0..1) {
                    val p = argb[(cy * 2 + j) * width + cx * 2 + k]
                    r += (p shr 16) and 0xff
                    g += (p shr 8) and 0xff
                    b += p and 0xff
                }
                r = (r + 2) shr 2
                g = (g + 2) shr 2
                b = (b + 2) shr 2
                u[cy * cw + cx] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[cy * cw + cx] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
    }
}
