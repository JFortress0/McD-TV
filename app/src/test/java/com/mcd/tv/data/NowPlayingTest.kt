package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private val t0 = 1_792_353_600_000L
    private val bluey = NowPlaying.State("tv", 82728, "Bluey", 1, 1, true, 10_000L, 420_000L, t0)

    @Test
    fun jsonRoundTrip() {
        val j = NowPlaying.json(bluey)!!
        assertEquals("tv", j.getString("media"))
        assertEquals(82728, j.getInt("id"))
        assertEquals("Bluey", j.getString("name"))
        assertEquals(1, j.getInt("season"))
        assertTrue(j.getBoolean("playing"))
        assertEquals(10_000L, j.getLong("position_ms"))
        assertEquals(420_000L, j.getLong("duration_ms"))
        assertNull(NowPlaying.json(null))
    }

    @Test
    fun sendsOnStartStopPauseAndTitle() {
        assertFalse(NowPlaying.sendDue(null, 0L, null, t0))
        assertTrue(NowPlaying.sendDue(null, 0L, bluey, t0)) // started
        assertTrue(NowPlaying.sendDue(bluey, t0, null, t0 + 1_000L)) // stopped
        assertTrue(NowPlaying.sendDue(bluey, t0, bluey.copy(playing = false, ts = t0 + 1_000L), t0 + 1_000L)) // paused
        assertTrue(NowPlaying.sendDue(bluey, t0, bluey.copy(episode = 2, ts = t0 + 1_000L), t0 + 1_000L)) // next episode
    }

    @Test
    fun positionOnlyOncePerMinute() {
        val later = bluey.copy(positionMs = 25_000L, ts = t0 + 15_000L)
        assertFalse(NowPlaying.sendDue(bluey, t0, later, t0 + 15_000L))
        assertFalse(NowPlaying.sendDue(bluey, t0, later, t0 + 59_999L))
        assertTrue(NowPlaying.sendDue(bluey, t0, later, t0 + 60_000L))
        assertTrue(NowPlaying.sendDue(bluey, t0 + 5_000L, later, t0)) // clock went back: send
    }

    @Test
    fun currentAndListener() {
        var heard: NowPlaying.State? = bluey
        NowPlaying.onChange { heard = it }
        NowPlaying.set(bluey)
        assertEquals(bluey, NowPlaying.current)
        NowPlaying.clear()
        assertNull(NowPlaying.current)
        assertNull(heard)
        NowPlaying.onChange(null)
    }
}
