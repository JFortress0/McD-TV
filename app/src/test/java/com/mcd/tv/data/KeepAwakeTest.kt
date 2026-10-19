package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakeTest {
    @Test fun offUntilHeld() {
        val k = KeepAwake()
        assertFalse(k.on)
        k.set("wall", true)
        assertTrue(k.on)
        assertTrue(k.has("wall"))
        k.set("wall", false)
        assertFalse(k.on)
    }

    @Test fun oneReasonClosingKeepsTheOther() {
        // The old bug: Phone Setup closing cleared the Wall's keep-screen-on (one shared flag).
        val k = KeepAwake()
        k.set("wall", true)
        k.set("phone_setup", true)
        k.set("phone_setup", false)
        assertTrue(k.on)
        k.set("phone_setup", false) // a second release is harmless
        assertTrue(k.on)
    }

    @Test fun listenerOnlyOnChange() {
        val k = KeepAwake()
        val seen = mutableListOf<Boolean>()
        k.listener = { seen += it }
        k.set("wall", true)
        k.set("wall", true)
        k.set("phone_setup", true)
        k.set("wall", false)
        k.set("phone_setup", false)
        k.set("phone_setup", false)
        assertEquals(listOf(true, false), seen)
    }
}
