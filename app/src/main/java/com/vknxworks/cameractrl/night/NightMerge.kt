package com.vknxworks.cameractrl.night

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Night mode maths, in plain Kotlin so it can be unit-tested on the computer: global translation alignment
 * on a luma pyramid, and a reference-guided average of the aligned frames. No Android classes here.
 */

/** One 8-bit image plane (luma), row by row with no padding. */
class Plane(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init {
        require(width > 0 && height > 0 && data.size >= width * height)
    }

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xff
}

/**
 * A YUV 4:2:0 frame in NV21 layout: [y] is width × height, [vu] holds the half-resolution chroma as
 * interleaved V,U pairs (width × height / 2 bytes). Width and height are even.
 */
class Frame(val width: Int, val height: Int, val y: ByteArray = ByteArray(width * height), val vu: ByteArray = ByteArray(width * height / 2)) {
    init {
        require(width > 1 && height > 1 && width % 2 == 0 && height % 2 == 0)
        require(y.size >= width * height && vu.size >= width * height / 2)
    }

    val luma: Plane get() = Plane(width, height, y)
}

/**
 * How [img] is moved against the reference: img(x + dx, y + dy) shows what ref(x, y) shows.
 * [error] is the mean absolute luma difference per sample at that shift (noise plus whatever moved).
 */
data class Shift(val dx: Int, val dy: Int, val error: Double)

object Align {
    /** Box-filter downscale by a whole factor (a remainder at the right and bottom edges is dropped). */
    fun downscale(src: Plane, factor: Int): Plane {
        require(factor >= 1)
        if (factor == 1) return src
        val w = max(1, src.width / factor)
        val h = max(1, src.height / factor)
        val out = ByteArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            val y0 = y * factor
            for (x in 0 until w) {
                val x0 = x * factor
                var s = 0
                for (j in 0 until factor) {
                    var i = (y0 + j) * src.width + x0
                    for (k in 0 until factor) s += src.data[i++].toInt() and 0xff
                }
                out[y * w + x] = ((s + area / 2) / area).toByte()
            }
        }
        return Plane(w, h, out)
    }

    /** [0] is [src]; each next level is half the size, until the short side would drop below [minSide]. */
    fun pyramid(src: Plane, minSide: Int = 48): List<Plane> {
        val levels = arrayListOf(src)
        while (min(levels.last().width, levels.last().height) / 2 >= minSide) levels += downscale(levels.last(), 2)
        return levels
    }

    /**
     * Mean absolute difference between ref(x, y) and img(x + dx, y + dy) over the part where both exist,
     * sampled every [step] pixels. Shifts that leave less than a quarter of the frame overlapping are refused.
     */
    fun meanAbsDiff(ref: Plane, img: Plane, dx: Int, dy: Int, step: Int = 1): Double {
        val w = min(ref.width, img.width)
        val h = min(ref.height, img.height)
        val xs = max(0, -dx)
        val xe = min(w, w - dx)
        val ys = max(0, -dy)
        val ye = min(h, h - dy)
        if (xe - xs < 2 || ye - ys < 2) return Double.MAX_VALUE
        if ((xe - xs).toLong() * (ye - ys) * 4 < w.toLong() * h) return Double.MAX_VALUE
        var sum = 0L
        var n = 0L
        var y = ys
        while (y < ye) {
            val ro = y * ref.width
            val io = (y + dy) * img.width + dx
            var x = xs
            while (x < xe) {
                sum += abs((ref.data[ro + x].toInt() and 0xff) - (img.data[io + x].toInt() and 0xff))
                n++
                x += step
            }
            y += step
        }
        return if (n == 0L) Double.MAX_VALUE else sum.toDouble() / n
    }

    /**
     * Global translation of [img] against [ref], coarse to fine: an exhaustive search of ±[maxShiftFraction]
     * of the width on the smallest level, then ±2 pixels on each larger level. Both pyramids come from
     * [pyramid] with frames of the same size. Each level is sampled at no more than [maxSamples] points.
     */
    fun estimate(ref: List<Plane>, img: List<Plane>, maxShiftFraction: Double = 0.06, maxSamples: Int = 200_000): Shift {
        val top = min(ref.size, img.size) - 1
        require(top >= 0)
        var dx = 0
        var dy = 0
        var err = Double.MAX_VALUE
        for (level in top downTo 0) {
            val r = ref[level]
            val i = img[level]
            val radius = if (level == top) max(1, ceil(r.width * maxShiftFraction).toInt()) else 2
            if (level != top) {
                dx *= 2
                dy *= 2
            }
            val step = max(1, ceil(sqrt(r.width.toDouble() * r.height / maxSamples)).toInt())
            var best = Double.MAX_VALUE
            var bx = dx
            var by = dy
            for (sy in -radius..radius) {
                for (sx in -radius..radius) {
                    val e = meanAbsDiff(r, i, dx + sx, dy + sy, step)
                    // Ties go to the smaller movement, so a flat scene stays put.
                    if (e < best || (e == best && abs(dx + sx) + abs(dy + sy) < abs(bx) + abs(by))) {
                        best = e
                        bx = dx + sx
                        by = dy + sy
                    }
                }
            }
            dx = bx
            dy = by
            err = best
        }
        return Shift(dx, dy, err)
    }
}

/**
 * Reference-guided average: the first frame is the reference; every other frame is added after shifting it by
 * its [Shift]. Where an added pixel differs from the reference by more than the threshold (something moved),
 * the reference pixel is used instead, so moving things don't leave ghosts. Everywhere else the frames are
 * averaged, which lowers random noise by about the square root of the number of frames.
 */
class Merger(private val ref: Frame, maxFrames: Int = 16) {
    private val w = ref.width
    private val h = ref.height
    private val sumY: ShortArray
    private val sumVU: ShortArray

    /** How many frames are in the sums, the reference included. */
    var count = 1
        private set

    init {
        require(maxFrames in 1..128) // 128 × 255 still fits in a Short
        sumY = ShortArray(w * h) { (ref.y[it].toInt() and 0xff).toShort() }
        sumVU = ShortArray(w * h / 2) { (ref.vu[it].toInt() and 0xff).toShort() }
    }

    /** Adds [f], moved by [s]. Pixels further than [threshold] from the reference count as the reference. */
    fun add(f: Frame, s: Shift, threshold: Int) {
        require(f.width == w && f.height == h)
        val ry = ref.y
        val fy = f.y
        for (y in 0 until h) {
            val sy = (y + s.dy).coerceIn(0, h - 1) * w
            val row = y * w
            for (x in 0 until w) {
                val r = ry[row + x].toInt() and 0xff
                val v = fy[sy + (x + s.dx).coerceIn(0, w - 1)].toInt() and 0xff
                sumY[row + x] = (sumY[row + x] + if (abs(v - r) > threshold) r else v).toShort()
            }
        }
        // Chroma is at half resolution; its shift is the luma shift halved.
        val cw = w / 2
        val ch = h / 2
        val cdx = Math.floorDiv(s.dx, 2)
        val cdy = Math.floorDiv(s.dy, 2)
        val rvu = ref.vu
        val fvu = f.vu
        for (y in 0 until ch) {
            val sy = (y + cdy).coerceIn(0, ch - 1) * w
            val row = y * w
            for (x in 0 until cw) {
                val sx = (x + cdx).coerceIn(0, cw - 1) * 2
                for (k in 0..1) {
                    val i = row + x * 2 + k
                    val r = rvu[i].toInt() and 0xff
                    val v = fvu[sy + sx + k].toInt() and 0xff
                    sumVU[i] = (sumVU[i] + if (abs(v - r) > threshold) r else v).toShort()
                }
            }
        }
        count++
    }

    /** The merged frame as NV21 (luma, then interleaved V,U), into [out] if given. */
    fun toNv21(out: ByteArray = ByteArray(w * h * 3 / 2)): ByteArray {
        require(out.size >= w * h * 3 / 2)
        val n = count
        val half = n / 2
        for (i in 0 until w * h) out[i] = ((sumY[i] + half) / n).toByte()
        val base = w * h
        for (i in 0 until w * h / 2) out[base + i] = ((sumVU[i] + half) / n).toByte()
        return out
    }

    companion object {
        /**
         * The ghost threshold for a frame aligned with mean difference [error]: about three times the noise,
         * so noise is averaged away but real movement is not.
         */
        fun thresholdFor(error: Double): Int = (error * 3).roundToInt().coerceIn(16, 80)
    }
}

/** Tripod and handheld settings for night mode. */
enum class NightPreset(
    val frames: Int,
    /** The longest exposure for each frame. */
    val maxFrameNs: Long,
    /** How far a frame may have moved, as a fraction of the width. */
    val maxShift: Double,
    /** Frames that match the reference worse than this (mean luma difference) are left out. */
    val maxError: Double,
) {
    HANDHELD(frames = 6, maxFrameNs = 66_000_000L, maxShift = 0.08, maxError = 28.0),
    TRIPOD(frames = 8, maxFrameNs = 500_000_000L, maxShift = 0.02, maxError = 22.0),
}

object NightExposure {
    /**
     * Exposure for each frame of the burst, from what auto exposure chose for the preview ([aeNs] at [aeIso]).
     * The brightness (time × ISO) stays the same. Handheld never uses a longer time than auto exposure did
     * (and at most [NightPreset.maxFrameNs]); tripod trades ISO for a longer time, up to its limit, to lower noise.
     */
    fun plan(aeNs: Long, aeIso: Int, isoMin: Int, isoMax: Int, nsMin: Long, nsMax: Long, preset: NightPreset): Pair<Long, Int> {
        val total = aeNs.toDouble() * aeIso
        val cap = min(preset.maxFrameNs, nsMax)
        val t = when (preset) {
            NightPreset.HANDHELD -> min(aeNs, cap)
            NightPreset.TRIPOD -> min(total / isoMin, max(cap.toDouble(), aeNs.toDouble())).toLong().coerceAtMost(nsMax)
        }.coerceIn(nsMin, nsMax)
        val iso = (total / t).roundToInt().coerceIn(isoMin, isoMax)
        return t to iso
    }
}
