package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvIdentityTest {
    @Test
    fun knownModelWins() {
        // J's Office TV (real model, Oct 18 2026). The maker rule would say the same, but the model is the record.
        assertEquals("Fire TV", TvIdentity.platform("Amazon", "AFTHY7ABBA"))
        assertEquals("Fire TV", TvIdentity.platform("", " afthy7abba "))
        assertTrue(TvIdentity.modelKnown("AFTHY7ABBA"))
        assertFalse(TvIdentity.modelKnown("AFTMM"))
    }

    @Test
    fun makerRule() {
        assertEquals("Fire TV", TvIdentity.platform("Amazon", "AFTSSS"))
        assertEquals("Google TV", TvIdentity.platform("Google", "Chromecast"))
        assertEquals("Google TV", TvIdentity.platform("Vizio", "Chromecast HD"))
        assertEquals("Android TV", TvIdentity.platform("Hisense", "HU55A6"))
        assertEquals("TV", TvIdentity.platform("", ""))
    }

    @Test
    fun labels() {
        assertEquals("Living Room TV", TvIdentity.cleanLabel("  Living   Room\nTV "))
        assertEquals(40, TvIdentity.cleanLabel("x".repeat(80)).length)
        assertEquals("", TvIdentity.cleanLabel("   "))
        assertEquals("Fire TV", TvIdentity.displayName("", "Fire TV"))
        assertEquals("The Wall", TvIdentity.displayName("The Wall", "Fire TV"))
        assertEquals("Office TV (Fire TV)", TvIdentity.listName("Office TV", "Fire TV"))
        assertEquals("Google TV", TvIdentity.listName("", "Google TV"))
        assertEquals("TV", TvIdentity.displayName("", ""))
    }
}
