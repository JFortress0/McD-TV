package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wall's keep-screen-on across the MainActivity lifecycle paths (see KeepAwakeGuard). */
class KeepAwakeGuardTest {
    private class FakeWindow : KeepOnWindow {
        override var keepOn = false
    }

    private val holds = KeepAwake()
    private val logs = mutableListOf<String>()
    private val guard = KeepAwakeGuard(holds) { logs += it }

    @Test fun wallOpenedAfterCreateTurnsFlagOn() {
        val w = FakeWindow()
        guard.attach(w)
        assertFalse(w.keepOn)
        val hold = holds.acquire(ScreenAwake.WALL)
        assertTrue(w.keepOn)
        hold.release()
        assertFalse(w.keepOn)
    }

    @Test fun holdBeforeCreateIsAppliedOnAttach() {
        holds.acquire(ScreenAwake.WALL)
        val w = FakeWindow()
        guard.attach(w)
        assertTrue(w.keepOn)
    }

    @Test fun rebuildMovesToTheNewWindow() {
        // Config change: old onStop (changing), old onDestroy, old composition lets go, new onCreate, new hold.
        val a = FakeWindow()
        guard.attach(a)
        val oldHold = holds.acquire(ScreenAwake.WALL)
        guard.stopped(changingConfig = true)
        guard.detach(a)
        oldHold.release()
        val b = FakeWindow()
        guard.attach(b)
        holds.acquire(ScreenAwake.WALL)
        guard.resumed()
        assertTrue(b.keepOn)
        assertTrue(logs.none { "left the front" in it }) // a rebuild is not a lapse
    }

    @Test fun lateDetachOrReleaseFromOldActivityChangesNothing() {
        val a = FakeWindow()
        guard.attach(a)
        val oldHold = holds.acquire(ScreenAwake.WALL)
        val b = FakeWindow()
        guard.attach(b) // new activity first
        holds.acquire(ScreenAwake.WALL)
        guard.detach(a) // old activity's onDestroy arrives late
        oldHold.release() // and its composition lets go late
        assertTrue(b.keepOn)
        assertFalse(guard.check())
    }

    @Test fun selfCheckPutsALapsedFlagBackAndLogsIt() {
        val w = FakeWindow()
        guard.attach(w)
        holds.acquire(ScreenAwake.WALL)
        assertFalse(guard.check())
        w.keepOn = false // something cleared the window flag
        assertTrue(guard.check())
        assertTrue(w.keepOn)
        assertEquals(1, guard.lapses)
        assertTrue(logs.any { "lapsed" in it && "wall" in it })
        assertFalse(guard.check())
        assertEquals(1, guard.lapses)
    }

    @Test fun selfCheckIsQuietWithNoHold() {
        val w = FakeWindow()
        guard.attach(w)
        assertFalse(guard.check())
        assertEquals(0, guard.lapses)
    }

    @Test fun resumeReassertsTheFlag() {
        val w = FakeWindow()
        guard.attach(w)
        holds.acquire(ScreenAwake.WALL)
        w.keepOn = false
        guard.resumed()
        assertTrue(w.keepOn)
    }

    @Test fun leavingTheFrontWithTheWallUpIsLogged() {
        guard.attach(FakeWindow())
        guard.stopped(changingConfig = false)
        assertTrue(logs.none { "left the front" in it }) // no Wall, nothing to say
        holds.acquire(ScreenAwake.WALL)
        guard.stopped(changingConfig = false)
        assertTrue(logs.any { "left the front" in it })
    }

    @Test fun checkWithNoWindowDoesNothing() {
        holds.acquire(ScreenAwake.WALL)
        assertFalse(guard.check())
    }
}
