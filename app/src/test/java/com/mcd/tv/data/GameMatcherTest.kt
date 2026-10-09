package com.mcd.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding playlist channels for a game: event channels, networks, and the false matches to avoid. */
class GameMatcherTest {

    private fun team(nick: String, location: String, abbr: String, short: String = nick) = TeamLine(
        id = abbr, name = "$location $nick", short = short, abbr = abbr, nickname = nick, location = location,
        logo = null, score = "", record = "",
    )

    private fun game(league: League, away: TeamLine, home: TeamLine, vararg broadcasts: String) = Game(
        id = "${away.abbr}-${home.abbr}", league = league, state = "pre", detail = "", startMs = 1_791_738_000_000L,
        timeTbd = false, home = home, away = away, broadcasts = broadcasts.toList(),
    )

    private var n = 0
    private fun ch(name: String, group: String) = Channel(name, null, group, "http://host/${n++}.ts")

    /** Channel names matched for [g], best first, with why each matched. */
    private fun matches(g: Game, channels: List<Channel>): List<Pair<String, String>> {
        val index = LiveOrganizer.build(channels)
        val hits = GameMatcher.match(listOf(g), index, emptyMap())[g.key]?.hits.orEmpty()
        return hits.map { index.channels[it.idx].name to it.why }
    }

    private val packers = team("Packers", "Green Bay", "GB")
    private val bears = team("Bears", "Chicago", "CHI")

    @Test
    fun eventChannelNamingBothTeamsRanksFirst() {
        val channels = listOf(
            ch("US| FOX 32 Chicago WFLD", "US| LOCALS"),
            ch("US| ESPN HD", "US| SPORTS"),
            ch("NFL 03: Packers @ Bears 1:00 PM", "US| NFL GAME PASS"),
            ch("NFL 04: Lions @ Vikings 1:00 PM", "US| NFL GAME PASS"),
        )
        val m = matches(game(League.NFL, packers, bears, "FOX"), channels)
        assertEquals("NFL 03: Packers @ Bears 1:00 PM", m.first().first)
        assertEquals("Event: Packers at Bears", m.first().second)
        assertTrue("FOX affiliate is offered", m.any { it.first == "US| FOX 32 Chicago WFLD" && it.second == "FOX" })
        assertFalse(m.any { it.first.contains("Lions") })
        assertFalse(m.any { it.first.contains("ESPN") }) // not carrying this game
    }

    @Test
    fun abbreviationPairEventChannel() {
        val channels = listOf(ch("NFL 07: GB vs CHI", "US| NFL GAME PASS"), ch("GB News", "UK| NEWS"))
        val m = matches(game(League.NFL, packers, bears), channels)
        assertEquals(listOf("NFL 07: GB vs CHI"), m.map { it.first })
    }

    @Test
    fun foxNewsDoesNotMatchFox() {
        val channels = listOf(
            ch("US| FOX NEWS HD", "US| NEWS"),
            ch("US| FOX BUSINESS", "US| NEWS"),
            ch("US| FOX SPORTS 1", "US| SPORTS"),
            ch("US| FOX HD", "US| LOCALS"),
        )
        val m = matches(game(League.NFL, packers, bears, "FOX"), channels)
        assertEquals(listOf("US| FOX HD"), m.map { it.first })
    }

    @Test
    fun cbsAffiliateMatchesButCbsSportsNetworkDoesNot() {
        val channels = listOf(
            ch("US| CBS SPORTS NETWORK", "US| SPORTS"),
            ch("US| CBS NEWS 24/7", "US| NEWS"),
            ch("US| CBS 2 WCBS New York", "US| LOCALS"),
            ch("US| CBS 5 KPHO Phoenix", "US| LOCALS"),
            ch("UK| CBS Reality", "UK| ENTERTAINMENT"),
        )
        val chiefs = team("Chiefs", "Kansas City", "KC")
        val bills = team("Bills", "Buffalo", "BUF")
        val m = matches(game(League.NFL, chiefs, bills, "CBS"), channels)
        assertEquals(setOf("US| CBS 2 WCBS New York", "US| CBS 5 KPHO Phoenix"), m.map { it.first }.toSet())
        assertTrue(m.all { it.second == "CBS" })
    }

    @Test
    fun plainNetworkChannelRanksAboveAffiliates() {
        val channels = listOf(
            ch("US| CBS 5 KPHO Phoenix", "US| LOCALS"),
            ch("US| CBS HD", "US| LOCALS"),
        )
        val m = matches(game(League.NFL, packers, bears, "CBS"), channels)
        assertEquals(listOf("US| CBS HD", "US| CBS 5 KPHO Phoenix"), m.map { it.first })
    }

    @Test
    fun mlbGiantsChannelDoesNotMatchNflGiantsGame() {
        val giants = team("Giants", "New York", "NYG")
        val cowboys = team("Cowboys", "Dallas", "DAL")
        val channels = listOf(
            ch("MLB: Giants vs Dodgers", "US| MLB PACKAGE"),
            ch("NBC Sports Bay Area: SF Giants vs Cowboys Rodeo", "US| MLB PACKAGE"),
            ch("NFL 01: Giants vs Cowboys", "US| NFL GAME PASS"),
        )
        val m = matches(game(League.NFL, giants, cowboys), channels)
        assertEquals(listOf("NFL 01: Giants vs Cowboys"), m.map { it.first })
    }

    @Test
    fun genericNicknamesNeedSportsContext() {
        // "Giants" and "Cowboys" are everyday words: a movie channel naming both is not the game.
        val giants = team("Giants", "New York", "NYG")
        val cowboys = team("Cowboys", "Dallas", "DAL")
        val channels = listOf(ch("Giants and Cowboys Marathon", "US| ENTERTAINMENT"))
        assertTrue(matches(game(League.NFL, giants, cowboys), channels).isEmpty())
    }

    @Test
    fun collegeOhioDoesNotMatchOhioState() {
        val ohio = team("Bobcats", "Ohio", "OHIO", short = "Ohio")
        val kent = team("Golden Flashes", "Kent State", "KENT", short = "Kent State")
        val channels = listOf(
            ch("NCAAF: Ohio State vs Michigan", "US| COLLEGE FOOTBALL"),
            ch("NCAAF: Ohio vs Kent State", "US| COLLEGE FOOTBALL"),
        )
        val m = matches(game(League.NCAAF, ohio, kent), channels)
        assertEquals(listOf("NCAAF: Ohio vs Kent State"), m.map { it.first })

        // And the other way: Ohio State's game matches its channel, not Michigan State's.
        val osu = team("Buckeyes", "Ohio State", "OSU", short = "Ohio State")
        val mich = team("Wolverines", "Michigan", "MICH", short = "Michigan")
        val channels2 = listOf(
            ch("NCAAF: Michigan State vs Iowa", "US| COLLEGE FOOTBALL"),
            ch("NCAAF: Ohio State vs Michigan", "US| COLLEGE FOOTBALL"),
        )
        assertEquals(listOf("NCAAF: Ohio State vs Michigan"), matches(game(League.NCAAF, mich, osu), channels2).map { it.first })
    }

    @Test
    fun guideListingMatches() {
        val channels = listOf(ch("US| NFL Network", "US| SPORTS"), ch("US| Bravo", "US| ENTERTAINMENT"))
        val index = LiveOrganizer.build(channels)
        val g = game(League.NFL, packers, bears)
        val guide = mapOf(
            channels[0].url to listOf(Programme("NFL Football: Packers at Bears", g.startMs - 600_000L, g.startMs + 3 * 3_600_000L, "")),
            channels[1].url to listOf(Programme("Packers at Bears", g.startMs, g.startMs + 3_600_000L, "")), // no sports context
        )
        val hits = GameMatcher.match(listOf(g), index, guide)[g.key]!!.hits
        assertEquals(listOf("US| NFL Network"), hits.map { index.channels[it.idx].name })
        assertEquals("Guide: Packers at Bears", hits[0].why)
    }

    @Test
    fun leagueChannelsListRedZoneFirst() {
        val channels = listOf(
            ch("NFL 01: Packers @ Bears", "US| NFL GAME PASS"),
            ch("US| NFL Network HD", "US| SPORTS"),
            ch("US| NFL RedZone HD", "US| SPORTS"),
            ch("US| NBA TV", "US| SPORTS"),
        )
        val index = LiveOrganizer.build(channels)
        val names = GameMatcher.leagueChannels(League.NFL, index).map { index.channels[it].name }
        assertEquals(listOf("US| NFL RedZone HD", "US| NFL Network HD", "NFL 01: Packers @ Bears"), names)
    }

    @Test
    fun normAndWords() {
        assertEquals("packers at bears", GameMatcher.norm("Packers @ Bears"))
        assertEquals("espn plus", GameMatcher.norm("ESPN+"))
        assertEquals("a e", GameMatcher.norm("  A&E!! "))
        assertArrayEquals(arrayOf("nfl", "03", "gb", "vs", "chi"), GameMatcher.words("NFL 03: GB vs. CHI"))
        assertEquals(0, GameMatcher.words("  --  ").size)
    }

    @Test
    fun emptyInputsGiveEmptyResults() {
        val index = LiveOrganizer.build(emptyList())
        assertTrue(GameMatcher.match(listOf(game(League.NFL, packers, bears)), index, emptyMap()).isEmpty())
        assertEquals(0, GameMatcher.leagueChannels(League.NFL, index).size)
    }
}
