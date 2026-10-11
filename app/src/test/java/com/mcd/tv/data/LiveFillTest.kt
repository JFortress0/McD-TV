package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guide borrowing keys, event-channel titles, the network's game and Huntsville first (same cases as the web app). */
class LiveFillTest {

    /** Epoch ms for a wall time in US Eastern during daylight time (UTC-4), as in October. */
    private fun edt(d: Int, h: Int, m: Int = 0): Long = utc(2026, 10, d, h + 4, m)

    private fun utc(y: Int, mo: Int, d: Int, h: Int, m: Int): Long {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, mo - 1, d, 0, 0, 0)
        return c.timeInMillis + h * 3_600_000L + m * 60_000L
    }

    private val now = edt(10, 16) // Saturday Oct 10 2026, 4:00 PM Eastern

    // ---------------------------------------------------------------- guide keys

    @Test
    fun guideKeyJoinsNumbersAndDropsTags() {
        assertEquals("espn2", LiveFill.guideKey("US| ESPN 2 FHD"))
        assertEquals("espn2", LiveFill.guideKey("ESPN2 (backup)"))
        assertEquals("espn2", LiveFill.guideKey("ESPN2 [Backup 2]"))
        assertEquals("a and e", LiveFill.guideKey("A&E HD"))
        assertEquals("a and e", LiveFill.guideKey("A and E"))
        assertEquals("usa network", LiveFill.guideKey("USA Network"))
        assertEquals("fox news", LiveFill.guideKey("Fox News US"))
    }

    @Test
    fun guideKeyKeepsWestApart() {
        assertEquals("hbo", LiveFill.guideKey("HBO East"))
        assertEquals("hbo", LiveFill.guideKey("HBO (E)"))
        assertEquals("hbo west", LiveFill.guideKey("HBO West"))
        assertEquals("hbo west", LiveFill.guideKey("HBO (Pacific)"))
        assertEquals("hbo west", LiveFill.guideKey("HBO (W)"))
    }

    // ---------------------------------------------------------------- Huntsville and networks

    @Test
    fun huntsvilleStations() {
        assertEquals(0, LiveFill.homeRank("US: CBS 19 WHNT Huntsville"))
        assertEquals(1, LiveFill.homeRank("WAAY-TV 31"))
        assertEquals(2, LiveFill.homeRank("WAFF 48"))
        assertEquals(3, LiveFill.homeRank("WZDX54"))
        assertEquals(4, LiveFill.homeRank("WHDF CW"))
        assertEquals(-1, LiveFill.homeRank("Fox 5 Atlanta"))
        assertEquals(-1, LiveFill.homeRank("WHNTX"))
    }

    @Test
    fun networksOfChannels() {
        assertEquals("ESPN", LiveFill.networkOf("US| ESPN FHD"))
        assertEquals("ESPN2", LiveFill.networkOf("ESPN 2 HD"))
        assertEquals("", LiveFill.networkOf("ESPN Deportes"))
        assertEquals("", LiveFill.networkOf("ESPN News"))
        assertEquals("", LiveFill.networkOf("ESPN+ 12"))
        assertEquals("SECN", LiveFill.networkOf("SEC Network"))
        assertEquals("ACCN", LiveFill.networkOf("ACC Network HD"))
        assertEquals("BTN", LiveFill.networkOf("Big Ten Network"))
        assertEquals("FOX", LiveFill.networkOf("FOX 54 Huntsville"))
        assertEquals("", LiveFill.networkOf("Fox News"))
        assertEquals("", LiveFill.networkOf("CBS Sports Network"))
        assertEquals("CBS", LiveFill.networkOf("WHNT News 19"))
        assertEquals("CW", LiveFill.networkOf("WHDF"))
        assertEquals("SECN", LiveFill.castNetwork("SEC Network"))
        assertEquals("CW", LiveFill.castNetwork("The CW"))
    }

    @Test
    fun localSectionListsHuntsvilleFirst() {
        var n = 0
        fun ch(name: String) = Channel(name, null, "US| LOCALS", "http://host/${n++}.ts")
        val index = LiveOrganizer.build(
            listOf(ch("US| CBS 2 New York"), ch("US| FOX 54 WZDX Huntsville"), ch("US| NBC 4"), ch("US| CBS 19 WHNT Huntsville"), ch("US| ABC 7")),
        )
        val local = index.sections.first { it.name == LiveOrganizer.LOCAL }
        val names = local.items.map { index.names[it] }
        assertEquals(listOf("CBS 19 WHNT Huntsville", "FOX 54 WZDX Huntsville", "CBS 2 New York", "NBC 4", "ABC 7"), names)
    }

    // ---------------------------------------------------------------- event channels

    @Test
    fun parsesEventTitleTimeAndLength() {
        val p = LiveFill.eventProgramme("NCAAF 05: Alabama vs Tennessee 3:30PM ET", "USA | NCAAF", now)
        assertNotNull(p)
        assertEquals("Alabama vs Tennessee", p!!.title)
        assertEquals(edt(10, 15, 30), p.start)
        assertEquals(210L, (p.end - p.start) / 60_000L)
        assertFalse(p.untimed)
    }

    @Test
    fun eventVariants() {
        assertEquals("Packers at Bears", LiveFill.eventProgramme("NFL 03 | Packers @ Bears 8:20 PM ET", "NFL", now)?.title)
        assertEquals(edt(10, 20, 20), LiveFill.eventProgramme("NFL 03 | Packers @ Bears 8:20 PM ET", "NFL", now)?.start)
        val nba = LiveFill.eventProgramme("NBA: Lakers at Celtics (7:00 PM EST)", "", now)!!
        assertEquals("Lakers at Celtics", nba.title)
        assertEquals(edt(10, 19), nba.start)
        assertEquals(150L, (nba.end - nba.start) / 60_000L)
        val mlb = LiveFill.eventProgramme("MLB 02: Yankees v Red Sox 8PM ET", "", now)!!
        assertEquals("Yankees vs Red Sox", mlb.title)
        assertEquals(edt(10, 20), mlb.start)
        assertEquals(edt(10, 16, 30), LiveFill.eventProgramme("Georgia vs Auburn 3:30PM CT", "", now)?.start)
        assertEquals("UFC 310: Main Card", LiveFill.eventProgramme("UFC 310: Main Card 10PM ET", "", now)?.title)
        // Date in the name, and a 1 AM start belongs to tomorrow.
        val dated = LiveFill.eventProgramme("NCAAF 05: Sat 10/11 Alabama vs Tennessee 9:00AM ET", "", now)!!
        assertEquals("Alabama vs Tennessee", dated.title)
        assertEquals(edt(11, 9), dated.start)
        assertEquals(edt(11, 1), LiveFill.eventProgramme("NFL 03 - Packers vs Bears 1AM ET", "", now)?.start)
    }

    @Test
    fun eventWithoutTimeIsUntimed() {
        val p = LiveFill.eventProgramme("NCAAF 07: Georgia vs Auburn", "", now)!!
        assertEquals("Georgia vs Auburn", p.title)
        assertTrue(p.untimed)
    }

    @Test
    fun eventTimeAcrossSeasonsAndZones() {
        // Winter: Eastern is UTC-5.
        val jan = utc(2027, 1, 15, 20, 0)
        assertEquals(utc(2027, 1, 16, 0, 0), LiveFill.eventProgramme("NBA 01: Hawks at Bulls 7:00PM ET", "", jan)?.start)
        val epl = LiveFill.eventProgramme("EPL: Arsenal vs Chelsea 12:30 UK", "", utc(2027, 1, 15, 11, 0))!!
        assertEquals(utc(2027, 1, 15, 12, 30), epl.start)
        assertEquals(120L, (epl.end - epl.start) / 60_000L)
    }

    @Test
    fun notEvents() {
        assertNull(LiveFill.eventProgramme("NFL 01", "", now))
        assertNull(LiveFill.eventProgramme("ESPN 2", "", now))
        assertNull(LiveFill.eventProgramme("Alien vs Predator", "", now))
        assertNull(LiveFill.eventProgramme("NCAAF 05: No Event", "", now))
        assertNull(LiveFill.eventProgramme("NFL 04: Bills vs Jets 12:00PM ET", "", now)) // over at 3:30
    }

    // ---------------------------------------------------------------- the network's game

    private fun team(short: String) = TeamLine(short, short, short, short.take(3).uppercase(), short, short, null, "", "")

    private fun game(id: String, state: String, start: Long, away: String, home: String, vararg casts: String) = Game(
        id, League.NCAAF, state, if (state == "in") "Q2 3:00" else "", start, false, team(home), team(away), casts.toList(),
    )

    private val games = listOf(
        game("1", "post", now - 5 * 3_600_000L, "A", "B", "ESPN"),
        game("2", "in", now - 3_600_000L, "Ole Miss", "LSU", "ESPN", "ESPN+"),
        game("3", "pre", now + 3_600_000L, "Auburn", "Georgia", "SEC Network"),
    )

    @Test
    fun liveOrNextGameOnTheNetwork() {
        val live = LiveFill.gameProgramme("ESPN", games, now)!!
        assertEquals("Ole Miss at LSU", live.title)
        assertEquals("Live now. Q2 3:00", live.desc)
        val next = LiveFill.gameProgramme("SECN", games, now)!!
        assertEquals("Auburn at Georgia", next.title)
        assertEquals(now + 3_600_000L, next.start)
        assertNull(LiveFill.gameProgramme("BTN", games, now))
        assertNull(LiveFill.gameProgramme("", games, now))
    }

    @Test
    fun nowNextPrefersTheRealGuide() {
        val espn = Channel("US| ESPN HD", null, "US| SPORTS", "http://host/espn.ts")
        val real = Programme("SportsCenter", now - 600_000L, now + 600_000L, "")
        assertEquals("SportsCenter", LiveFill.nowNext(espn, mapOf(espn.url to listOf(real)), now) { games }.first?.title)
        // No guide: the network's live game.
        assertEquals("Ole Miss at LSU", LiveFill.nowNext(espn, emptyMap(), now) { games }.first?.title)
        // Upcoming game: shown as next.
        val sec = Channel("SEC Network", null, "US| SPORTS", "http://host/sec.ts")
        val (cur, nxt) = LiveFill.nowNext(sec, emptyMap(), now) { games }
        assertNull(cur)
        assertEquals("Auburn at Georgia", nxt?.title)
        // Event channel title wins over the network.
        val ev = Channel("NCAAF 05: Alabama vs Tennessee 3:30PM ET", null, "NCAAF", "http://host/ev.ts")
        assertEquals("Alabama vs Tennessee", LiveFill.nowNext(ev, emptyMap(), now) { games }.first?.title)
    }

    @Test
    fun programmesForGridsUseTheSameFallbacks() {
        val espn = Channel("US| ESPN HD", null, "US| SPORTS", "http://host/espn.ts")
        val real = listOf(Programme("SportsCenter", now - 600_000L, now + 600_000L, ""), Programme("NFL Live", now + 600_000L, now + 4_200_000L, ""))
        assertEquals(listOf("SportsCenter", "NFL Live"), LiveFill.programmes(espn, mapOf(espn.url to real), now) { games }.map { it.title })
        // Only finished shows in the guide: the fallback, like nowNext.
        val over = listOf(Programme("Old", now - 7_200_000L, now - 3_600_000L, ""))
        assertEquals(listOf("Ole Miss at LSU"), LiveFill.programmes(espn, mapOf(espn.url to over), now) { games }.map { it.title })
        assertEquals(listOf("Auburn at Georgia"), LiveFill.programmes(Channel("SEC Network", null, "US| SPORTS", "http://host/sec.ts"), emptyMap(), now) { games }.map { it.title })
        val ev = Channel("NCAAF 05: Alabama vs Tennessee 3:30PM ET", null, "NCAAF", "http://host/ev.ts")
        assertEquals(listOf("Alabama vs Tennessee"), LiveFill.programmes(ev, emptyMap(), now) { games }.map { it.title })
        assertTrue(LiveFill.programmes(Channel("US| HISTORY", null, "US| ENTERTAINMENT", "http://host/h.ts"), emptyMap(), now) { games }.isEmpty())
    }

    @Test
    fun huntsvilleStationRanksFirstForItsNetworkGame() {
        var n = 0
        fun ch(name: String, group: String) = Channel(name, null, group, "http://host/${n++}.ts")
        val channels = listOf(
            ch("US| CBS 2 New York HD", "US| LOCALS"),
            ch("US| CBS HD", "US| LOCALS"),
            ch("US| WHNT News 19", "US| LOCALS"),
        )
        val index = LiveOrganizer.build(channels)
        val g = Game("9", League.NCAAF, "pre", "", now, false, team("Alabama"), team("Tennessee"), listOf("CBS"))
        val hits = GameMatcher.match(listOf(g), index, emptyMap())[g.key]?.hits.orEmpty()
        assertEquals("US| WHNT News 19", index.channels[hits.first().idx].name)
    }
}
