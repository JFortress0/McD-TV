package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFavMergeTest {
    private fun s(vararg x: String) = x.toSet()

    @Test
    fun firstSyncKeepsBothLists() {
        val dozen = (1..12).map { "$it" }.toSet()
        assertEquals(dozen + s("50", "51"), LiveFavMerge.merge(emptySet(), s("50", "51"), dozen))
    }

    @Test
    fun removalsGoEverywhere() {
        val base = s("1", "2", "3")
        assertEquals(s("1", "3"), LiveFavMerge.merge(base, s("1", "3"), base))
        assertEquals(s("1", "2"), LiveFavMerge.merge(base, base, s("1", "2")))
    }

    @Test
    fun addsOnBothSides() {
        val base = s("1")
        assertEquals(s("1", "2", "3"), LiveFavMerge.merge(base, s("1", "2"), s("1", "3")))
    }

    @Test
    fun parsesAndFormats() {
        assertEquals(s("3", "10"), LiveFavMerge.parse(" 10,3,,"))
        assertEquals("10,3", LiveFavMerge.format(s("3", "10")))
        assertEquals(emptySet<String>(), LiveFavMerge.parse(""))
    }
}
