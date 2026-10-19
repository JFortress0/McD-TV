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
    fun changesThatPhonesMustHear() {
        assertFalse(NowPlaying.changed(null, null))
        assertTrue(NowPlaying.changed(null, bluey)) // started
        assertTrue(NowPlaying.changed(bluey, null)) // stopped
        assertTrue(NowPlaying.changed(bluey, bluey.copy(playing = false))) // paused
        assertTrue(NowPlaying.changed(bluey, bluey.copy(episode = 2))) // next episode
        assertFalse(NowPlaying.changed(bluey, bluey.copy(positionMs = 25_000L, ts = t0 + 15_000L))) // position only
    }

    @Test
    fun atMostOncePerTenSeconds() {
        assertEquals(0L, NowPlaying.waitMs(0L, t0)) // never sent
        assertEquals(10_000L, NowPlaying.waitMs(t0, t0))
        assertEquals(4_000L, NowPlaying.waitMs(t0, t0 + 6_000L))
        assertEquals(0L, NowPlaying.waitMs(t0, t0 + 10_000L))
        assertEquals(0L, NowPlaying.waitMs(t0 + 5_000L, t0)) // clock went back
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
