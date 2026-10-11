package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillWatchingTest {
    @Test
    fun asksAfterThreeAutomaticEpisodes() {
        val c = AutoPlayCount(3)
        assertFalse(c.shouldAsk())
        c.autoAdvanced()
        c.autoAdvanced()
        assertFalse(c.shouldAsk())
        c.autoAdvanced()
        assertTrue(c.shouldAsk())
        assertEquals(3, c.count)
    }

    @Test
    fun keyPressStartsOver() {
        val c = AutoPlayCount(3)
        repeat(2) { c.autoAdvanced() }
        c.keyPressed()
        assertEquals(0, c.count)
        repeat(2) { c.autoAdvanced() }
        assertFalse(c.shouldAsk())
    }

    @Test
    fun appWideCounterUsesThree() {
        assertEquals(3, StillWatching.counter.limit)
    }
}
