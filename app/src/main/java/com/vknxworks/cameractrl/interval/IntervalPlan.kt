package com.vknxworks.cameractrl.interval

import kotlin.math.max

/** When an interval run ends. */
sealed interface StopRule {
    /** After this many photos have been taken. Slots that had to be skipped don't count. */
    data class AfterShots(val shots: Int) : StopRule

    /** After this much time: shots are planned every interval while less than [millis] has passed since the start. */
    data class AfterTime(val millis: Long) : StopRule

    /** Until the run is stopped by hand. */
    data object Never : StopRule

    /** How it's stored in the settings ("shots:100", "time:1800000", "never"). */
    fun encode(): String = when (this) {
        is AfterShots -> "shots:$shots"
        is AfterTime -> "time:$millis"
        Never -> "never"
    }

    companion object {
        fun decode(s: String?): StopRule? {
            if (s == null) return null
            if (s == "never") return Never
            val (kind, value) = s.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return when (kind) {
                "shots" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { AfterShots(it) }
                "time" -> value.toLongOrNull()?.takeIf { it > 0 }?.let { AfterTime(it) }
                else -> null
            }
        }
    }
}

/**
 * The schedule of an interval run: slot k is due [intervalMs] × k after the start. Times are on one
 * monotonic clock (elapsedRealtime), so a changed wall clock doesn't move the shots.
 *
 * A shot can take longer than the interval (slow saving, a long exposure, the phone asleep). Then the
 * missed slots are skipped instead of being shot in a hurry, so the run keeps its rhythm.
 */
class IntervalPlan(val intervalMs: Long, val stop: StopRule) {
    init {
        require(intervalMs > 0)
    }

    /** A slot that is late by at most this much is still shot. */
    val grace: Long get() = intervalMs / 4

    fun slotTime(start: Long, slot: Long): Long = start + slot * intervalMs

    /**
     * The slot to wait for next, at [now], after slot [last] was shot (-1 before the first shot): the first
     * slot after [last] that isn't over yet. A slot is over once it is more than [grace] late.
     */
    fun nextSlot(start: Long, last: Long, now: Long): Long {
        val elapsed = now - start - grace
        val firstNotOver = if (elapsed <= 0) 0L else (elapsed + intervalMs - 1) / intervalMs
        return max(last + 1, firstNotOver)
    }

    /** Whether the run is over before shooting [slot], with [shots] photos taken so far. */
    fun finished(slot: Long, shots: Int): Boolean = when (stop) {
        is StopRule.AfterShots -> shots >= stop.shots
        is StopRule.AfterTime -> slot * intervalMs >= stop.millis
        StopRule.Never -> false
    }

    /** How many photos the run takes if no slot is skipped; null when it runs until stopped. */
    val expectedShots: Int?
        get() = when (stop) {
            is StopRule.AfterShots -> stop.shots
            is StopRule.AfterTime -> ((stop.millis + intervalMs - 1) / intervalMs).toInt().coerceAtLeast(1)
            StopRule.Never -> null
        }

    /** How long the run lasts if no slot is skipped (from the first shot to the last); null when it runs until stopped. */
    val expectedDurationMs: Long?
        get() = expectedShots?.let { (it - 1).coerceAtLeast(0) * intervalMs }

    companion object {
        /** The length of a timelapse video made of [shots] frames at [fps] frames per second. */
        fun videoMillis(shots: Int, fps: Int): Long = shots.toLong() * 1000 / fps
    }
}
