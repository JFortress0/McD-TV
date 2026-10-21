package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * The Heads-up card, tested on a real feed body: tools/wallfeed/spotlight-feed.json is what the Wall feed code
 * (wall/netlify/functions/feed.mts) makes from the iCal fixtures in tools/wallfeed (node tools/wallfeed/feed-fixture.mjs).
 * The fixture clock is Wednesday, October 21, 2026, in Huntsville.
 */
class WallSpotlightTest {
    companion object {
        private val savedZone: TimeZone = TimeZone.getDefault()
        private const val TODAY = 20261021

        @JvmStatic @BeforeClass
        fun zone() = TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))

        @JvmStatic @AfterClass
        fun restore() = TimeZone.setDefault(savedZone)

        private val feedBody: String by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "tools/wallfeed/spotlight-feed.json").exists()) dir = dir.parentFile
            File(dir ?: error("spotlight-feed.json not found"), "tools/wallfeed/spotlight-feed.json").readText()
        }
    }

    private fun feed() = WallFeed.parse(feedBody)

    @Test
    fun weeklyRepeatsNeverShowEvenWhenTheyAreTheSoonestEvents() {
        val f = feed()
        assertTrue(f.repeatsKnown)
        val weekly = f.events.filter { it.title.startsWith("Weekly") }
        assertTrue("fixture has weekly dates in the window", weekly.count { it.firstDay in 20261022..20261118 } >= 8)
        assertTrue(weekly.all { it.repeats })
        // Every day of the next 8 weeks: no weekly or other repeating event ever reaches the card.
        var day = TODAY
        repeat(56) {
            WallSpotlight.pick(f, day).forEach { s -> assertTrue("${s.title} on $day", !s.event.repeats) }
            assertTrue(WallSpotlight.pick(f, day).none { it.title.startsWith("Weekly") || it.title.startsWith("Review") || it.title.startsWith("Moved repeat") })
            day = Day.plus(day, 1)
        }
    }

    @Test
    fun soonestThreeOneOffsWithTheirCopy() {
        val spots = WallSpotlight.pick(feed(), TODAY)
        assertEquals(listOf("Dentist", "Concert", "Beach trip"), spots.map { it.title })

        val tomorrow = spots[0]
        assertEquals("Tomorrow", tomorrow.countdown)
        assertEquals("Thu, Oct 22 at 2:30 PM", tomorrow.days)
        assertEquals("Main St Dental", tomorrow.location)

        assertEquals("Next Tuesday", spots[1].countdown)
        assertEquals("Tue, Oct 27 at 7:00 PM", spots[1].days)
        assertNull(spots[1].location)

        val trip = spots[2]
        assertEquals("In 3 weeks", trip.countdown)
        assertEquals("Wed, Nov 11 to Sat, Nov 14", trip.days)
        assertEquals("Gulf Shores, AL", trip.location)
    }

    @Test
    fun leavesOutTodaySchoolFarOffAndTheSharedCopy() {
        val f = feed()
        val titles = WallSpotlight.pick(f, TODAY).map { it.title }
        assertTrue("Haircut today" !in titles)
        assertTrue("Six weeks out" !in titles)
        assertTrue(titles.none { it.startsWith("Module") })
        assertEquals(1, titles.count { it.equals("Beach trip", ignoreCase = true) })
        // A day later the dentist is today: the next one-off moves up.
        assertEquals(listOf("Concert", "Beach trip", "Fourth one-off"), WallSpotlight.pick(f, 20261022).map { it.title })
    }

    @Test
    fun aFeedWithoutRepeatInfoShowsNothing() {
        val old = WallFeed.parse(feedBody.replace("\"v\": 2", "\"v\": 1"))
        assertTrue(!old.repeatsKnown)
        assertTrue(WallSpotlight.pick(old, TODAY).isEmpty())
    }

    @Test
    fun countdownWords() {
        // Wednesday, October 21, 2026.
        assertEquals("Tomorrow", WallSpotlight.countdown(TODAY, 20261022))
        assertEquals("This Friday", WallSpotlight.countdown(TODAY, 20261023))
        assertEquals("This Saturday", WallSpotlight.countdown(TODAY, 20261024))
        assertEquals("Next Sunday", WallSpotlight.countdown(TODAY, 20261025))
        assertEquals("Next Tuesday", WallSpotlight.countdown(TODAY, 20261027))
        assertEquals("Next Saturday", WallSpotlight.countdown(TODAY, 20261031))
        assertEquals("In 2 weeks", WallSpotlight.countdown(TODAY, 20261101))
        assertEquals("In 3 weeks", WallSpotlight.countdown(TODAY, 20261111))
        assertEquals("In 4 weeks", WallSpotlight.countdown(TODAY, 20261118))
        // From a Saturday, the next week starts tomorrow.
        assertEquals("Tomorrow", WallSpotlight.countdown(20261024, 20261025))
        assertEquals("Next Tuesday", WallSpotlight.countdown(20261024, 20261027))
        assertEquals("In a week", WallSpotlight.countdown(20261024, 20261101))
        // Across the clock change on November 1.
        assertEquals("In 2 weeks", WallSpotlight.countdown(20261029, 20261112))
    }

    @Test
    fun theCardNeverAddsCalendarTimeOrTakesPhotoTime() {
        for (key in listOf("30/1", "10/1", "10/5", "15/2", "60/5", "0/0")) {
            val c = WallCycle.parse(key)
            var monthMinutes = 0
            var photoMinutes = 0
            var cardMinutes = 0
            for (m in 0 until 1440) {
                val without = WallSpotlight.frame(c, 0, m)
                val with = WallSpotlight.frame(c, 3, m)
                assertEquals("month at minute $m with $key", without.month, with.month)
                assertEquals(WallCycle.showCalendar(c, m), with.month)
                if (with.month) monthMinutes++ else photoMinutes++
                if (with.spot != null) cardMinutes++
            }
            var baseMonth = 0
            for (m in 0 until 1440) if (WallCycle.showCalendar(c, m)) baseMonth++
            assertEquals("calendar minutes a day, $key", baseMonth, monthMinutes)
            assertEquals("photo minutes a day, $key", 1440 - baseMonth, photoMinutes)
            assertTrue("card shows, $key", cardMinutes > 0)
        }
        // Default cycle: 48 calendar minutes and 1392 photo minutes a day, with or without the card.
        val d = WallCycle.parse(WallCycle.DEFAULT)
        assertEquals(48, (0 until 1440).count { WallSpotlight.frame(d, 3, it).month })
        assertEquals(1392, (0 until 1440).count { !WallSpotlight.frame(d, 3, it).month })
    }

    @Test
    fun threeEventsTakeTurns() {
        val c = WallCycle.parse(WallCycle.DEFAULT)
        val shown = (0 until 60).mapNotNull { WallSpotlight.frame(c, 3, it).spot }
        // :02, :07, :12 ... one minute in five, cycling through the three.
        assertEquals(12, shown.size)
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2), shown)
        assertEquals(listOf(0, 0, 0), (0 until 15).mapNotNull { WallSpotlight.frame(c, 1, it).spot })
        assertTrue((0 until 1440).all { WallSpotlight.frame(c, 0, it).spot == null })
    }
}
