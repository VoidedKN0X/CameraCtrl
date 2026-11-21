package com.vknxworks.cameractrl.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class BracketTest {
    @Test
    fun evOrder() {
        assertEquals(listOf(0), Bracket.OFF.evs)
        assertEquals(listOf(0, -1, 1), Bracket.THREE_1.evs)
        assertEquals(listOf(0, -2, 2), Bracket.THREE_2.evs)
        assertEquals(listOf(0, -1, 1, -2, 2), Bracket.FIVE_1.evs)
    }

    @Test
    fun shutterChangesFirst() {
        val plan = Bracket.plan(10_000_000L, 100, 50, 3200, 100_000L, 1_000_000_000L, listOf(0, -1, 1))
        assertEquals(10_000_000L to 100, plan[0])
        assertEquals(5_000_000L to 100, plan[1])
        assertEquals(20_000_000L to 100, plan[2])
    }

    @Test
    fun isoTakesOverAboveAQuarterSecond() {
        // +2 EV from 1/10 s would be 0.4 s: the shutter stops at 1/4 s and ISO rises for the rest.
        val (ns, iso) = Bracket.plan(100_000_000L, 100, 50, 3200, 100_000L, 1_000_000_000L, listOf(2))[0]
        assertEquals(250_000_000L, ns)
        assertEquals(160, iso)
    }

    @Test
    fun shortestShutterThenIsoDown() {
        // -2 EV from the lens's shortest time: ISO goes down instead.
        val (ns, iso) = Bracket.plan(100_000L, 400, 50, 3200, 100_000L, 1_000_000_000L, listOf(-2))[0]
        assertEquals(100_000L, ns)
        assertEquals(100, iso)
    }

    @Test
    fun longBaseExposureIsKept() {
        // A tripod exposure of 2 s at -1 EV keeps the shutter as the variable.
        val (ns, iso) = Bracket.plan(2_000_000_000L, 100, 100, 3200, 100_000L, 4_000_000_000L, listOf(-1))[0]
        assertEquals(1_000_000_000L, ns)
        assertEquals(100, iso)
    }
}
