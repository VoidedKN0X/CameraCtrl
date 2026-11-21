package com.vknxworks.cameractrl.camera

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Exposure bracketing in Photo mode: how many photos, and how many stops apart. */
enum class Bracket(val shots: Int, val stepEv: Int) {
    OFF(1, 0),
    THREE_1(3, 1),
    THREE_2(3, 2),
    FIVE_1(5, 1);

    /** The EV offsets in the order they are taken: the normal exposure first, then darker and lighter in turn. */
    val evs: List<Int>
        get() = if (this == OFF) listOf(0) else buildList {
            add(0)
            for (k in 1..shots / 2) {
                add(-k * stepEv)
                add(k * stepEv)
            }
        }

    companion object {
        /** The longest exposure a bracketed shot gets by itself before ISO is raised instead (a quarter second). */
        const val MAX_HANDHELD_NS = 250_000_000L

        /**
         * Exposure time and ISO for each EV offset in [evs], starting from [baseNs] at [baseIso] (what auto
         * exposure chose, or the manual settings). The shutter speed changes first; ISO makes up what the shutter
         * can't (the lens's limits, or more than a quarter second when the base exposure was shorter). Offsets
         * the lens can't reach are as close as it allows.
         */
        fun plan(baseNs: Long, baseIso: Int, isoMin: Int, isoMax: Int, nsMin: Long, nsMax: Long, evs: List<Int>): List<Pair<Long, Int>> {
            val cap = minOf(nsMax, maxOf(baseNs, MAX_HANDHELD_NS))
            return evs.map { ev ->
                val factor = 2.0.pow(ev)
                val target = baseNs.toDouble() * baseIso * factor
                var ns = (baseNs * factor).roundToLong().coerceIn(nsMin, cap)
                val iso = (target / ns).roundToInt().coerceIn(isoMin, isoMax)
                // ISO hit its limit: let the shutter take the rest, within the lens's range.
                ns = (target / iso).roundToLong().coerceIn(nsMin, cap)
                ns to iso
            }
        }
    }
}
