package com.vknxworks.cameractrl.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseUpTest {
    private val main = FocusLens("0/2", minFocus = 10f, calibrated = true)
    private val wide = FocusLens("0/3", minFocus = 40f, calibrated = true)
    private val tele = FocusLens("0/4", minFocus = 3f, calibrated = true)

    @Test
    fun picksTheLensThatFocusesClosest() {
        assertEquals(wide, CloseUp.closerLens(main, listOf(main, wide, tele)))
        assertEquals(wide, CloseUp.closerLens(tele, listOf(main, wide, tele)))
        assertNull(CloseUp.closerLens(wide, listOf(main, wide, tele)))
    }

    @Test
    fun neverComparesUncalibratedLenses() {
        assertNull(CloseUp.closerLens(main.copy(calibrated = false), listOf(wide)))
        assertNull(CloseUp.closerLens(main, listOf(wide.copy(calibrated = false))))
    }

    @Test
    fun needsAClearlyCloserLens() {
        assertNull(CloseUp.closerLens(main, listOf(FocusLens("x", 12f, true))))
        assertNull(CloseUp.closerLens(FocusLens("f", 0f, true), listOf(wide)))
    }

    @Test
    fun tooCloseOnlyAfterHolding() {
        val t = CloseUpTracker(holdMs = 1000)
        assertFalse(t.tooClose(0, 10f, sharp = false, minFocus = 10f))
        assertFalse(t.tooClose(500, 10f, sharp = false, minFocus = 10f))
        assertTrue(t.tooClose(1000, 9.8f, sharp = false, minFocus = 10f))
    }

    @Test
    fun sharpOrFarResetsTheWait() {
        val t = CloseUpTracker(holdMs = 1000)
        assertFalse(t.tooClose(0, 10f, sharp = false, minFocus = 10f))
        assertFalse(t.tooClose(600, 10f, sharp = true, minFocus = 10f))
        assertFalse(t.tooClose(1200, 10f, sharp = false, minFocus = 10f))
        assertFalse(t.tooClose(1800, 5f, sharp = false, minFocus = 10f))
        assertFalse(t.tooClose(2000, -1f, sharp = false, minFocus = 10f))
    }

    @Test
    fun backWhenSharpAndFarEnough() {
        val t = CloseUpTracker(backMs = 1500)
        assertFalse(t.farEnough(0, 5f, sharp = true, minFocus = 10f))
        assertTrue(t.farEnough(1500, 5f, sharp = true, minFocus = 10f))
        assertFalse(CloseUpTracker(backMs = 0).farEnough(0, 9f, sharp = true, minFocus = 10f))
    }
}
