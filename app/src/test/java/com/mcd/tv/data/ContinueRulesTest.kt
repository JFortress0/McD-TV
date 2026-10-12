package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueRulesTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun inProgressStays() {
        assertTrue(ContinueRules.keep("movie", 0, 0, 30 * 60_000L, 0.4f, now, now))
        assertTrue(ContinueRules.keep("tv", 1, 1, 5 * 60_000L, 0.3f, now, now))
    }

    @Test
    fun finishedEpisodeShowsTheNextOne() {
        assertTrue(ContinueRules.keep("tv", 1, 2, 21 * 60_000L, 0.99f, now - day, now))
        assertEquals(1 to 3, ContinueRules.playEpisode(1, 2, 0.99f))
        assertEquals(1 to 2, ContinueRules.playEpisode(1, 2, 0.5f))
    }

    @Test
    fun justStartedLaterEpisodeStays() {
        assertTrue(ContinueRules.keep("tv", 1, 3, 20_000L, 0.02f, now, now))
        assertTrue(ContinueRules.keep("tv", 2, 1, 0L, 0f, now, now))
        assertFalse("a first episode barely sampled", ContinueRules.keep("tv", 1, 1, 20_000L, 0.02f, now, now))
    }

    @Test
    fun finishedMoviesAndOldShowsLeave() {
        assertFalse(ContinueRules.keep("movie", 0, 0, 100 * 60_000L, 0.99f, now, now))
        assertFalse(ContinueRules.keep("tv", 1, 4, 21 * 60_000L, 0.99f, now - 61 * day, now))
        assertTrue("in progress never times out", ContinueRules.keep("tv", 1, 4, 5 * 60_000L, 0.3f, now - 200 * day, now))
    }
}
