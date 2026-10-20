package com.mcd.tv.data

// History hub rules: which era a title depicts, the curated marquee titles, the Ken Burns shelf order,
// what counts as a historical drama, and which watched title seeds the personal row.
// Pure Kotlin (no Android, no Prefs, no network), so every rule is unit tested on the JVM.
// HistoryHub.kt gathers the data (TMDB, Library) and calls into this file.

/** The era shelves. Era = the period a title depicts (its plot keywords), never its release year. */
enum class Era(val id: String, val label: String) {
    FOUNDING("founding", "Revolutionary & Founding"),
    CIVIL_WAR("civilwar", "Civil War Era"),
    WORLD_WARS("worldwars", "World War I & II"),
    COLD_WAR("coldwar", "Cold War & Vietnam"),
    WEST("west", "The American West"),
    ;

    companion object {
        fun fromId(id: String): Era? = entries.firstOrNull { it.id == id }
    }
}

/** What the hub knows about one title after classification. [scores]: points per era in [eras], best first. */
data class EraResult(val eras: List<Era>, val presidential: Boolean, val scores: Map<Era, Int> = emptyMap()) {
    /** Points for the best era (marquee titles count as a sure fit). */
    val best: Int get() = eras.firstOrNull()?.let { scores[it] } ?: 0

    companion object {
        val NONE = EraResult(emptyList(), false)
    }
}

/**
 * A title the hub always places by hand (accuracy beats keyword guessing for the famous ones).
 * [drama] = it leads the Historical Dramas shelf. Matched by name and year (within a year).
 */
data class Marquee(val type: String, val name: String, val year: Int, val eras: Set<Era>, val drama: Boolean = false)

/** One crew credit of a person, as the Ken Burns shelf needs it. [date] is "yyyy-MM-dd" or "". */
data class Credit(
    val key: String,
    val name: String,
    val date: String,
    val genres: Set<Int>,
    val jobs: List<String>,
    val votes: Int,
)

/** One watched title, newest first, for picking the personal row's seed. */
data class Watched(val key: String, val name: String, val progress: Float, val finished: Boolean, val at: Long)

object HistoryEras {
    const val HISTORY = 36
    const val DOCUMENTARY = 99
    const val DRAMA = 18
    const val WAR = 10752
    const val WAR_POLITICS = 10768
    const val WESTERN = 37
    const val COMEDY = 35

    /** A title needs this many points for an era. A strong keyword alone is enough; weak ones need company. */
    const val MIN_SCORE = 2

    /** Points a hand-placed marquee title gets for its eras. */
    const val MARQUEE_SCORE = 6

    /** Fantasy, sci-fi, horror, animation and kids titles never land on an era shelf by keywords. */
    private val VETO_GENRES = setOf(14, 878, 27, 16, 10765, 10762)

    /**
     * Stories set today that only reach back to the past (a treasure hunt for a founding father's clue, a film
     * crew shooting a Revolution picture, time travel) never land on an era shelf.
     */
    private val MODERN_FRAME = listOf(
        "treasure hunt", "treasure hunter", "time travel", "time traveler", "time machine", "filmmaking",
        "film within a film", "film making", "historical reenactment", "present day",
    )

    /**
     * Keyword rules per era. Terms are TMDB keyword names, normalized (see [norm]). A term matches a keyword
     * that equals it or contains it as whole words; a term starting with "=" must equal the keyword.
     * strong = 2 points, weak = 1 point, decades = 1 point at most in total, veto = never this era.
     * [placeNeeded]: a title with only weak points also needs an American place (or, for [englishIsPlace],
     * an English-language title), so a French 1770s court drama never lands on the founding shelf.
     */
    private class Rules(
        val strong: List<String>,
        val weak: List<String>,
        val decades: List<String>,
        val veto: List<String> = emptyList(),
        val placeNeeded: Boolean = false,
        val englishIsPlace: Boolean = false,
    )

    private val RULES: Map<Era, Rules> = mapOf(
        Era.FOUNDING to Rules(
            strong = listOf(
                "american revolution", "american revolutionary war", "=revolutionary war", "george washington", "john adams",
                "thomas jefferson", "benjamin franklin", "alexander hamilton", "founding fathers", "declaration of independence",
                "continental army", "continental congress", "valley forge", "boston tea party", "colonial america",
                "thirteen colonies", "lewis and clark",
            ),
            weak = listOf("18th century", "redcoat", "redcoats", "loyalist"),
            decades = listOf("1760s", "1770s", "1780s", "1790s", "1775", "1776", "1777", "1781"),
            veto = listOf("french revolution", "french revolutionary wars", "napoleon", "napoleon bonaparte", "napoleonic wars"),
            placeNeeded = true,
        ),
        Era.CIVIL_WAR to Rules(
            strong = listOf(
                "american civil war", "confederate army", "confederate soldier", "confederacy", "confederate states of america",
                "union army", "union soldier", "abraham lincoln", "battle of gettysburg", "gettysburg", "ulysses s grant",
                "robert e lee", "stonewall jackson", "emancipation proclamation", "reconstruction era", "john wilkes booth",
                "harriet tubman", "underground railroad", "fort sumter", "antietam",
            ),
            weak = listOf("civil war", "abolitionist", "abolitionism", "abolition of slavery"),
            decades = listOf("1850s", "1860s", "1861", "1862", "1863", "1864", "1865"),
            placeNeeded = true,
            englishIsPlace = true,
        ),
        Era.WORLD_WARS to Rules(
            strong = listOf(
                "world war ii", "world war i", "second world war", "first world war", "d day", "normandy invasion",
                "invasion of normandy", "omaha beach", "pearl harbor", "attack on pearl harbor", "battle of the bulge",
                "iwo jima", "battle of okinawa", "battle of midway", "guadalcanal", "=holocaust", "=holocaust shoah",
                "=the holocaust", "manhattan project", "trench warfare", "western front", "dunkirk", "dunkirk evacuation",
                "the blitz", "battle of britain", "winston churchill", "franklin d roosevelt", "eighth air force", "u boat",
                "luftwaffe", "wehrmacht", "waffen ss", "nazi occupation",
            ),
            weak = listOf("nazi", "nazis", "nazi germany", "adolf hitler", "third reich", "paratrooper", "concentration camp"),
            decades = listOf("1910s", "1940s", "1914", "1915", "1916", "1917", "1918", "1939", "1940", "1941", "1942", "1943", "1944", "1945"),
            veto = listOf("neo nazi", "nazi hunter"),
        ),
        Era.COLD_WAR to Rules(
            strong = listOf(
                "vietnam war", "cold war", "korean war", "cuban missile crisis", "space race", "apollo program", "apollo 11",
                "apollo 13", "moon landing", "john f kennedy", "assassination of john f kennedy", "kennedy assassination",
                "watergate", "watergate scandal", "richard nixon", "lyndon b johnson", "mccarthyism", "red scare", "berlin wall",
                "bay of pigs", "bay of pigs invasion", "pentagon papers", "iron curtain", "tet offensive",
            ),
            weak = listOf("soviet union", "kgb", "nasa", "astronaut", "anti war protest"),
            decades = listOf("1950s", "1960s", "1970s", "1980s"),
        ),
        Era.WEST to Rules(
            strong = listOf(
                "old west", "wild west", "american old west", "american frontier", "oregon trail", "cattle drive", "gold rush",
                "indian wars", "american indian wars", "transcontinental railroad", "pony express", "wyatt earp", "doc holliday",
                "billy the kid", "jesse james", "butch cassidy", "george armstrong custer", "little bighorn", "wagon train",
                "lewis and clark", "o k corral", "stagecoach",
            ),
            weak = listOf("cowboy", "frontier", "gunslinger", "outlaw", "settler", "native american", "comanche", "sioux", "cherokee", "saloon", "homestead", "cavalry"),
            decades = listOf("1840s", "1870s", "1880s", "1890s", "19th century"),
            veto = listOf("neo western", "contemporary western", "modern western", "space western", "weird west"),
            placeNeeded = true,
        ),
    )

    /** Keywords that put a title in the United States (for weak-only matches). */
    private val AMERICAN_PLACES = listOf(
        "usa", "united states", "america", "american history", "u s history", "virginia", "massachusetts", "boston",
        "philadelphia", "pennsylvania", "new york", "georgia usa", "south carolina", "north carolina", "tennessee", "kentucky",
        "maryland", "texas", "new mexico", "arizona", "colorado", "montana", "wyoming", "dakota", "kansas", "oklahoma",
        "nebraska", "california", "oregon", "washington d c", "hawaii", "missouri", "mississippi", "alabama", "louisiana",
    )

    /** Keywords that make a historical title a presidential story. */
    private val PRESIDENTIAL = listOf(
        "president of the united states", "u s president", "us president", "american president", "presidential election",
        "george washington", "john adams", "thomas jefferson", "abraham lincoln", "theodore roosevelt", "franklin d roosevelt",
        "harry s truman", "dwight d eisenhower", "john f kennedy", "lyndon b johnson", "richard nixon",
    )

    /** TMDB keyword names to discover each era's titles with (the most specific strong terms). */
    val DISCOVER_TERMS: Map<Era, List<String>> = mapOf(
        Era.FOUNDING to listOf("american revolution", "revolutionary war", "george washington", "founding fathers", "colonial america"),
        Era.CIVIL_WAR to listOf("american civil war", "abraham lincoln", "confederate army", "underground railroad"),
        Era.WORLD_WARS to listOf("world war ii", "world war i", "d-day", "pearl harbor", "holocaust (shoah)"),
        Era.COLD_WAR to listOf("vietnam war", "cold war", "korean war", "cuban missile crisis", "space race"),
        Era.WEST to listOf("old west", "wild west", "american frontier", "gold rush", "cattle drive"),
    )

    /** Extra discover terms for the Historical Dramas shelf (presidential stories). */
    val PRESIDENTIAL_TERMS = listOf("president of the united states", "u.s. president")

    // ---------------------------------------------------------------- marquee titles

    /**
     * Famous titles placed by hand, era by era in story order. They lead their era shelves; the ones marked
     * drama lead Historical Dramas, oldest story first, so Young Washington opens that shelf.
     */
    val MARQUEE: List<Marquee> = listOf(
        // Revolutionary & Founding
        Marquee("movie", "Young Washington", 2026, setOf(Era.FOUNDING), drama = true),
        Marquee("tv", "The American Revolution", 2025, setOf(Era.FOUNDING)),
        Marquee("tv", "John Adams", 2008, setOf(Era.FOUNDING), drama = true),
        Marquee("tv", "TURN: Washington's Spies", 2014, setOf(Era.FOUNDING), drama = true),
        Marquee("movie", "The Patriot", 2000, setOf(Era.FOUNDING), drama = true),
        Marquee("movie", "Hamilton", 2020, setOf(Era.FOUNDING), drama = true),
        Marquee("tv", "Sons of Liberty", 2015, setOf(Era.FOUNDING), drama = true),
        Marquee("movie", "1776", 1972, setOf(Era.FOUNDING), drama = true),
        Marquee("tv", "Washington", 2020, setOf(Era.FOUNDING)),
        Marquee("tv", "Benjamin Franklin", 2022, setOf(Era.FOUNDING)),
        Marquee("movie", "Lewis & Clark: The Journey of the Corps of Discovery", 1997, setOf(Era.FOUNDING, Era.WEST)),
        // Civil War era
        Marquee("tv", "The Civil War", 1990, setOf(Era.CIVIL_WAR)),
        Marquee("movie", "Lincoln", 2012, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Glory", 1989, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Gettysburg", 1993, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("tv", "Manhunt", 2024, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Harriet", 2019, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Free State of Jones", 2016, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Gods and Generals", 2003, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "The Conspirator", 2011, setOf(Era.CIVIL_WAR), drama = true),
        Marquee("movie", "Cold Mountain", 2003, setOf(Era.CIVIL_WAR)),
        // World War I & II
        Marquee("tv", "The War", 2007, setOf(Era.WORLD_WARS)),
        Marquee("tv", "Band of Brothers", 2001, setOf(Era.WORLD_WARS), drama = true),
        Marquee("movie", "Saving Private Ryan", 1998, setOf(Era.WORLD_WARS), drama = true),
        Marquee("tv", "The Pacific", 2010, setOf(Era.WORLD_WARS), drama = true),
        Marquee("tv", "Masters of the Air", 2024, setOf(Era.WORLD_WARS), drama = true),
        Marquee("movie", "Oppenheimer", 2023, setOf(Era.WORLD_WARS, Era.COLD_WAR), drama = true),
        Marquee("movie", "1917", 2019, setOf(Era.WORLD_WARS), drama = true),
        Marquee("movie", "Hacksaw Ridge", 2016, setOf(Era.WORLD_WARS), drama = true),
        Marquee("movie", "Greyhound", 2020, setOf(Era.WORLD_WARS)),
        Marquee("movie", "Darkest Hour", 2017, setOf(Era.WORLD_WARS), drama = true),
        Marquee("tv", "The Roosevelts: An Intimate History", 2014, setOf(Era.WORLD_WARS)),
        Marquee("tv", "The U.S. and the Holocaust", 2022, setOf(Era.WORLD_WARS)),
        // Cold War & Vietnam
        Marquee("tv", "The Vietnam War", 2017, setOf(Era.COLD_WAR)),
        Marquee("movie", "Thirteen Days", 2000, setOf(Era.COLD_WAR), drama = true),
        Marquee("movie", "Bridge of Spies", 2015, setOf(Era.COLD_WAR), drama = true),
        Marquee("movie", "The Right Stuff", 1983, setOf(Era.COLD_WAR)),
        Marquee("movie", "Hidden Figures", 2016, setOf(Era.COLD_WAR)),
        Marquee("movie", "Apollo 13", 1995, setOf(Era.COLD_WAR)),
        Marquee("movie", "We Were Soldiers", 2002, setOf(Era.COLD_WAR), drama = true),
        Marquee("movie", "Platoon", 1986, setOf(Era.COLD_WAR)),
        Marquee("movie", "JFK", 1991, setOf(Era.COLD_WAR), drama = true),
        Marquee("movie", "The Post", 2017, setOf(Era.COLD_WAR), drama = true),
        Marquee("movie", "Frost/Nixon", 2008, setOf(Era.COLD_WAR), drama = true),
        // The American West
        Marquee("tv", "The West", 1996, setOf(Era.WEST)),
        Marquee("tv", "Lonesome Dove", 1989, setOf(Era.WEST)),
        Marquee("movie", "Dances with Wolves", 1990, setOf(Era.WEST)),
        Marquee("movie", "Tombstone", 1993, setOf(Era.WEST)),
        Marquee("movie", "Unforgiven", 1992, setOf(Era.WEST)),
        Marquee("tv", "1883", 2021, setOf(Era.WEST)),
        Marquee("movie", "True Grit", 2010, setOf(Era.WEST)),
        Marquee("movie", "The Revenant", 2015, setOf(Era.WEST)),
        Marquee("movie", "Butch Cassidy and the Sundance Kid", 1969, setOf(Era.WEST)),
        Marquee("tv", "The American Buffalo", 2023, setOf(Era.WEST)),
    )

    /** Letters and digits only, lower case ("Frost/Nixon" = "frostnixon"). For matching names. */
    fun normName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** The marquee entry for a title, by name and year (a year apart is allowed: festival vs. release). */
    fun marqueeFor(name: String, year: Int): Marquee? {
        if (year <= 0) return null // "Lincoln", "Glory" and "1917" need a year to be sure
        val n = normName(name)
        return MARQUEE.firstOrNull { normName(it.name) == n && kotlin.math.abs(it.year - year) <= 1 }
    }

    // ---------------------------------------------------------------- classification

    /** TMDB keyword name, normalized: lower case, words split on anything that is not a letter or digit. */
    fun norm(keyword: String): String = keyword.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun matches(kw: String, term: String): Boolean =
        if (term.startsWith("=")) kw == term.substring(1) else kw == term || " $kw ".contains(" $term ")

    private fun anyMatch(kws: List<String>, terms: List<String>) = kws.any { k -> terms.any { matches(k, it) } }

    /** Points for one era (0 when vetoed). [kws] are normalized keyword names. */
    internal fun score(era: Era, kws: List<String>, genres: Set<Int>, language: String): Int {
        val r = RULES.getValue(era)
        if (anyMatch(kws, r.veto) || anyMatch(kws, MODERN_FRAME)) return 0
        val strong = kws.count { k -> r.strong.any { matches(k, it) } }
        val weak = kws.count { k -> r.weak.any { matches(k, it) } } +
            (if (anyMatch(kws, r.decades)) 1 else 0) +
            (if (era == Era.WEST && WESTERN in genres) 1 else 0)
        if (strong == 0 && r.placeNeeded) {
            val place = anyMatch(kws, AMERICAN_PLACES) ||
                (era == Era.WEST && WESTERN in genres) ||
                (r.englishIsPlace && language == "en")
            if (!place) return 0
        }
        return strong * 2 + weak
    }

    /**
     * The eras a title depicts, best match first, and whether it is a presidential story.
     * A marquee title is placed by hand. Otherwise its plot keywords decide; fantasy, sci-fi, horror,
     * animation and kids titles never qualify.
     */
    fun classify(name: String, year: Int, genres: Set<Int>, keywords: List<String>, language: String = ""): EraResult {
        val kws = keywords.map(::norm).filter { it.isNotEmpty() }
        val presidential = anyMatch(kws, PRESIDENTIAL)
        marqueeFor(name, year)?.let { m ->
            val eras = Era.entries.filter { it in m.eras }
            return EraResult(eras, presidential, eras.associateWith { MARQUEE_SCORE })
        }
        if (genres.any { it in VETO_GENRES }) return EraResult(emptyList(), presidential)
        // A comedy with no History, War or Drama genre needs twice the points: one passing mention (Zoolander) is
        // not a period piece, a comedy set in the era (A Million Ways to Die in the West) still is.
        val comedyOnly = COMEDY in genres && genres.none { it == HISTORY || it == WAR || it == WAR_POLITICS || it == DRAMA }
        val need = if (comedyOnly) MIN_SCORE * 2 else MIN_SCORE
        val scored = Era.entries.map { it to score(it, kws, genres, language) }.filter { it.second >= need }
            .sortedByDescending { it.second }
        return EraResult(scored.map { it.first }, presidential, scored.toMap())
    }

    /**
     * Historical Dramas: biopics and period dramas set in one of the eras (not the West on its own) or about
     * a president. Never documentaries; must carry a History, War or Drama genre.
     */
    fun isHistoricalDrama(genres: Set<Int>, result: EraResult, marqueeDrama: Boolean = false): Boolean {
        if (marqueeDrama) return true
        if (DOCUMENTARY in genres || genres.any { it in VETO_GENRES }) return false
        val dramaGenre = genres.any { it == HISTORY || it == WAR || it == WAR_POLITICS || it == DRAMA }
        val period = result.eras.any { it != Era.WEST }
        return dramaGenre && (period || (result.presidential && HISTORY in genres))
    }

    /**
     * A history title, for the personal row's seed: History genre, or a war story or documentary that
     * depicts one of the eras, or anything from the Ken Burns shelf.
     */
    fun isHistoryTitle(genres: Set<Int>, result: EraResult, inKenBurns: Boolean = false): Boolean =
        inKenBurns || HISTORY in genres ||
            (result.eras.isNotEmpty() && genres.any { it == DOCUMENTARY || it == WAR || it == WAR_POLITICS })

    /** Within an era shelf (after the marquee titles): era fit, then American-made, votes and rating. */
    fun rank(eraScore: Int, votes: Int, rating: Double, american: Boolean): Double =
        minOf(eraScore, 6) * 0.4 + kotlin.math.log10(votes + 1.0) + rating / 5.0 + (if (american) 1.0 else 0.0)

    /** Era shelves in the order to show: eras the profile watched most first, the rest in story order. */
    fun orderEras(watched: Map<Era, Int>): List<Era> =
        Era.entries.sortedWith(compareByDescending<Era> { watched[it] ?: 0 }.thenBy { it.ordinal })

    // ---------------------------------------------------------------- Ken Burns

    private val DIRECTED = setOf("Director", "Co-Director", "Creator", "Series Director")
    private val KEN_BURNS_JOBS = DIRECTED + setOf("Writer", "Producer", "Executive Producer")

    /**
     * The Ken Burns shelf from his real crew credits: every documentary he directed, created, wrote or
     * produced (acting credits, such as interviews in other films, are left out; a film listed twice shows
     * once). Order: anything released
     * in the last 18 months first (newest first), then films he directed before ones he only produced, each
     * by how many people rated them. Unreleased titles go last. [today] = "yyyy-MM-dd".
     */
    fun kenBurnsOrder(credits: List<Credit>, today: String): List<String> {
        val recentFrom = monthsBefore(today, 18)
        val kept = credits
            .filter { c -> c.jobs.any { it in KEN_BURNS_JOBS } && (c.genres.isEmpty() || DOCUMENTARY in c.genres) }
            .distinctBy { it.key }
            // TMDB sometimes lists one film twice (as a movie and as a series): keep the better known one.
            .groupBy { normName(it.name) + "|" + it.date.take(4) }.values.map { same -> same.maxByOrNull { it.votes }!! }
        fun released(c: Credit) = c.date.isNotBlank() && c.date <= today
        val (out, upcoming) = kept.partition(::released)
        val (recent, older) = out.partition { it.date >= recentFrom }
        return recent.sortedByDescending { it.date }.map { it.key } +
            older.sortedWith(compareBy<Credit> { c -> if (c.jobs.any { it in DIRECTED }) 0 else 1 }.thenByDescending { it.votes }).map { it.key } +
            upcoming.sortedBy { it.date.ifBlank { "9999" } }.map { it.key }
    }

    /** "yyyy-MM-dd" [months] earlier (day kept; plain text compare is enough for ordering). */
    internal fun monthsBefore(date: String, months: Int): String {
        val y = date.take(4).toIntOrNull() ?: return ""
        val m = date.drop(5).take(2).toIntOrNull() ?: 1
        val total = y * 12 + (m - 1) - months
        return String.format(java.util.Locale.US, "%04d-%02d-%s", total / 12, total % 12 + 1, date.drop(8).ifBlank { "01" })
    }

    // ---------------------------------------------------------------- personal row

    /**
     * The watched title that seeds "More Like …": the newest one the profile got into (a quarter watched or
     * finished) that [isHistory] accepts. Null when the profile has watched no history yet.
     */
    fun pickSeed(watched: List<Watched>, isHistory: (String) -> Boolean): Watched? =
        watched.filter { it.finished || it.progress >= 0.25f }.sortedByDescending { it.at }.firstOrNull { isHistory(it.key) }
}
