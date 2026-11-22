package com.vknxworks.cameractrl.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseUpTest {
    private val main = FocusLens("0/2", minFocus = 10f, calibrated = true, equivMm = 24)
    private val wide = FocusLens("0/3", minFocus = 40f, calibrated = true, equivMm = 14)
    private val tele = FocusLens("0/4", minFocus = 3f, calibrated = true, equivMm = 70)
    private val auto = FocusLens("0", minFocus = 10f, calibrated = true, equivMm = 24)

    // ---------- Target lens ----------

    @Test
    fun calibratedPicksTheLensThatFocusesClosest() {
        assertEquals(wide, CloseUp.closerLens(main, listOf(main, wide, tele)))
        assertEquals(wide, CloseUp.closerLens(tele, listOf(main, wide, tele)))
        assertNull(CloseUp.closerLens(wide, listOf(main, wide, tele)))
    }

    @Test
    fun calibratedNeedsAClearlyCloserLens() {
        assertNull(CloseUp.closerLens(main, listOf(FocusLens("x", 12f, true, 14))))
        assertNull(CloseUp.closerLens(FocusLens("f", 0f, true, 24), listOf(wide)))
    }

    @Test
    fun uncalibratedFallsBackToTheWidestAutofocusLens() {
        val u = { l: FocusLens -> l.copy(calibrated = false) }
        assertEquals(u(wide), CloseUp.closerLens(u(main), listOf(u(main), u(wide), u(tele))))
        // Only one side uncalibrated is enough to go by width.
        assertEquals(u(wide), CloseUp.closerLens(main, listOf(u(wide), tele)))
        assertEquals(wide, CloseUp.closerLens(u(main), listOf(wide)))
        // From the telephoto the widest lens wins, not the main one.
        assertEquals(u(wide), CloseUp.closerLens(u(tele), listOf(u(main), u(wide))))
    }

    @Test
    fun neverPicksFixedFocusOrNarrowerLenses() {
        val fixedWide = FocusLens("0/5", 0f, false, 14)
        assertNull(CloseUp.closerLens(main.copy(calibrated = false), listOf(fixedWide)))
        assertNull(CloseUp.closerLens(main.copy(calibrated = false), listOf(tele.copy(calibrated = false))))
        // Not clearly wider (within 15 %).
        assertNull(CloseUp.closerLens(main.copy(calibrated = false), listOf(FocusLens("y", 20f, false, 22))))
        // No focal length reported: nothing to compare.
        assertNull(CloseUp.closerLens(main.copy(calibrated = false, equivMm = 0), listOf(wide.copy(calibrated = false))))
    }

    @Test
    fun planSwitchesToAnOpenableLens() {
        assertEquals(CloseUpPlan.Switch(wide), CloseUp.plan(auto, listOf(main, wide, tele), emptySet(), 0.6f, 1f))
    }

    @Test
    fun planZoomsAutoOutWhenTheLensDoesNotOpenOrIsNotListed() {
        assertEquals(CloseUpPlan.Zoom(0.6f), CloseUp.plan(auto, listOf(main, wide, tele), setOf(wide.key), 0.6f, 1f))
        assertEquals(CloseUpPlan.Zoom(0.6f), CloseUp.plan(auto, emptyList(), emptySet(), 0.6f, 2f))
    }

    @Test
    fun planDoesNothingWithoutAWayCloser() {
        // Already at the widest zoom, no wider zoom, or a single lens.
        assertNull(CloseUp.plan(auto, emptyList(), emptySet(), 0.6f, 0.6f))
        assertNull(CloseUp.plan(auto, emptyList(), emptySet(), 1f, 1f))
        assertNull(CloseUp.plan(main, listOf(wide), setOf(wide.key), null, 1f))
        // The lenses are listed and none of them helps (the ultrawide has fixed focus): no zooming either.
        assertNull(CloseUp.plan(auto, listOf(main, FocusLens("0/3", 0f, false, 14)), emptySet(), 0.6f, 1f))
    }

    @Test
    fun backThresholds() {
        assertEquals(8f, CloseUp.backBelow(main, wide, 0), 0.001f)
        // Uncalibrated: a share of the close-up lens's own range.
        assertEquals(16f, CloseUp.backBelow(main, wide.copy(calibrated = false), 0), 0.001f)
        // Close-up lens unknown (AUTO zoomed out): the original lens's range.
        assertEquals(4f, CloseUp.backBelow(main, null, 0), 0.001f)
        // Each quick back-and-forth halves it, at most three times.
        assertEquals(4f, CloseUp.backBelow(main, wide, 1), 0.001f)
        assertEquals(1f, CloseUp.backBelow(main, wide, 5), 0.001f)
    }

    // ---------- Tracker ----------

    @Test
    fun tooCloseOnlyAfterHolding() {
        val t = CloseUpTracker(holdMs = 1000)
        assertFalse(t.tooClose(0, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(500, 10f, sharp = false, unfocused = false, failedScans = 0, minFocus = 10f))
        assertTrue(t.tooClose(1000, 9.2f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
    }

    @Test
    fun sharpOrFarResetsTheWait() {
        val t = CloseUpTracker(holdMs = 1000)
        assertFalse(t.tooClose(0, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(600, 10f, sharp = true, unfocused = false, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(1200, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(1800, 5f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(2900, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
    }

    @Test
    fun uncalibratedUnitsOnlyMatterRelativeToTheLensItself() {
        // A lens that reports focus in its own scale (here 0..1): at its own near end counts.
        val t = CloseUpTracker(holdMs = 1000)
        assertFalse(t.tooClose(0, 0.95f, sharp = false, unfocused = true, failedScans = 0, minFocus = 1f))
        assertTrue(t.tooClose(1000, 1f, sharp = false, unfocused = true, failedScans = 0, minFocus = 1f))
        assertFalse(CloseUpTracker(holdMs = 0).tooClose(0, 0.5f, sharp = false, unfocused = true, failedScans = 0, minFocus = 1f))
    }

    @Test
    fun withoutADistanceItWaitsLongerForFailedFocus() {
        val t = CloseUpTracker(holdMs = 1000, blindHoldMs = 3000)
        assertFalse(t.tooClose(0, -1f, sharp = false, unfocused = false, failedScans = 4, minFocus = 10f))
        assertFalse(t.tooClose(1500, -1f, sharp = false, unfocused = true, failedScans = 4, minFocus = 10f))
        // Held long enough, but still scanning and only one failed scan since it started: not yet.
        assertFalse(t.tooClose(3000, -1f, sharp = false, unfocused = false, failedScans = 5, minFocus = 10f))
        // A second failed scan.
        assertTrue(t.tooClose(3200, -1f, sharp = false, unfocused = false, failedScans = 6, minFocus = 10f))

        val u = CloseUpTracker(blindHoldMs = 3000)
        assertFalse(u.tooClose(0, -1f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertTrue(u.tooClose(3000, -1f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        // Fixed focus never.
        assertFalse(CloseUpTracker(blindHoldMs = 0).tooClose(0, -1f, sharp = false, unfocused = true, failedScans = 9, minFocus = 0f))
    }

    @Test
    fun switchingBetweenDistanceAndNoDistanceStartsOver() {
        val t = CloseUpTracker(holdMs = 1000, blindHoldMs = 3000)
        assertFalse(t.tooClose(0, -1f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertFalse(t.tooClose(2500, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
        assertTrue(t.tooClose(3500, 10f, sharp = false, unfocused = true, failedScans = 0, minFocus = 10f))
    }

    @Test
    fun backWhenSharpAndFarEnough() {
        val t = CloseUpTracker(backMs = 1500)
        assertFalse(t.farEnough(0, 5f, sharp = true, below = 8f))
        assertFalse(t.farEnough(1000, 5f, sharp = false, below = 8f))
        assertFalse(t.farEnough(1500, 5f, sharp = true, below = 8f))
        assertTrue(t.farEnough(3000, 5f, sharp = true, below = 8f))
        assertFalse(CloseUpTracker(backMs = 0).farEnough(0, 9f, sharp = true, below = 8f))
        assertFalse(CloseUpTracker(backMs = 0).farEnough(0, -1f, sharp = true, below = 8f))
    }

    @Test
    fun pauseEndsWhenTheSubjectMovesAway() {
        val t = CloseUpTracker(clearMs = 1500)
        assertFalse(t.movedAway(0, 10f, sharp = true, minFocus = 10f))
        assertFalse(t.movedAway(500, 5f, sharp = true, minFocus = 10f))
        assertTrue(t.movedAway(2000, 5f, sharp = true, minFocus = 10f))
        // No distance reported: sharp alone.
        val u = CloseUpTracker(clearMs = 1500)
        assertFalse(u.movedAway(0, -1f, sharp = true, minFocus = 10f))
        assertTrue(u.movedAway(1500, -1f, sharp = true, minFocus = 10f))
    }
}
