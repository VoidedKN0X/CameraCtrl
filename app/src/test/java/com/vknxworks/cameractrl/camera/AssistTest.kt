package com.vknxworks.cameractrl.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun histogramBins() {
        val h = FrameAssist.histogram(intArrayOf(grey(0), grey(0), grey(255), grey(128)), bins = 4)
        assertEquals(listOf(2, 0, 1, 1), h.toList())
    }

    @Test
    fun peakingMarksAHardEdgeOnly() {
        val w = 20
        val h = 20
        val frame = IntArray(w * h) { i -> if (i % w < 10) grey(20) else grey(220) }
        val out = IntArray(w * h)
        val n = FrameAssist.peaking(frame, w, h, out, 0xFFFF0000.toInt())
        assertTrue(n > 0)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val edge = x == 9 || x == 10
            assertEquals("x=$x y=$y", edge, out[y * w + x] != 0)
        }
    }

    @Test
    fun peakingIgnoresSoftGradients() {
        val w = 32
        val h = 8
        val frame = IntArray(w * h) { i -> grey((i % w) * 4) }
        val out = IntArray(w * h)
        assertEquals(0, FrameAssist.peaking(frame, w, h, out, -1))
    }

    @Test
    fun levelTilt() {
        assertEquals(0f, Level.tilt(0f, 9.8f, 0f)!!, 0.01f)
        assertEquals(0f, Level.tilt(9.8f, 0f, 0f)!!, 0.01f)
        assertEquals(10f, Level.tilt(1.7016f, 9.6512f, 0f)!!, 0.1f)
        assertEquals(-10f, Level.tilt(-1.7016f, 9.6512f, 0f)!!, 0.1f)
        assertNull(Level.tilt(0.5f, 0.5f, 9.8f))
    }
}
