package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryErasTest {
    private fun eras(keywords: List<String>, genres: Set<Int> = setOf(18, 36), lang: String = "en", name: String = "Some Title", year: Int = 2001) =
        HistoryEras.classify(name, year, genres, keywords, lang).eras

    // ---------------------------------------------------------------- marquee overrides

    @Test fun marqueeTitlesArePlacedByHand() {
        assertEquals(listOf(Era.FOUNDING), eras(emptyList(), name = "Young Washington", year = 2026))
        assertEquals(listOf(Era.CIVIL_WAR), eras(emptyList(), name = "Lincoln", year = 2012))
        assertEquals(listOf(Era.CIVIL_WAR), eras(emptyList(), name = "The Civil War", year = 1990, genres = setOf(99)))
        assertEquals(listOf(Era.FOUNDING), eras(emptyList(), name = "The American Revolution", year = 2025, genres = setOf(99)))
        // A year apart is the same title (festival vs. release date).
        assertEquals(listOf(Era.CIVIL_WAR), eras(emptyList(), name = "The Conspirator", year = 2010))
        // Punctuation and case do not matter.
        assertEquals(listOf(Era.COLD_WAR), eras(emptyList(), name = "frost nixon", year = 2008))
    }

    @Test fun marqueeNeedsTheRightYear() {
        // Another "Lincoln" or "Glory" is not the marquee one.
        assertTrue(eras(emptyList(), name = "Lincoln", year = 1988).isEmpty())
        assertNull(HistoryEras.marqueeFor("Glory", 0))
        assertNull(HistoryEras.marqueeFor("Manhunt", 2017)) // the Unabomber series, not the Lincoln one
    }

    @Test fun marqueeBeatsGenreVetoAndKeywords() {
        // Oppenheimer spans both shelves, whatever its keywords say.
        assertEquals(listOf(Era.WORLD_WARS, Era.COLD_WAR), eras(listOf("cowboy"), name = "Oppenheimer", year = 2023))
    }

    @Test fun marqueeListIsSound() {
        val keys = HistoryEras.MARQUEE.map { HistoryEras.normName(it.name) + it.year }
        assertEquals("no duplicates", keys.size, keys.toSet().size)
        Era.entries.forEach { e -> assertTrue("$e has marquee titles", HistoryEras.MARQUEE.count { e in it.eras } >= 5) }
        assertEquals("Young Washington", HistoryEras.MARQUEE.first { it.drama }.name)
        assertTrue(HistoryEras.MARQUEE.none { it.drama && it.eras == setOf(Era.WEST) })
    }

    // ---------------------------------------------------------------- keywords

    @Test fun strongKeywordPlacesATitle() {
        assertEquals(Era.CIVIL_WAR, eras(listOf("American Civil War", "1860s", "Union Army")).first())
        assertEquals(listOf(Era.WORLD_WARS), eras(listOf("world war ii", "normandy, france")))
        assertEquals(listOf(Era.COLD_WAR), eras(listOf("vietnam war", "helicopter")))
        assertEquals(listOf(Era.FOUNDING), eras(listOf("george washington", "spy")))
    }

    @Test fun lewisAndClarkIsFoundingAndWest() {
        assertEquals(setOf(Era.FOUNDING, Era.WEST), eras(listOf("lewis and clark expedition"), genres = setOf(99)).toSet())
    }

    @Test fun weakKeywordsAloneNeedAnAmericanPlace() {
        // Marie Antoinette: 1770s, 18th century, but France.
        assertTrue(Era.FOUNDING !in eras(listOf("18th century", "1770s", "france", "queen")))
        assertEquals(listOf(Era.FOUNDING), eras(listOf("1770s", "redcoat", "massachusetts")))
        // Generic "civil war" plus the 1860s, in English: the American one.
        assertEquals(listOf(Era.CIVIL_WAR), eras(listOf("civil war", "1860s")))
        // The Spanish Civil War is not the Civil War era.
        assertTrue(eras(listOf("spanish civil war", "1930s"), lang = "es").isEmpty())
    }

    @Test fun decadesCountOnce() {
        assertTrue(eras(listOf("1950s", "1960s", "1970s")).isEmpty())
        assertEquals(listOf(Era.COLD_WAR), eras(listOf("1960s", "nasa")))
    }

    @Test fun vetoTermsAndExactTerms() {
        assertTrue(eras(listOf("neo-nazi", "skinhead", "1940s")).isEmpty())
        assertTrue(Era.WORLD_WARS !in eras(listOf("holocaust survivor", "1970s")))
        assertEquals(listOf(Era.WORLD_WARS), eras(listOf("holocaust (shoah)")))
    }

    @Test fun modernStoriesThatReachBackStayOff() {
        // National Treasure: a present-day treasure hunt for the founding fathers' clues.
        assertTrue(eras(listOf("treasure hunt", "declaration of independence", "founding fathers", "freemason")).isEmpty())
        // Sahara: a Confederate ironclad, found today.
        assertTrue(eras(listOf("treasure hunter", "confederate army", "africa"), genres = setOf(28, 12)).isEmpty())
        // A comedy about time travel to 1776.
        assertTrue(eras(listOf("time travel", "american revolution"), genres = setOf(35)).isEmpty())
    }

    @Test fun comediesNeedMoreThanAPassingMention() {
        assertTrue(eras(listOf("abraham lincoln", "fashion model"), genres = setOf(35)).isEmpty())
        assertEquals(listOf(Era.WEST), eras(listOf("wild west", "cowboy", "gunslinger"), genres = setOf(35, 37)))
        // A comedy-drama counts like any drama.
        assertEquals(listOf(Era.CIVIL_WAR), eras(listOf("abraham lincoln"), genres = setOf(35, 18)))
    }

    @Test fun frenchRevolutionIsNotTheAmericanOne() {
        // Napoleon (1927): "french revolutionary wars" contains "revolutionary war".
        assertTrue(Era.FOUNDING !in eras(listOf("french revolutionary wars", "napoleon bonaparte", "18th century", "1790s"), lang = "fr"))
        assertEquals(listOf(Era.FOUNDING), eras(listOf("revolutionary war")))
    }

    @Test fun westernsNeedThePeriodNotJustTheGenre() {
        assertEquals(listOf(Era.WEST), eras(listOf("cowboy", "horse"), genres = setOf(37)))
        assertTrue(eras(listOf("neo-western", "texas", "bank robbery"), genres = setOf(37, 80)).isEmpty())
        assertTrue(eras(listOf("cowboy", "rodeo"), genres = setOf(18)).isEmpty())
        assertEquals(listOf(Era.WEST), eras(listOf("wild west", "gunslinger"), genres = setOf(28)))
    }

    @Test fun fantasyHorrorAndCartoonsNeverQualify() {
        // Abraham Lincoln: Vampire Hunter.
        assertTrue(eras(listOf("abraham lincoln", "vampire", "american civil war"), genres = setOf(14, 27, 28)).isEmpty())
        assertTrue(eras(listOf("world war ii"), genres = setOf(16, 10751)).isEmpty())
    }

    @Test fun keywordNormalization() {
        assertEquals("u s president", HistoryEras.norm("U.S. President"))
        assertEquals("d day", HistoryEras.norm("D-Day"))
        assertEquals("holocaust shoah", HistoryEras.norm("Holocaust (Shoah)"))
    }

    // ---------------------------------------------------------------- shelves

    @Test fun historicalDramas() {
        val lincoln = HistoryEras.classify("X", 2001, setOf(18, 36), listOf("american civil war"))
        assertTrue(HistoryEras.isHistoricalDrama(setOf(18, 36), lincoln))
        // Documentaries belong on Ken Burns and the era shelves, not here.
        assertFalse(HistoryEras.isHistoricalDrama(setOf(99, 36), lincoln))
        // A western on its own is not one of Young Washington's kin.
        val western = HistoryEras.classify("X", 2001, setOf(37, 18), listOf("wild west"))
        assertFalse(HistoryEras.isHistoricalDrama(setOf(37, 18), western))
        // Presidential story with the History genre: yes. A White House action thriller: no.
        val president = HistoryEras.classify("X", 2001, setOf(36, 18), listOf("president of the united states"))
        assertTrue(president.presidential)
        assertTrue(HistoryEras.isHistoricalDrama(setOf(36, 18), president))
        val thriller = HistoryEras.classify("X", 2001, setOf(28, 53), listOf("white house", "secret service"))
        assertFalse(thriller.presidential)
        assertFalse(HistoryEras.isHistoricalDrama(setOf(28, 53), thriller))
        // A marquee drama always qualifies.
        assertTrue(HistoryEras.isHistoricalDrama(emptySet(), EraResult.NONE, marqueeDrama = true))
    }

    @Test fun historyTitlesForTheSeed() {
        val civilWar = HistoryEras.classify("The Civil War", 1990, setOf(99), emptyList())
        assertTrue(HistoryEras.isHistoryTitle(setOf(99), civilWar))
        assertTrue(HistoryEras.isHistoryTitle(setOf(36, 18), EraResult.NONE))
        assertTrue(HistoryEras.isHistoryTitle(setOf(99), EraResult.NONE, inKenBurns = true))
        // A poker documentary is not history.
        assertFalse(HistoryEras.isHistoryTitle(setOf(99), HistoryEras.classify("All In", 2009, setOf(99), listOf("poker"))))
        // A comedy set in the 1960s is not either.
        assertFalse(HistoryEras.isHistoryTitle(setOf(35), HistoryEras.classify("X", 2001, setOf(35), listOf("vietnam war"))))
    }

    @Test fun rankPrefersEraFitAmericanAndVotes() {
        assertTrue(HistoryEras.rank(4, 5000, 7.5, true) > HistoryEras.rank(4, 5000, 7.5, false))
        assertTrue(HistoryEras.rank(4, 5000, 7.5, false) > HistoryEras.rank(2, 5000, 7.5, false))
        assertTrue(HistoryEras.rank(2, 50000, 7.5, false) > HistoryEras.rank(2, 500, 7.5, false))
    }

    @Test fun erasOrderFollowsWhatWasWatched() {
        assertEquals(Era.entries.toList(), HistoryEras.orderEras(emptyMap()))
        assertEquals(
            listOf(Era.WEST, Era.CIVIL_WAR, Era.FOUNDING, Era.WORLD_WARS, Era.COLD_WAR),
            HistoryEras.orderEras(mapOf(Era.WEST to 3, Era.CIVIL_WAR to 1)),
        )
    }

    // ---------------------------------------------------------------- Ken Burns

    private fun credit(key: String, date: String, jobs: List<String>, votes: Int, genres: Set<Int> = setOf(99)) =
        Credit(key, key, date, genres, jobs, votes)

    @Test fun kenBurnsShelfOrder() {
        val credits = listOf(
            credit("tv:baseball", "1994-09-18", listOf("Director", "Writer"), 200),
            credit("tv:west", "1996-09-15", listOf("Executive Producer"), 100),
            credit("tv:civilwar", "1990-09-23", listOf("Director", "Producer"), 300),
            credit("tv:revolution", "2025-11-16", listOf("Director"), 20),
            credit("tv:cancer", "2015-03-30", listOf("Executive Producer"), 30),
            credit("movie:interview", "2010-01-01", listOf("Self"), 999), // a talking head in someone else's film
            credit("movie:drama", "2012-01-01", listOf("Producer"), 999, genres = setOf(18)), // not a documentary
            credit("tv:next", "2027-04-01", listOf("Director"), 0),
            credit("tv:civilwar", "1990-09-23", listOf("Director"), 300), // listed twice
        )
        assertEquals(
            listOf("tv:revolution", "tv:civilwar", "tv:baseball", "tv:west", "tv:cancer", "tv:next"),
            HistoryEras.kenBurnsOrder(credits, "2026-10-20"),
        )
    }

    @Test fun kenBurnsFilmListedTwiceShowsOnce() {
        val credits = listOf(
            Credit("movie:1", "Henry David Thoreau", "2026-03-30", setOf(99), listOf("Executive Producer"), 3),
            Credit("tv:2", "Henry David Thoreau", "2026-03-30", setOf(99), listOf("Executive Producer"), 9),
            Credit("tv:3", "The Civil War", "1990-09-23", setOf(99), listOf("Director"), 300),
        )
        assertEquals(listOf("tv:2", "tv:3"), HistoryEras.kenBurnsOrder(credits, "2026-10-20"))
    }

    @Test fun kenBurnsRecentWindowIsEighteenMonths() {
        val credits = listOf(credit("a", "2025-03-01", listOf("Director"), 1), credit("b", "1990-01-01", listOf("Director"), 500))
        // March 2025 is more than 18 months before October 2026: ordered by votes like the rest.
        assertEquals(listOf("b", "a"), HistoryEras.kenBurnsOrder(credits, "2026-10-20"))
        assertEquals("2025-04-20", HistoryEras.monthsBefore("2026-10-20", 18))
        assertEquals("2025-12-05", HistoryEras.monthsBefore("2026-01-05", 1))
    }

    // ---------------------------------------------------------------- personal row

    @Test fun seedIsTheNewestHistoryTitleTheProfileGotInto() {
        val watched = listOf(
            Watched("movie:comedy", "A Comedy", 1f, true, 500),
            Watched("tv:civilwar", "The Civil War", 0.4f, false, 400),
            Watched("movie:lincoln", "Lincoln", 1f, true, 300),
            Watched("movie:glory", "Glory", 0.1f, false, 600), // barely started
        )
        val history = setOf("tv:civilwar", "movie:lincoln", "movie:glory")
        assertEquals("The Civil War", HistoryEras.pickSeed(watched) { it in history }?.name)
        assertNull(HistoryEras.pickSeed(watched) { false })
    }
}
