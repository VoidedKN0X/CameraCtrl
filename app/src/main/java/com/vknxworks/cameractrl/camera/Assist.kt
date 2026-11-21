package com.vknxworks.cameractrl.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Viewfinder aids worked out from a small copy of the preview: histogram and focus peaking. Pure Kotlin. */
object FrameAssist {
    /** Brightness (BT.601 luma, 0..255) of an ARGB pixel. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /** A brightness histogram of [argb] with [bins] bins (darkest first). */
    fun histogram(argb: IntArray, bins: Int = 64): IntArray {
        val out = IntArray(bins)
        for (p in argb) out[luma(p) * bins / 256]++
        return out
    }

    /**
     * Focus peaking: marks the sharpest edges of a [w] × [h] frame with [color] in [out] (other pixels become 0,
     * transparent). An edge is where brightness changes most between neighbours; only the strongest few percent
     * of the frame count, and never weak ones, so a blurred frame shows little or nothing. Returns how many
     * pixels were marked.
     */
    fun peaking(argb: IntArray, w: Int, h: Int, out: IntArray, color: Int): Int {
        out.fill(0)
        if (w < 3 || h < 3) return 0
        val l = IntArray(w * h) { luma(argb[it]) }
        val mag = IntArray(w * h)
        val counts = IntArray(511)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val m = abs(l[i + 1] - l[i - 1]) + abs(l[i + w] - l[i - w])
                mag[i] = m
                counts[m]++
            }
        }
        // The magnitude above which the top 4 % of the frame's pixels lie, and at least MIN_EDGE.
        val total = (w - 2) * (h - 2)
        var left = total * 4 / 100
        var threshold = 510
        while (threshold > 0 && left > 0) {
            left -= counts[threshold]
            threshold--
        }
        threshold = maxOf(threshold + 1, MIN_EDGE)
        var marked = 0
        for (i in mag.indices) {
            if (mag[i] >= threshold) {
                out[i] = color
                marked++
            }
        }
        return marked
    }

    /** Edges weaker than this (sum of the horizontal and vertical brightness steps) are never marked. */
    const val MIN_EDGE = 48
}

/** The level: how far the phone is tilted from the nearest upright or sideways position. */
object Level {
    /**
     * The tilt in degrees from gravity [x] and [y] in the phone's screen axes (as the accelerometer reports
     * them), measured from the nearest of upright, sideways or upside down. It is negative when the phone is
     * turned clockwise, so a line drawn on the screen rotated by this many degrees (clockwise positive, as
     * Compose rotates) stays level with the world. Null when the phone lies nearly flat
     * ([z] carries almost all of gravity), where a horizon means nothing.
     */
    fun tilt(x: Float, y: Float, z: Float): Float? {
        val g = sqrt(x * x + y * y + z * z)
        if (g <= 0f || sqrt(x * x + y * y) < g * 0.35f) return null
        val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        var d = angle % 90f
        if (d > 45f) d -= 90f
        if (d < -45f) d += 90f
        return d
    }
}
