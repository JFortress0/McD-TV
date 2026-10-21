package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Favorite teams: matching a game's teams, the defaults, pinning in a league and the My Teams list. */
class MyTeamsTest {

    private fun team(location: String, nick: String, abbr: String, short: String = nick) =
        TeamLine(id = abbr, name = "$location $nick", short = short, abbr = abbr, nickname = nick, location = location, logo = null, score = "", record = "")

    private val eagles = team("Philadelphia", "Eagles", "PHI")
    private val cowboys = team("Dallas", "Cowboys", "DAL")
    private val sixers = team("Philadelphia", "76ers", "PHI")
    private val celtics = team("Boston", "Celtics", "BOS")
    private val flyers = team("Philadelphia", "Flyers", "PHI")
    private val rangers = team("New York", "Rangers", "NYR")
    private val bcEagles = team("Boston College", "Eagles", "BC", short = "Boston College")

    private fun game(id: String, league: League, away: TeamLine, home: TeamLine, state: String = "pre", start: Long = 0L) =
        Game(id, league, state, "", start, false, home, away, emptyList())

    @Test
    fun matchesByNicknameFullNameOrAbbreviation() {
        assertTrue(FavoriteTeam(League.NFL, "Eagles").matches(eagles))
        assertTrue(FavoriteTeam(League.NFL, "philadelphia eagles").matches(eagles))
        assertTrue(FavoriteTeam(League.NFL, "PHI").matches(eagles))
        assertTrue(FavoriteTeam(League.NBA, "76ers").matches(sixers))
        assertFalse(FavoriteTeam(League.NFL, "Eagles").matches(cowboys))
        // A city alone is not a team: "Philadelphia" would also match the 76ers' and Flyers' cities.
        assertFalse(FavoriteTeam(League.NFL, "Philadelphia").matches(eagles))
        assertFalse(FavoriteTeam(League.NFL, "").matches(eagles))
    }

    @Test
    fun aFavoriteBelongsToItsLeague() {
        val teams = listOf(FavoriteTeam(League.NFL, "Eagles"))
        assertTrue(MyTeams.isMine(game("1", League.NFL, cowboys, eagles), teams))
        // Boston College's Eagles are a college team, not the NFL favorite.
        assertFalse(MyTeams.isMine(game("2", League.NCAAF, bcEagles, team("Miami", "Hurricanes", "MIA")), teams))
    }

    @Test
    fun dadStartsWithPhiladelphiaOthersWithNothing() {
        val dad = FavoriteTeam.listFrom(null, "p1")
        assertEquals(listOf("NFL:Eagles", "MLB:Phillies", "NHL:Flyers", "NBA:76ers"), dad.map { it.stored })
        assertEquals(emptyList<FavoriteTeam>(), FavoriteTeam.listFrom(null, "p2"))
        // Once saved, the saved list wins, even when empty.
        assertEquals(emptyList<FavoriteTeam>(), FavoriteTeam.listFrom(emptyList(), "p1"))
        assertEquals(listOf(FavoriteTeam(League.NHL, "Flyers")), FavoriteTeam.listFrom(listOf("NHL:Flyers", "XFL:Guardians", "NFL:", "NHL:Flyers"), "p1"))
    }

    @Test
    fun parseAndStoreRoundTrip() {
        val t = FavoriteTeam(League.MLB, "Phillies")
        assertEquals(t, FavoriteTeam.parse(t.stored))
        assertNull(FavoriteTeam.parse("Phillies"))
        assertNull(FavoriteTeam.parse(":Phillies"))
    }

    @Test
    fun favoriteGamesGoFirstInTheirLeague() {
        val teams = FavoriteTeam.listFrom(null, "p1")
        val a = game("a", League.NBA, celtics, team("Miami", "Heat", "MIA"))
        val b = game("b", League.NBA, sixers, celtics)
        val c = game("c", League.NBA, team("Utah", "Jazz", "UTA"), team("Denver", "Nuggets", "DEN"))
        assertEquals(listOf("b", "a", "c"), MyTeams.pinned(listOf(a, b, c), teams).map { it.id })
        assertEquals(listOf("a", "b", "c"), MyTeams.pinned(listOf(a, b, c), emptyList()).map { it.id })
    }

    @Test
    fun myTeamsCollectsLiveThenUpcomingAcrossLeaguesAndDropsFinals() {
        val teams = FavoriteTeam.listFrom(null, "p1")
        val nfl = Schedule(League.NFL, listOf(game("e", League.NFL, eagles, cowboys, start = 300L)), null, 0L)
        val nhl = Schedule(
            League.NHL,
            listOf(
                game("f1", League.NHL, flyers, rangers, state = "post", start = 50L),
                game("f2", League.NHL, rangers, flyers, state = "in", start = 100L),
                game("x", League.NHL, rangers, team("Boston", "Bruins", "BOS"), start = 10L),
            ),
            null, 0L,
        )
        val nba = Schedule(League.NBA, listOf(game("s", League.NBA, celtics, sixers, start = 200L)), null, 0L)
        assertEquals(listOf("f2", "s", "e"), MyTeams.collect(listOf(nfl, nhl, nba), teams).map { it.id })
        assertEquals(emptyList<Game>(), MyTeams.collect(listOf(nfl, nhl, nba), emptyList()))
    }

    @Test
    fun parsesEspnTeamsList() {
        val body = """{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"21","displayName":"Philadelphia Eagles","shortDisplayName":"Eagles","name":"Eagles","abbreviation":"PHI","location":"Philadelphia"}},
            {"team":{"id":"6","displayName":"Dallas Cowboys","shortDisplayName":"Cowboys","name":"Cowboys","abbreviation":"DAL","location":"Dallas"}},
            {"team":{"id":"21","displayName":"Philadelphia Eagles","shortDisplayName":"Eagles","name":"Eagles","abbreviation":"PHI","location":"Philadelphia"}}
        ]}]}]}"""
        val teams = Games.parseTeams(body)
        assertEquals(listOf("Cowboys", "Eagles"), teams.map { it.short })
        assertTrue(FavoriteTeam(League.NFL, teams[1].short).matches(eagles))
        assertEquals(emptyList<TeamLine>(), Games.parseTeams("{}"))
    }
}
