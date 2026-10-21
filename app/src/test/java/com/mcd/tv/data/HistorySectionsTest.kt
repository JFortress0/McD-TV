package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The History hub's sections on a fixed catalog (HistoryFixtureCatalog). Proves: crime titles such as The
 * Irishman are on no shelf, Ken Burns' documentaries are on Documentaries and Ken Burns, and the hub is
 * built from catalog metadata only, so a profile that has watched nothing sees the same shelves.
 */
class HistorySectionsTest {
    private val cat = HistoryFixtureCatalog
    private val hub = HistoryEras.sections(cat.ALL, cat.KEN_BURNS_CREDITS, cat.TODAY)
    private val shelves = hub.byId()
    private val names = cat.ALL.associate { it.key to it.name }

    private fun onShelves(key: String) = shelves.filterValues { key in it }.keys

    @Test fun crimeTitlesAreOnNoShelf() {
        cat.CRIME.forEach { t ->
            assertEquals("${t.name} is on no shelf", emptySet<String>(), onShelves(t.key))
            assertEquals("${t.name} is left out as crime", "crime", HistoryEras.exclusion(t))
        }
    }

    @Test fun theIrishmanWouldHaveLandedWithoutTheCrimeRule() {
        // Why the rule is needed: History genre, and "john f. kennedy" places it in the Cold War era.
        val irishman = cat.CRIME.first { it.key == "movie:irishman" }
        val r = HistoryEras.classify(irishman)
        assertEquals(listOf(Era.COLD_WAR), r.eras)
        assertTrue(r.presidential)
        assertTrue(HistoryEras.isHistoryProgram(irishman, r))
        assertTrue(HistoryEras.isHistoryMovie(irishman, r))
    }

    @Test fun biopicsAndNonHistoryAreOnNoShelf() {
        cat.NOT_HISTORY.forEach { t -> assertEquals("${t.name} is on no shelf", emptySet<String>(), onShelves(t.key)) }
        listOf("movie:bohemian", "movie:elvis", "movie:fordferrari", "movie:socialnetwork", "movie:amy", "tv:crown").forEach { k ->
            assertEquals(names[k], "biopic", HistoryEras.exclusion(cat.NOT_HISTORY.first { it.key == k }))
        }
    }

    @Test fun kenBurnsDocumentariesAreOnDocumentariesAndKenBurns() {
        val known = listOf("tv:civilwar", "tv:baseball", "tv:vietnam", "tv:thewar", "tv:revolution", "tv:west", "tv:roosevelts", "tv:jazz")
        known.forEach { k ->
            assertTrue("${names[k]} on Documentaries", k in hub.documentaries)
            assertTrue("${names[k]} on Ken Burns", k in hub.kenBurns)
        }
        // They rank high enough to show on the hub's rail (30 tiles), not only in See all.
        known.forEach { k -> assertTrue("${names[k]} in the first 30", hub.documentaries.indexOf(k) in 0 until 30) }
        // A talking-head credit is not his film.
        assertTrue("movie:interview" !in hub.kenBurns)
    }

    @Test fun eachSectionKeepsItsKind() {
        val byKey = cat.ALL.associateBy { it.key }
        hub.documentaries.forEach { k -> assertTrue("${names[k]} is a documentary", 99 in byKey.getValue(k).genres) }
        hub.movies.forEach { k ->
            val t = byKey.getValue(k)
            assertTrue("${t.name} is a movie, not a documentary", t.type == "movie" && 99 !in t.genres)
        }
        assertEquals("movie:youngwashington", hub.movies.first())
        listOf("movie:imitation", "movie:braveheart", "movie:gladiator", "movie:spr", "movie:dunkirk", "movie:hiddenfigures")
            .forEach { k -> assertTrue("${names[k]} on History Movies", k in hub.movies) }
        listOf("tv:bandofbrothers", "tv:johnadams", "tv:vikings", "movie:lincoln", "movie:shallnot")
            .forEach { k -> assertTrue("${names[k]} on History", k in hub.history) }
        assertEquals("tv:civilwar", hub.eras.getValue(Era.CIVIL_WAR).first())
        assertTrue("movie:yuma" in hub.eras.getValue(Era.WEST)) // a western keeps its outlaws
        assertTrue("movie:butchcassidy" in hub.eras.getValue(Era.WEST))
    }

    @Test fun exactSectionContents() {
        val expected = linkedMapOf(
            "docs" to EXPECTED_DOCS, "history" to EXPECTED_HISTORY, "movies" to EXPECTED_MOVIES, "kenburns" to EXPECTED_KEN_BURNS,
            "era:founding" to EXPECTED_FOUNDING, "era:civilwar" to EXPECTED_CIVIL_WAR, "era:worldwars" to EXPECTED_WORLD_WARS,
            "era:coldwar" to EXPECTED_COLD_WAR, "era:west" to EXPECTED_WEST,
        )
        assertEquals(expected, shelves.mapValues { (_, keys) -> keys.map { names.getValue(it) } })
    }

    @Test fun aProfileThatWatchedNothingSeesTheSameShelves() {
        // The section rules take only catalog metadata. Feeding the catalog in another order, as a profile
        // with a different TMDB cache would, gives the same shelves.
        val again = HistoryEras.sections(cat.ALL.reversed(), cat.KEN_BURNS_CREDITS.reversed(), cat.TODAY)
        assertEquals(hub, again)
    }

    @Test fun theHubReadsNoViewingHistory() {
        // Nothing in the hub, its screen or Home's History row may read what a profile watched, liked or favorited.
        val root = File(System.getProperty("jarvis.mainSrc") ?: listOf("src/main/java", "app/src/main/java").first { File(it).isDirectory })
        val files = listOf("com/mcd/tv/data/HistoryHub.kt", "com/mcd/tv/data/HistoryEras.kt", "com/mcd/tv/ui/HistoryScreen.kt").map { File(root, it) }
        val forbidden = listOf(
            "Library.history", "Library.likes", "Library.favorites", "Library.isWatched", "Recommender.moreLikeThis",
            "Recommender.features", "Recommender.forYou", "becauseYouWatched", "moreLike", "eraOrder",
        )
        files.forEach { f ->
            assertTrue("$f exists", f.isFile)
            val code = f.readText()
            forbidden.forEach { w -> assertTrue("${f.name} must not use $w", !code.contains(w)) }
        }
        val home = File(root, "com/mcd/tv/ui/HomeScreen.kt").readText()
        val row = Regex("val historyRow by rememberLoad[^}]*\\}").find(home)?.value ?: ""
        assertTrue("Home's History row found", row.contains("HistoryHub.homeRow()"))
        assertTrue("Home's History row reads no history", listOf("Library", "Recommender").none { row.contains(it) })
    }

    @Test fun printSections() {
        // The shelves as the test catalog builds them, for the pull request.
        shelves.forEach { (id, keys) -> println("[$id] " + keys.joinToString(" | ") { names.getValue(it) }) }
    }

    companion object {
        val EXPECTED_DOCS = listOf(
            "The Vietnam War",
            "The Civil War",
            "The War",
            "The Roosevelts: An Intimate History",
            "The West",
            "The American Revolution",
            "Apollo 11",
            "They Shall Not Grow Old",
            "The Central Park Five",
            "Baseball",
            "Country Music",
            "Jazz",
            "World War II in Colour",
        )
        val EXPECTED_HISTORY = listOf(
            "Saving Private Ryan",
            "Oppenheimer",
            "Hidden Figures",
            "Band of Brothers",
            "Lincoln",
            "The Patriot",
            "Glory",
            "Butch Cassidy and the Sundance Kid",
            "The Vietnam War",
            "The Civil War",
            "The Imitation Game",
            "John Adams",
            "Apollo 11",
            "The War",
            "They Shall Not Grow Old",
            "The Roosevelts: An Intimate History",
            "The West",
            "Braveheart",
            "The American Revolution",
            "Vikings",
            "Young Washington",
            "World War II in Colour",
            "Baseball",
        )
        val EXPECTED_MOVIES = listOf(
            "Young Washington",
            "The Patriot",
            "Lincoln",
            "Glory",
            "Saving Private Ryan",
            "Oppenheimer",
            "Hidden Figures",
            "Dunkirk",
            "Butch Cassidy and the Sundance Kid",
            "The Imitation Game",
            "Gladiator",
            "Braveheart",
        )
        val EXPECTED_KEN_BURNS = listOf(
            "The American Revolution",
            "The Vietnam War",
            "The Civil War",
            "The Central Park Five",
            "The War",
            "Baseball",
            "The Roosevelts: An Intimate History",
            "Country Music",
            "Jazz",
            "The West",
        )
        val EXPECTED_FOUNDING = listOf(
            "Young Washington",
            "The American Revolution",
            "John Adams",
            "The Patriot",
        )
        val EXPECTED_CIVIL_WAR = listOf(
            "The Civil War",
            "Lincoln",
            "Glory",
        )
        val EXPECTED_WORLD_WARS = listOf(
            "The War",
            "Band of Brothers",
            "Saving Private Ryan",
            "Oppenheimer",
            "The Roosevelts: An Intimate History",
            "Dunkirk",
            "The Imitation Game",
            "They Shall Not Grow Old",
            "World War II in Colour",
        )
        val EXPECTED_COLD_WAR = listOf(
            "Oppenheimer",
            "The Vietnam War",
            "Hidden Figures",
            "Apollo 11",
        )
        val EXPECTED_WEST = listOf(
            "The West",
            "Unforgiven",
            "Butch Cassidy and the Sundance Kid",
            "3:10 to Yuma",
        )
    }
}
