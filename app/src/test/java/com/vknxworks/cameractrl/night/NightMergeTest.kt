package com.vknxworks.cameractrl.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NightMergeTest {
    private val w = 320
    private val h = 240

    /** A smooth but detailed synthetic scene (gradients, ripples and a few blocks), as 0..255 doubles. */
    private fun scene(x: Int, y: Int): Double {
        var v = 60.0 + 0.25 * x + 0.2 * y + 30 * sin(x / 7.0) * sin(y / 11.0)
        if ((x / 40 + y / 30) % 3 == 0) v += 40
        if (x in 200..230 && y in 50..90) v = 230.0
        return v.coerceIn(0.0, 255.0)
    }

    /** The scene moved by (dx, dy) (so img(x + dx, y + dy) = scene(x, y)) with Gaussian noise of [sigma]. */
    private fun frame(dx: Int, dy: Int, sigma: Double, rnd: Random, override: ((Int, Int) -> Double?)? = null): Frame {
        val f = Frame(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x - dx).coerceIn(0, w - 1)
            val sy = (y - dy).coerceIn(0, h - 1)
            val base = override?.invoke(x, y) ?: scene(sx, sy)
            f.y[y * w + x] = (base + gauss(rnd) * sigma).toInt().coerceIn(0, 255).toByte()
        }
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            f.vu[y * w + x * 2] = (128 + (gauss(rnd) * sigma / 2).toInt()).coerceIn(0, 255).toByte()
            f.vu[y * w + x * 2 + 1] = (128 + (gauss(rnd) * sigma / 2).toInt()).coerceIn(0, 255).toByte()
        }
        return f
    }

    private fun gauss(rnd: Random): Double {
        // Box-Muller.
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private fun rmsAgainstScene(y: ByteArray, margin: Int = 12): Double {
        var s = 0.0
        var n = 0
        for (yy in margin until h - margin) for (x in margin until w - margin) {
            val d = (y[yy * w + x].toInt() and 0xff) - scene(x, yy)
            s += d * d
            n++
        }
        return sqrt(s / n)
    }

    @Test
    fun downscaleAveragesBlocks() {
        val p = Plane(4, 2, byteArrayOf(0, 10, 100, 200.toByte(), 20, 30, 100, 0))
        val d = Align.downscale(p, 2)
        assertEquals(2, d.width)
        assertEquals(1, d.height)
        assertEquals(15, d[0, 0])
        assertEquals(100, d[1, 0])
    }

    @Test
    fun pyramidStopsAtTheMinimumSide() {
        val levels = Align.pyramid(Plane(400, 300), minSide = 48)
        assertEquals(listOf(400, 200, 100), levels.map { it.width })
        assertTrue(levels.all { minOf(it.width, it.height) >= 48 })
    }

    @Test
    fun findsAKnownTranslation() {
        val rnd = Random(1)
        val ref = frame(0, 0, 4.0, rnd)
        for ((dx, dy) in listOf(7 to -4, -11 to 9, 0 to 0, 3 to 15, -16 to -2)) {
            val img = frame(dx, dy, 4.0, rnd)
            val s = Align.estimate(Align.pyramid(ref.luma), Align.pyramid(img.luma), maxShiftFraction = 0.08)
            assertEquals("dx for ($dx, $dy)", dx, s.dx)
            assertEquals("dy for ($dx, $dy)", dy, s.dy)
            assertTrue("error ${s.error} should be about the noise", s.error < 10)
        }
    }

    @Test
    fun meanAbsDiffRefusesTinyOverlaps() {
        val p = Plane(100, 100)
        assertEquals(Double.MAX_VALUE, Align.meanAbsDiff(p, p, 80, 0), 0.0)
        assertEquals(0.0, Align.meanAbsDiff(p, p, 10, 10), 0.0)
    }

    @Test
    fun mergingAlignedFramesLowersNoise() {
        val rnd = Random(2)
        val sigma = 12.0
        val ref = frame(0, 0, sigma, rnd)
        val single = rmsAgainstScene(ref.y)
        val pyr = Align.pyramid(ref.luma)
        val m = Merger(ref)
        val shifts = listOf(3 to 1, -2 to 4, 5 to -3, 1 to 1, -4 to -2, 2 to -5, 0 to 3)
        for ((dx, dy) in shifts) {
            val f = frame(dx, dy, sigma, rnd)
            val s = Align.estimate(pyr, Align.pyramid(f.luma), maxShiftFraction = 0.08)
            m.add(f, s, Merger.thresholdFor(s.error))
        }
        assertEquals(8, m.count)
        val merged = m.toNv21().copyOfRange(0, w * h)
        val after = rmsAgainstScene(merged)
        // Eight frames: noise should drop to well under half (about 1/√8 ≈ 0.35 in theory).
        assertTrue("noise $single -> $after", after < single * 0.5)
    }

    @Test
    fun withoutAlignmentTheMergeIsWorse() {
        val rnd = Random(3)
        val ref = frame(0, 0, 6.0, rnd)
        val aligned = Merger(ref)
        val naive = Merger(ref)
        val pyr = Align.pyramid(ref.luma)
        for ((dx, dy) in listOf(6 to 4, -5 to 3, 4 to -6)) {
            val f = frame(dx, dy, 6.0, rnd)
            val s = Align.estimate(pyr, Align.pyramid(f.luma), maxShiftFraction = 0.08)
            aligned.add(f, s, 255)
            naive.add(f, Shift(0, 0, 0.0), 255)
        }
        val a = rmsAgainstScene(aligned.toNv21().copyOfRange(0, w * h))
        val n = rmsAgainstScene(naive.toNv21().copyOfRange(0, w * h))
        assertTrue("aligned $a should beat unaligned $n", a < n * 0.7)
    }

    @Test
    fun movingThingsTakeTheReference() {
        val rnd = Random(4)
        val ref = frame(0, 0, 2.0, rnd)
        val m = Merger(ref)
        // Every other frame has a bright square where the reference has none (something walked through).
        repeat(5) {
            val f = frame(0, 0, 2.0, rnd) { x, y -> if (x in 20..60 && y in 150..190) 250.0 else null }
            m.add(f, Shift(0, 0, 2.0), Merger.thresholdFor(2.0))
        }
        val out = m.toNv21()
        var worst = 0
        for (y in 150..190) for (x in 20..60) {
            val d = abs((out[y * w + x].toInt() and 0xff) - (ref.y[y * w + x].toInt() and 0xff))
            worst = maxOf(worst, d)
        }
        assertTrue("no ghost of the square (worst difference $worst)", worst <= 3)
    }

    @Test
    fun mergeOfIdenticalFramesIsUnchanged() {
        val rnd = Random(5)
        val ref = frame(0, 0, 0.0, rnd)
        val m = Merger(ref)
        repeat(3) { m.add(ref, Shift(0, 0, 0.0), 16) }
        val out = m.toNv21()
        assertTrue(ref.y.indices.all { out[it] == ref.y[it] })
        assertTrue(ref.vu.indices.all { out[w * h + it] == ref.vu[it] })
    }

    @Test
    fun chromaFollowsHalfTheLumaShift() {
        val ref = Frame(8, 4)
        for (i in ref.vu.indices) ref.vu[i] = 100
        val f = Frame(8, 4)
        // Chroma rows are 8 bytes (4 V,U pairs). Mark pair 2 of row 1 in the moved frame.
        for (i in f.vu.indices) f.vu[i] = 100
        f.vu[1 * 8 + 2 * 2] = 110
        f.vu[1 * 8 + 2 * 2 + 1] = 110
        val m = Merger(ref)
        // Luma shift (2, 2) is chroma shift (1, 1): ref chroma pair (1, 0) reads frame pair (2, 1).
        m.add(f, Shift(2, 2, 0.0), 255)
        val out = m.toNv21()
        assertEquals(105, out[8 * 4 + 0 * 8 + 1 * 2].toInt() and 0xff)
        assertEquals(100, out[8 * 4 + 0 * 8 + 0 * 2].toInt() and 0xff)
    }

    @Test
    fun thresholdStaysInItsRange() {
        assertEquals(16, Merger.thresholdFor(0.5))
        assertEquals(30, Merger.thresholdFor(10.0))
        assertEquals(80, Merger.thresholdFor(100.0))
    }

    @Test
    fun handheldKeepsBrightnessAndNeverExposesLonger() {
        val (t, iso) = NightExposure.plan(aeNs = 100_000_000L, aeIso = 800, isoMin = 50, isoMax = 6400, nsMin = 10_000L, nsMax = 1_000_000_000L, preset = NightPreset.HANDHELD)
        assertEquals(66_000_000L, t)
        assertEquals(1212, iso) // 0.1 s × 800 = 0.066 s × 1212
    }

    @Test
    fun tripodTradesIsoForTime() {
        val (t, iso) = NightExposure.plan(aeNs = 50_000_000L, aeIso = 1600, isoMin = 100, isoMax = 6400, nsMin = 10_000L, nsMax = 2_000_000_000L, preset = NightPreset.TRIPOD)
        assertEquals(500_000_000L, t)
        assertEquals(160, iso)
        // Bright scene: ISO bottoms out, so the time is only as long as needed.
        val (t2, iso2) = NightExposure.plan(aeNs = 10_000_000L, aeIso = 200, isoMin = 100, isoMax = 6400, nsMin = 10_000L, nsMax = 2_000_000_000L, preset = NightPreset.TRIPOD)
        assertEquals(20_000_000L, t2)
        assertEquals(100, iso2)
    }

    @Test
    fun tripodRespectsTheLensLongestExposure() {
        val (t, iso) = NightExposure.plan(aeNs = 100_000_000L, aeIso = 3200, isoMin = 100, isoMax = 6400, nsMin = 10_000L, nsMax = 250_000_000L, preset = NightPreset.TRIPOD)
        assertEquals(250_000_000L, t)
        assertEquals(1280, iso)
    }
}
