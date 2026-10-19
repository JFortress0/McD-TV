package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakeTest {
    @Test fun offUntilHeld() {
        val k = KeepAwake()
        assertFalse(k.on)
        val h = k.acquire("wall")
        assertTrue(k.on)
        assertTrue(k.has("wall"))
        h.release()
        assertFalse(k.on)
        assertFalse(k.has("wall"))
    }

    @Test fun oneReasonClosingKeepsTheOther() {
        // The old bug: Phone Setup closing cleared the Wall's keep-screen-on (one shared flag).
        val k = KeepAwake()
        k.acquire("wall")
        val phone = k.acquire("phone_setup")
        phone.release()
        assertTrue(k.on)
        phone.release() // a second release is harmless
        assertTrue(k.on)
    }

    @Test fun staleReleaseOfSameReasonKeepsTheNewHold() {
        // A rebuilt activity holds "wall" before the old screen lets its own "wall" go.
        val k = KeepAwake()
        val old = k.acquire("wall")
        k.acquire("wall")
        old.release()
        assertTrue(k.has("wall"))
    }

    @Test fun listenerOnlyOnChange() {
        val k = KeepAwake()
        val seen = mutableListOf<Boolean>()
        k.listener = { seen += it }
        val a = k.acquire("wall")
        val b = k.acquire("phone_setup")
        a.release()
        b.release()
        b.release()
        assertEquals(listOf(true, false), seen)
    }
}
