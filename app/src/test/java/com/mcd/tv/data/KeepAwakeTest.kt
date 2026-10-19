package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakeTest {
    @Test
    fun oneScreenClosingKeepsTheOtherHold() {
        val s = HoldSet()
        val wall = s.hold("wall")
        val setup = s.hold("setup")
        s.release(setup) // Phone & Computer Setup closes: the Wall must stay held
        assertTrue(s.held)
        assertEquals(listOf("wall"), s.holders())
        s.release(wall)
        assertFalse(s.held)
    }

    @Test
    fun releaseTwiceOrUnknownIsHarmless() {
        val s = HoldSet()
        val a = s.hold("wall")
        val b = s.hold("wall") // a second Wall on the stack
        s.release(a)
        s.release(a)
        s.release(999)
        assertTrue(s.held)
        s.release(b)
        assertFalse(s.held)
    }

    @Test
    fun wallComesBackAfterRecreate() {
        // Re-created while the Wall was up: back to the Wall, even with "Open in Wall on start" off and the intro on.
        assertEquals(StartScreen.Start.Wall, StartScreen.pick(hasDeepLink = false, wallWasUp = true, wallStartPref = false, playIntro = true, skipProfiles = false))
    }

    @Test
    fun wallWinsOverTheOldLaunchIntent() {
        // Opened with "--es screen settings", then the Wall from Settings, then re-created: the Wall, not Settings.
        assertEquals(StartScreen.Start.Wall, StartScreen.pick(hasDeepLink = true, wallWasUp = true, wallStartPref = false, playIntro = false, skipProfiles = true))
    }

    @Test
    fun startRulesUnchangedOtherwise() {
        assertEquals(StartScreen.Start.DeepLink, StartScreen.pick(true, false, true, true, false))
        assertEquals(StartScreen.Start.Wall, StartScreen.pick(false, false, true, true, false))
        assertEquals(StartScreen.Start.Intro, StartScreen.pick(false, false, false, true, false))
        assertEquals(StartScreen.Start.Profiles, StartScreen.pick(false, false, false, false, false))
        assertEquals(StartScreen.Start.Home, StartScreen.pick(false, false, false, false, true))
    }
}
