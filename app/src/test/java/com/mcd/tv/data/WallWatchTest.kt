package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling apart why the Wall stopped: sleep, something on top, a crash, Android ending Jarvis. */
class WallWatchTest {
    private val h = 3_600_000L
    private val t0 = 1_760_000_000_000L
    private fun beat(pid: Int = 100, wall: Boolean = true, front: Boolean = true, at: Long = t0, key: Long = t0 - 2 * h) =
        WallWatch.Beat(at = at, elapsed = 50_000, pid = pid, wallUp = wall, inFront = front, screenOn = true, lastKeyAt = key)

    @Test fun beatRoundTrips() {
        val b = beat()
        assertEquals(b, WallWatch.decode(WallWatch.encode(b)))
        assertNull(WallWatch.decode(""))
        assertNull(WallWatch.decode("junk"))
    }

    @Test fun nothingToSayWithoutALostWall() {
        assertNull(WallWatch.diagnose(null, 200, t0, null, null, null))
        assertNull(WallWatch.diagnose(beat(wall = false), 200, t0, null, null, null)) // closed with BACK
        assertNull(WallWatch.diagnose(beat(pid = 200), 200, t0, null, null, null)) // same process: a rebuild
    }

    @Test fun killedInFrontForLowMemory() {
        val d = WallWatch.diagnose(beat(), 200, t0 + 5 * 60_000, null, WallWatch.exitReason(3), null)!!
        assertTrue(d, d.contains("Jarvis was in front and was ended by Android for low memory."))
        assertTrue(d, d.contains("Last check 5 min ago"))
        assertTrue(d, d.contains("No remote key for 2 h 0 min"))
    }

    @Test fun crashWins() {
        val d = WallWatch.diagnose(beat(), 200, t0, "OutOfMemoryError: Failed to allocate", WallWatch.exitReason(4), null)!!
        assertTrue(d, d.contains("Jarvis crashed: OutOfMemoryError"))
    }

    @Test fun sleepThenEnded() {
        val d = WallWatch.diagnose(beat(), 200, t0, null, null, "screen off")!!
        assertTrue(d, d.contains("The TV went to sleep first"))
    }

    @Test fun coveredThenEnded() {
        val d = WallWatch.diagnose(beat(), 200, t0, null, WallWatch.exitReason(3), "screen on")!!
        assertTrue(d, d.contains("Something covered Jarvis first"))
        assertTrue(d, d.contains("low memory"))
    }

    @Test fun noGoodbyeAndNoExitReason() {
        // Fire OS 7 (Android 9) has no exit reasons.
        val d = WallWatch.diagnose(beat(), 200, t0, null, null, null)!!
        assertTrue(d, d.contains("Android most likely ended it"))
    }

    @Test fun reopenOnlyAfterAnEndInFront() {
        assertTrue(WallWatch.reopenWall(beat(), 200, null))
        assertTrue(WallWatch.reopenWall(beat(), 200, ""))
        assertFalse(WallWatch.reopenWall(beat(), 200, "screen on")) // HOME or the screensaver first
        assertFalse(WallWatch.reopenWall(beat(), 200, "screen off")) // asleep first
        assertFalse(WallWatch.reopenWall(beat(front = false), 200, null))
        assertFalse(WallWatch.reopenWall(beat(wall = false), 200, null))
        assertFalse(WallWatch.reopenWall(beat(pid = 200), 200, null))
        assertFalse(WallWatch.reopenWall(null, 200, null))
    }

    @Test fun gapMeansAsleepOrFrozen() {
        assertNull(WallWatch.gapSeconds(0, 999_999, 60_000)) // no earlier tick
        assertNull(WallWatch.gapSeconds(100_000, 160_500, 60_000)) // on time
        assertNull(WallWatch.gapSeconds(100_000, 280_000, 60_000)) // 3 minutes: still within the margin
        assertEquals(3600L, WallWatch.gapSeconds(100_000, 3_700_000, 60_000))
        assertNull(WallWatch.gapSeconds(200_000, 100_000, 60_000)) // clock went back: ignore
    }

    @Test fun sleepWarningOnlyForAttentiveTimeout() {
        assertNull(WallWatch.sleepWarning(null))
        assertNull(WallWatch.sleepWarning(0))
        assertNull(WallWatch.sleepWarning(-1))
        val w = WallWatch.sleepWarning(4 * h)!!
        assertTrue(w, w.contains("after 4 h 0 min with no remote use"))
        assertFalse(w, w.contains("—"))
    }

    @Test fun leftFrontNotes() {
        assertTrue(WallWatch.leftFrontNote(true, 90 * 60_000).contains("screen on"))
        assertTrue(WallWatch.leftFrontNote(false, 2 * h).contains("went to sleep"))
        assertTrue(WallWatch.leftFrontNote(false, 2 * h).contains("2 h 0 min"))
    }

    @Test fun exitReasons() {
        assertEquals("was ended by Android for low memory", WallWatch.exitReason(3))
        assertEquals("crashed", WallWatch.exitReason(4))
        assertEquals("froze (not responding) and was closed", WallWatch.exitReason(6))
        assertEquals("ended for an unknown reason", WallWatch.exitReason(99))
    }
}
