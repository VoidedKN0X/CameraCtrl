package com.vknxworks.cameractrl.interval

import org.junit.Assert.assertEquals
import org.junit.Test

class YuvTest {
    private fun convert(color: Int): Triple<Int, Int, Int> {
        val argb = IntArray(4) { color }
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        Yuv.argbToI420(argb, 2, 2, y, u, v)
        return Triple(y[0].toInt() and 0xff, u[0].toInt() and 0xff, v[0].toInt() and 0xff)
    }

    @Test
    fun limitRangeBlackAndWhite() {
        assertEquals(Triple(16, 128, 128), convert(0xFF000000.toInt()))
        assertEquals(Triple(235, 128, 128), convert(0xFFFFFFFF.toInt()))
    }

    @Test
    fun primaries() {
        // BT.601 limited range reference values.
        assertEquals(Triple(82, 90, 240), convert(0xFFFF0000.toInt()))
        assertEquals(Triple(144, 54, 34), convert(0xFF00FF00.toInt()))
        assertEquals(Triple(41, 240, 110), convert(0xFF0000FF.toInt()))
    }

    @Test
    fun chromaIsTheBlockAverage() {
        val argb = intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF0000FF.toInt())
        val y = ByteArray(4)
        val u = ByteArray(1)
        val v = ByteArray(1)
        Yuv.argbToI420(argb, 2, 2, y, u, v)
        // Average colour (128, 0, 128).
        assertEquals(165, u[0].toInt() and 0xff)
        assertEquals(175, v[0].toInt() and 0xff)
    }
}
