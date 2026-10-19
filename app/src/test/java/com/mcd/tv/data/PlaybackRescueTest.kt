package com.mcd.tv.data

import com.mcd.tv.data.PlaybackRescue.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRescueTest {
    /** 34:50, where The Irishman was left on the web app. */
    private val resume = (34 * 60 + 50) * 1000L

    @Test
    fun webPositionIsMillisecondsAndResumesAsIs() {
        // The web app sends position_ms / duration_ms; the cloud copy is ms too. 34:50 of 3:29:00 stays 34:50.
        assertEquals(resume, PlaybackRescue.resumeTarget(resume, 209 * 60_000L))
        assertEquals(resume, PlaybackRescue.resumeTarget(resume, 0L)) // length not known yet
        assertEquals(0L, PlaybackRescue.resumeTarget(0L, 209 * 60_000L))
    }

    @Test
    fun savedSpotPastTheEndOfThisSourceStartsAtZero() {
        assertEquals(0L, PlaybackRescue.resumeTarget(resume, resume)) // a shorter file
        assertEquals(0L, PlaybackRescue.resumeTarget(resume, resume + 5_000L))
        assertEquals(resume, PlaybackRescue.resumeTarget(resume, resume + 60_000L))
    }

    @Test
    fun failedResumeWithAnotherSourceSwitchesAtTheSameSpot() {
        val r = PlaybackRescue(resume) { true }
        assertEquals(Action.RECONNECT, r.onFailure(resume, true).action)
        assertEquals(Action.RECONNECT, r.onFailure(resume, true).action)
        val step = r.onFailure(resume, true)
        assertEquals(Action.NEXT_SOURCE, step.action)
        assertTrue(step.note!!.contains("at 34:50"))
        // A failed jump is not proof the file is cut off: the player does not flag it.
        assertTrue(r.failedAtResume)
    }

    @Test
    fun failedResumeReconnectsTwiceThenPlaysFromTheStart() {
        val r = PlaybackRescue(resume) { false }
        assertEquals(Action.RECONNECT, r.onFailure(resume, true).action)
        assertEquals(Action.RECONNECT, r.onFailure(resume + 1_000L, true).action)
        val step = r.onFailure(resume, true)
        assertEquals(Action.FROM_START, step.action)
        assertEquals(0L, step.seekToMs)
        assertTrue(step.note!!.contains("34:50"))
        assertFalse(step.note!!.contains("—")) // no em dashes in user text
        assertFalse(r.resumePending)
        // The saved 34:50 is kept while the viewer watches the opening again.
        assertEquals(resume, r.keepSavedBelow)
        r.onPlaying(10 * 60_000L)
        assertEquals(resume, r.keepSavedBelow)
        r.onPlaying(resume + 1_000L)
        assertEquals(0L, r.keepSavedBelow)
    }

    @Test
    fun decoderErrorAtResumeSkipsTheReconnects() {
        val r = PlaybackRescue(resume) { false }
        assertEquals(Action.FROM_START, r.onFailure(resume, false).action)
    }

    @Test
    fun resumeThatPlayedIsNotRetriedFromTheStart() {
        val r = PlaybackRescue(resume) { true }
        r.onPlaying(resume + 6_000L)
        assertFalse(r.resumePending)
        r.onFailure(resume + 7_000L, true)
        r.onFailure(resume + 7_000L, true)
        val step = r.onFailure(resume + 7_000L, true)
        assertEquals(Action.NEXT_SOURCE, step.action)
        assertTrue(step.note!!.contains("34:57"))
    }

    @Test
    fun sourceThatDiesAtOneSpotNeverLoopsForever() {
        // No other source: reconnect, reconnect, skip past once, then give up with the time.
        val r = PlaybackRescue(0L) { false }
        val spot = resume
        assertEquals(Action.RECONNECT, r.onFailure(spot, true).action)
        assertEquals(Action.RECONNECT, r.onFailure(spot, true).action)
        val skip = r.onFailure(spot, true)
        assertEquals(Action.SKIP_AHEAD, skip.action)
        assertEquals(spot + PlaybackRescue.SKIP_MS, skip.seekToMs)
        // The skip landed inside the same bad stretch: one more failure there gives up.
        val give = r.onFailure(spot + 10_000L, true)
        assertEquals(Action.GIVE_UP, give.action)
        assertTrue(give.note!!.contains("Try another source"))
    }

    @Test
    fun aNewSpotStartsOver() {
        val r = PlaybackRescue(0L) { false }
        repeat(3) { r.onFailure(resume, true) }
        // Played on fine, then a hiccup an hour later: just a reconnect again.
        r.onPlaying(resume + 60_000L)
        assertEquals(Action.RECONNECT, r.onFailure(resume + 3_600_000L, true).action)
    }

    @Test
    fun noResumePointMeansNoStartOver() {
        val r = PlaybackRescue(0L) { true }
        r.onFailure(0L, true)
        r.onFailure(0L, true)
        assertEquals(Action.NEXT_SOURCE, r.onFailure(0L, true).action)
    }
}
