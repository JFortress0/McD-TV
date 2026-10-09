package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Games.parse on a hand-written reply shaped like ESPN's public scoreboard feed. */
class GamesParseTest {

    private val nflWeek6 = """
        {
          "season": {"type": 2, "year": 2026},
          "week": {"number": 6},
          "events": [
            {
              "id": "401772001",
              "date": "2026-10-11T17:00Z",
              "status": {"type": {"state": "pre", "shortDetail": "10/11 - 1:00 PM EDT"}},
              "competitions": [{
                "id": "401772001",
                "timeValid": true,
                "competitors": [
                  {"homeAway": "home", "score": "0",
                   "team": {"id": "3", "displayName": "Chicago Bears", "shortDisplayName": "Bears", "abbreviation": "CHI",
                            "name": "Bears", "location": "Chicago", "logo": "https://a.espncdn.com/chi.png"},
                   "records": [{"name": "Home", "type": "home", "summary": "2-0"}, {"name": "overall", "type": "total", "summary": "3-2"}]},
                  {"homeAway": "away", "score": "0",
                   "team": {"id": "9", "displayName": "Green Bay Packers", "shortDisplayName": "Packers", "abbreviation": "GB",
                            "name": "Packers", "location": "Green Bay", "logos": [{"href": "https://a.espncdn.com/gb.png"}]},
                   "records": [{"summary": "4-1"}]}
                ],
                "broadcasts": [{"market": "national", "names": ["FOX", "NFL+"]}],
                "geoBroadcasts": [{"media": {"shortName": "FOX"}}, {"media": {"shortName": "FOX Deportes"}}]
              }]
            },
            {
              "id": "401772002",
              "competitions": [{
                "id": "401772002",
                "date": "2026-10-12T00:20:00Z",
                "status": {"type": {"state": "in", "shortDetail": "Q3 5:12"}},
                "competitors": [
                  {"homeAway": "home", "score": {"value": 21.0, "displayValue": "21"}, "curatedRank": {"current": 99},
                   "team": {"id": "6", "displayName": "Dallas Cowboys", "shortDisplayName": "Cowboys", "abbreviation": "DAL", "name": "Cowboys", "location": "Dallas"}},
                  {"homeAway": "away", "score": null,
                   "team": {"id": "19", "displayName": "New York Giants", "shortDisplayName": "Giants", "abbreviation": "NYG", "name": "Giants", "location": "New York", "logo": null}}
                ],
                "broadcasts": [{"names": ["NBC", null, "", "Peacock"]}]
              }]
            },
            {"id": "broken-no-competitions"},
            {"id": "broken-one-team", "competitions": [{"competitors": [
              {"homeAway": "home", "team": {"id": "1", "displayName": "Only Team"}}
            ]}]},
            "not an object",
            {
              "id": "401772003",
              "date": "2026-10-13T00:15Z",
              "timeValid": false,
              "status": {"type": {"state": "", "shortDetail": "TBD"}},
              "competitions": [{
                "competitors": [
                  {"homeAway": "home", "team": {"id": "2", "displayName": "Buffalo Bills", "name": "Bills", "abbreviation": "BUF"}},
                  {"homeAway": "away", "team": {"id": "1", "displayName": "Atlanta Falcons", "name": "Falcons", "abbreviation": "ATL"}}
                ]
              }]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesScoreboardEvents() {
        val (games, week) = Games.parse(nflWeek6, League.NFL)
        assertEquals("WEEK 6", week)
        assertEquals(listOf("401772001", "401772002", "401772003"), games.map { it.id })

        val g = games[0]
        assertEquals(League.NFL, g.league)
        assertEquals("NFL:401772001", g.key)
        assertEquals("pre", g.state)
        assertFalse(g.live)
        assertEquals("10/11 - 1:00 PM EDT", g.detail)
        assertEquals(Instant.parse("2026-10-11T17:00:00Z").toEpochMilli(), g.startMs)
        assertFalse(g.timeTbd)
        assertEquals("Chicago Bears", g.home.name)
        assertEquals("Bears", g.home.short)
        assertEquals("CHI", g.home.abbr)
        assertEquals("Chicago", g.home.location)
        assertEquals("https://a.espncdn.com/chi.png", g.home.logo)
        assertEquals("3-2", g.home.record) // the overall record, not the first one listed
        assertEquals("Packers", g.away.nickname)
        assertEquals("https://a.espncdn.com/gb.png", g.away.logo) // from the logos array
        assertEquals("4-1", g.away.record)
        // Broadcast names first, then geo broadcasts not already listed (case-insensitive).
        assertEquals(listOf("FOX", "NFL+", "FOX Deportes"), g.broadcasts)
    }

    @Test
    fun parsesLiveGameWithStatusAndDateOnTheCompetition() {
        val g = Games.parse(nflWeek6, League.NFL).first.first { it.id == "401772002" }
        assertTrue(g.live)
        assertEquals("Q3 5:12", g.detail)
        assertEquals(Instant.parse("2026-10-12T00:20:00Z").toEpochMilli(), g.startMs)
        assertEquals("21", g.home.score) // score object -> displayValue
        assertEquals("", g.away.score) // JSON null
        assertNull(g.away.logo)
        assertEquals(0, g.home.rank) // 99 = unranked
        assertEquals(listOf("NBC", "Peacock"), g.broadcasts)
    }

    @Test
    fun missingStateDefaultsToPreAndTimeTbdIsRead() {
        val g = Games.parse(nflWeek6, League.NFL).first.first { it.id == "401772003" }
        assertEquals("pre", g.state)
        assertTrue(g.timeTbd)
        // shortDisplayName missing: short falls back to the display name.
        assertEquals("Buffalo Bills", g.home.short)
        assertEquals("", g.home.location)
        assertEquals(emptyList<String>(), g.broadcasts)
    }

    @Test
    fun weekLabels() {
        fun label(json: String, league: League) = Games.parse(json, league).second
        assertEquals("PLAYOFFS", label("""{"season":{"type":3},"week":{"number":1}}""", League.NFL))
        assertEquals("BOWLS", label("""{"season":{"type":3},"week":{"number":1}}""", League.NCAAF))
        assertEquals("PRESEASON WEEK 2", label("""{"season":{"type":1},"week":{"number":2}}""", League.NFL))
        assertNull(label("""{"season":{"type":2}}""", League.NFL))
        // Daily leagues have no week label.
        assertNull(label("""{"season":{"type":2},"week":{"number":6}}""", League.NBA))
    }

    @Test
    fun noEventsGivesAnEmptyList() {
        val (games, week) = Games.parse("""{"season":{"type":2},"week":{"number":7}}""", League.NFL)
        assertTrue(games.isEmpty())
        assertEquals("WEEK 7", week)
    }

    @Test
    fun collegeRankAndRecord() {
        val json = """
            {"events":[{"id":"1","date":"2026-10-11T19:30Z","competitions":[{"competitors":[
              {"homeAway":"home","curatedRank":{"current":3},
               "team":{"id":"194","displayName":"Ohio State Buckeyes","shortDisplayName":"Ohio State","abbreviation":"OSU","name":"Buckeyes","location":"Ohio State"}},
              {"homeAway":"away","curatedRank":{"current":0},
               "team":{"id":"130","displayName":"Michigan Wolverines","shortDisplayName":"Michigan","abbreviation":"MICH","name":"Wolverines","location":"Michigan"}}
            ]}]}]}
        """.trimIndent()
        val g = Games.parse(json, League.NCAAF).first.single()
        assertEquals(3, g.home.rank)
        assertEquals(0, g.away.rank)
        assertEquals("Ohio State", g.home.location)
    }

    @Test
    fun parseDateFormats() {
        assertEquals(Instant.parse("2026-10-11T17:00:00Z").toEpochMilli(), Games.parseDate("2026-10-11T17:00Z"))
        assertEquals(Instant.parse("2026-10-11T17:00:30Z").toEpochMilli(), Games.parseDate("2026-10-11T17:00:30Z"))
        assertEquals(Instant.parse("2026-10-11T17:00:30.250Z").toEpochMilli(), Games.parseDate("2026-10-11T17:00:30.250Z"))
        assertEquals(0L, Games.parseDate(""))
        assertEquals(0L, Games.parseDate("   "))
        assertEquals(0L, Games.parseDate("next Sunday"))
    }
}
