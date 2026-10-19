package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallCycleTest {
    @Test fun defaultIsPhotosFirst() {
        val c = WallCycle.parse("")
        assertEquals(WallCycle.Cycle(30, 1), c)
        val calMinutes = (0 until 1440).count { WallCycle.showCalendar(c, it) }
        assertEquals(48, calMinutes) // 1 minute in 30: photos 97% of the day
    }

    @Test fun calendarAtEndOfEachGap() {
        val c = WallCycle.parse("30/1")
        assertFalse(WallCycle.showCalendar(c, 28))
        assertTrue(WallCycle.showCalendar(c, 29))
        assertFalse(WallCycle.showCalendar(c, 30))
        assertTrue(WallCycle.showCalendar(c, 59))
        val h = WallCycle.parse("60/5")
        assertTrue(WallCycle.showCalendar(h, 9 * 60 + 55))
        assertFalse(WallCycle.showCalendar(h, 9 * 60 + 54))
    }

    @Test fun neverMeansNever() {
        val c = WallCycle.parse("0/2")
        assertEquals("0/0", c.key)
        assertFalse((0 until 1440).any { WallCycle.showCalendar(c, it) })
    }

    @Test fun badValuesGiveDefault() {
        assertEquals("30/1", WallCycle.clean("7/1"))
        assertEquals("30/1", WallCycle.clean("junk"))
        assertEquals("15/1", WallCycle.clean("15/9"))
        assertEquals("10/5", WallCycle.clean("10/5"))
    }

    @Test fun nextSteps() {
        assertEquals("60/1", WallCycle.next(WallCycle.parse("30/1")).key)
        assertEquals("0/0", WallCycle.next(WallCycle.parse("60/1")).key)
        assertEquals("10/1", WallCycle.next(WallCycle.parse("0/0")).key)
        assertEquals("30/2", WallCycle.nextStays(WallCycle.parse("30/1")).key)
        assertEquals("30/1", WallCycle.nextStays(WallCycle.parse("30/5")).key)
    }

    @Test fun labels() {
        assertEquals("Calendar every 30 min for 1 min", WallCycle.label(WallCycle.parse("30/1")))
        assertEquals("Calendar every hour for 2 min", WallCycle.label(WallCycle.parse("60/2")))
        assertEquals("Calendar never shows on its own", WallCycle.label(WallCycle.parse("0/0")))
    }
}
