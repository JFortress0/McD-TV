package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyCountdownTest {
    private val due = 20270224

    @Test
    fun countsDaysAndWeeks() {
        val s = BabyCountdown.state(20261010, due)!!
        assertEquals(137, s.daysLeft)
        assertEquals(20, s.week)
        assertEquals(3, s.day)
        assertEquals("137 days to go", s.headline)
        assertEquals("Week 20, day 3. Due Feb 24.", s.detail)
    }

    @Test
    fun crossesTheNewYearAndDaylightTime() {
        assertEquals(55, BabyCountdown.daysBetween(20261231, due))
        assertEquals(1, BabyCountdown.daysBetween(20270313, 20270314))
        assertEquals(1, BabyCountdown.daysBetween(20261031, 20261101))
    }

    @Test
    fun dueDayAndAfter() {
        assertEquals("1 day to go", BabyCountdown.state(20270223, due)!!.headline)
        val today = BabyCountdown.state(due, due)!!
        assertEquals("Due today", today.headline)
        assertEquals("Week 40. Due Feb 24.", today.detail)
        assertEquals(1f, today.progress)
        assertEquals("3 days past the due date", BabyCountdown.state(20270227, due)!!.headline)
        assertNull("hidden three weeks after", BabyCountdown.state(20270318, due))
        assertNull("hidden before the pregnancy", BabyCountdown.state(20260501, due))
    }

    @Test
    fun usesTheNameWhenSet() {
        assertEquals("137 days until Sam", BabyCountdown.state(20261010, due)!!.headlineFor(" Sam "))
        assertEquals("Sam is due today", BabyCountdown.state(due, due)!!.headlineFor("Sam"))
        assertEquals("Sam is 2 days past due", BabyCountdown.state(20270226, due)!!.headlineFor("Sam"))
        assertEquals("137 days to go", BabyCountdown.state(20261010, due)!!.headlineFor(""))
    }

    @Test
    fun parsesOnlyRealDates() {
        assertEquals(due, BabyCountdown.parse("2027-02-24"))
        assertNull(BabyCountdown.parse(""))
        assertNull(BabyCountdown.parse("02/24/2027"))
        assertNull(BabyCountdown.parse("2027-13-01"))
        assertNull(BabyCountdown.daysBetween(20261010, 20270231))
    }

    @Test
    fun findsTheDueDateEvent() {
        assertTrue(BabyCountdown.isDueEvent("Baby due!"))
        assertTrue(BabyCountdown.isDueEvent("Due date"))
        assertFalse(BabyCountdown.isDueEvent("SNHU assignment due"))
        assertTrue(BabyCountdown.isDueEvent("Baby girl due"))
        assertFalse(BabyCountdown.isDueEvent("Rent due date"))
    }
}
