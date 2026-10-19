package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentErrorsTest {
    @Test
    fun newestFirstAndCapped() {
        var l = emptyList<RecentErrors.Entry>()
        for (i in 1..15) l = RecentErrors.add(l, "Error $i", i.toLong())
        assertEquals(RecentErrors.KEEP, l.size)
        assertEquals("Error 15", l.first().text)
        assertEquals("Error 6", l.last().text)
    }

    @Test
    fun repeatsFoldIntoACount() {
        var l = RecentErrors.add(emptyList(), "Source failed", 1)
        l = RecentErrors.add(l, "  Source\n failed ", 2)
        assertEquals(1, l.size)
        assertEquals(2, l.first().count)
        assertEquals(2L, l.first().at)
        l = RecentErrors.add(l, "", 3)
        assertEquals(1, l.size)
    }

    @Test
    fun roundTrip() {
        val l = RecentErrors.add(RecentErrors.add(emptyList(), "A", 1), "B", 2)
        assertEquals(l, RecentErrors.decode(RecentErrors.encode(l)))
        assertTrue(RecentErrors.decode("not json").isEmpty())
    }
}
