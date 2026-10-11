package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekAccumulatorTest {
    private val dur = 45 * 60_000L

    @Test
    fun pressesAddUpFromTheFirstPosition() {
        val s = SeekAccumulator()
        assertEquals(70_000L, s.press(true, 0, 60_000L, dur))
        // Later positions are ignored while adding up (the player has not moved yet).
        assertEquals(80_000L, s.press(true, 0, 999_000L, dur))
        assertEquals(90_000L, s.press(true, 0, 0L, dur))
        assertEquals(80_000L, s.press(false, 0, 0L, dur))
        assertEquals(20_000L, s.delta())
        assertTrue(s.pending)
        assertEquals(80_000L, s.commit(dur))
        assertFalse(s.pending)
        assertNull(s.commit(dur))
    }

    @Test
    fun heldKeySpeedsUp() {
        val s = SeekAccumulator()
        assertEquals(10_000L, s.stepFor(0))
        assertEquals(10_000L, s.stepFor(19))
        assertEquals(30_000L, s.stepFor(20))
        s.press(true, 0, 0L, dur)
        assertEquals(40_000L, s.press(true, 25, 0L, dur))
    }

    @Test
    fun staysInsideTheVideo() {
        val s = SeekAccumulator()
        assertEquals(0L, s.press(false, 0, 5_000L, dur))
        assertEquals(0L, s.press(false, 0, 5_000L, dur))
        // One press forward after hitting the start moves forward at once.
        assertEquals(10_000L, s.press(true, 0, 5_000L, dur))
        s.cancel()
        assertEquals(dur, s.press(true, 0, dur - 3_000L, dur))
        assertEquals(dur - 10_000L, s.press(false, 0, 0L, dur))
    }

    @Test
    fun unknownLengthHasNoUpperLimit() {
        val s = SeekAccumulator()
        assertEquals(20_000L, s.press(true, 0, 10_000L, -1L))
        assertEquals(20_000L, s.commit(-1L))
    }

    @Test
    fun labelShowsTargetAndChange() {
        val s = SeekAccumulator()
        s.press(true, 0, 60_000L, dur)
        s.press(true, 0, 0L, dur)
        assertEquals("1:20 / 45:00  (+0:20)", s.label(dur))
        s.cancel()
        s.press(false, 0, 3_725_000L, -1L)
        assertEquals("1:01:55  (-0:10)", s.label(-1L))
        assertEquals("1:02:05", SeekAccumulator.clock(3_725_000L))
        assertEquals("0:00", SeekAccumulator.clock(-5L))
    }
}
