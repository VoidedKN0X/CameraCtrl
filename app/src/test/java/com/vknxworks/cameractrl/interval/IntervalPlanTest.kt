package com.vknxworks.cameractrl.interval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalPlanTest {
    private val start = 1_000_000L

    /** Simulates a run where every shot takes [shotMs]; returns the times the shots were taken (relative to start). */
    private fun simulate(plan: IntervalPlan, shotMs: Long, limit: Int = 10_000): List<Long> {
        val taken = ArrayList<Long>()
        var slot = 0L
        var now = start
        while (!plan.finished(slot, taken.size) && taken.size < limit) {
            now = maxOf(now, plan.slotTime(start, slot))
            taken += now - start
            now += shotMs
            slot = plan.nextSlot(start, slot, now)
        }
        return taken
    }

    @Test
    fun fastShotsKeepTheInterval() {
        val plan = IntervalPlan(5_000, StopRule.AfterShots(4))
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), simulate(plan, shotMs = 400))
    }

    @Test
    fun slowShotsSkipMissedSlotsInsteadOfRushing() {
        // 1 s interval, 1.6 s per shot: slot 1 (1 s) is over by 0.6 s (> 0.25 s grace), so it's skipped.
        val plan = IntervalPlan(1_000, StopRule.AfterShots(3))
        assertEquals(listOf(0L, 2_000L, 4_000L), simulate(plan, shotMs = 1_600))
    }

    @Test
    fun aSlightlyLateSlotIsStillShot() {
        val plan = IntervalPlan(1_000, StopRule.AfterShots(3))
        // 1.1 s per shot: each slot is 0.1 s, then 0.2 s late, within the 0.25 s grace, so it's shot right away.
        assertEquals(listOf(0L, 1_100L, 2_200L), simulate(plan, shotMs = 1_100))
    }

    @Test
    fun nextSlotAfterTheFirstShot() {
        val plan = IntervalPlan(10_000, StopRule.Never)
        assertEquals(0L, plan.nextSlot(start, -1, start))
        assertEquals(1L, plan.nextSlot(start, 0, start + 300))
        // The phone slept for 35 s: slots 1..3 are over, slot 4 (40 s) is next.
        assertEquals(4L, plan.nextSlot(start, 0, start + 35_000))
        // Exactly on a slot, or within its grace: that slot.
        assertEquals(3L, plan.nextSlot(start, 0, start + 30_000))
        assertEquals(3L, plan.nextSlot(start, 0, start + 32_500))
        assertEquals(4L, plan.nextSlot(start, 0, start + 32_501))
    }

    @Test
    fun durationRunsEndOnTime() {
        val plan = IntervalPlan(5_000, StopRule.AfterTime(60_000))
        assertEquals(12, plan.expectedShots)
        assertEquals(55_000L, plan.expectedDurationMs)
        val shots = simulate(plan, shotMs = 500)
        assertEquals(12, shots.size)
        assertEquals(55_000L, shots.last())
        // Skipped slots make fewer shots, but the run still ends within the time.
        val slow = simulate(plan, shotMs = 7_000)
        assertTrue(slow.size < 12)
        assertTrue(slow.last() < 60_000L)
    }

    @Test
    fun countRunsTakeEveryShotEvenWhenSlow() {
        val plan = IntervalPlan(1_000, StopRule.AfterShots(10))
        assertEquals(10, simulate(plan, shotMs = 2_500).size)
    }

    @Test
    fun aDurationShorterThanTheIntervalStillTakesOneShot() {
        val plan = IntervalPlan(60_000, StopRule.AfterTime(10_000))
        assertEquals(1, plan.expectedShots)
        assertEquals(listOf(0L), simulate(plan, shotMs = 500))
    }

    @Test
    fun neverStopsOnItsOwn() {
        val plan = IntervalPlan(2_000, StopRule.Never)
        assertNull(plan.expectedShots)
        assertNull(plan.expectedDurationMs)
        assertFalse(plan.finished(1_000_000, 1_000_000))
        assertEquals(500, simulate(plan, shotMs = 100, limit = 500).size)
    }

    @Test
    fun videoLength() {
        assertEquals(10_000L, IntervalPlan.videoMillis(300, 30))
        assertEquals(33L, IntervalPlan.videoMillis(1, 30))
    }

    @Test
    fun stopRulesRoundTrip() {
        for (r in listOf(StopRule.AfterShots(300), StopRule.AfterTime(3_600_000), StopRule.Never)) {
            assertEquals(r, StopRule.decode(r.encode()))
        }
        assertNull(StopRule.decode("shots:0"))
        assertNull(StopRule.decode("time:-5"))
        assertNull(StopRule.decode("bogus"))
        assertNull(StopRule.decode(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroIntervalIsRefused() {
        IntervalPlan(0, StopRule.Never)
    }
}
