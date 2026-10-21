package com.mcd.tv.data

/**
 * A fixed catalog for the History hub tests: real titles with their TMDB genre ids and typical TMDB plot
 * keywords, transcribed by hand (tests never call TMDB). Keys are readable stand-ins, not TMDB ids.
 * TMDB genres: 12 Adventure, 18 Drama, 28 Action, 36 History, 37 Western, 53 Thriller, 80 Crime,
 * 99 Documentary, 10402 Music, 10749 Romance, 10752 War, 10759 Action & Adventure (TV), 10768 War & Politics (TV),
 * 14 Fantasy, 27 Horror, 9648 Mystery.
 */
object HistoryFixtureCatalog {
    private fun t(
        key: String, name: String, year: Int, genres: Set<Int>, keywords: List<String>,
        votes: Int = 1000, rating: Double = 7.5, kenBurns: Boolean = false,
    ) = CatalogTitle(key, name, year, genres, keywords, "en", votes, rating, true, kenBurns)

    // ---------------------------------------------------------------- Ken Burns (on his crew credits)

    val KEN_BURNS = listOf(
        t("tv:civilwar", "The Civil War", 1990, setOf(99, 10768), listOf("american civil war", "abraham lincoln", "19th century", "miniseries"), 310, 8.7, true),
        t("tv:baseball", "Baseball", 1994, setOf(99), listOf("baseball", "sports history", "american history"), 120, 8.4, true),
        t("tv:vietnam", "The Vietnam War", 2017, setOf(99, 10768), listOf("vietnam war", "cold war", "archive footage"), 400, 8.8, true),
        t("tv:thewar", "The War", 2007, setOf(99, 10768), listOf("world war ii", "home front"), 160, 8.6, true),
        t("tv:revolution", "The American Revolution", 2025, setOf(99, 10768), listOf("american revolution", "george washington"), 25, 8.5, true),
        t("tv:west", "The West", 1996, setOf(99, 37), listOf("old west", "american frontier"), 60, 8.2, true),
        t("tv:roosevelts", "The Roosevelts: An Intimate History", 2014, setOf(99), listOf("franklin d. roosevelt", "theodore roosevelt", "president of the united states"), 70, 8.3, true),
        t("tv:jazz", "Jazz", 2001, setOf(99), listOf("jazz", "music history"), 40, 8.4, true),
        t("tv:countrymusic", "Country Music", 2019, setOf(99), listOf("country music", "musician"), 50, 8.6, true),
        // A Ken Burns documentary TMDB also files under Crime: his films are history by editorial rule.
        t("movie:centralpark", "The Central Park Five", 2012, setOf(99, 80), listOf("wrongful conviction", "new york city", "1980s"), 200, 7.6, true),
    )

    /** Ken Burns' crew credits, as TMDB lists them (the shelf keeps directing, writing and producing). */
    val KEN_BURNS_CREDITS = listOf(
        Credit("tv:civilwar", "The Civil War", "1990-09-23", setOf(99, 10768), listOf("Director", "Producer"), 310),
        Credit("tv:baseball", "Baseball", "1994-09-18", setOf(99), listOf("Director", "Writer"), 120),
        Credit("tv:vietnam", "The Vietnam War", "2017-09-17", setOf(99, 10768), listOf("Director"), 400),
        Credit("tv:thewar", "The War", "2007-09-23", setOf(99, 10768), listOf("Director"), 160),
        Credit("tv:revolution", "The American Revolution", "2025-11-16", setOf(99, 10768), listOf("Director"), 25),
        Credit("tv:west", "The West", "1996-09-15", setOf(99, 37), listOf("Executive Producer"), 60),
        Credit("tv:roosevelts", "The Roosevelts: An Intimate History", "2014-09-14", setOf(99), listOf("Director"), 70),
        Credit("tv:jazz", "Jazz", "2001-01-08", setOf(99), listOf("Director"), 40),
        Credit("tv:countrymusic", "Country Music", "2019-09-15", setOf(99), listOf("Director"), 50),
        Credit("movie:centralpark", "The Central Park Five", "2012-11-23", setOf(99, 80), listOf("Director", "Writer"), 200),
        Credit("movie:interview", "Some Interview", "2010-01-01", setOf(99), listOf("Self"), 999), // a talking head: not his film
    )

    // ---------------------------------------------------------------- crime: never in the hub

    val CRIME = listOf(
        // The complaint: Crime, Drama, History on TMDB, and its keywords name JFK (a Cold War term).
        t("movie:irishman", "The Irishman", 2019, setOf(80, 18, 36), listOf(
            "biography", "mafia", "hitman", "organized crime", "labor union", "mobster", "1950s", "1960s", "1970s",
            "jimmy hoffa", "john f. kennedy", "based on novel or book",
        ), 7800, 7.6),
        t("movie:goodfellas", "GoodFellas", 1990, setOf(18, 80), listOf("biography", "mafia", "gangster", "1950s", "1960s", "1970s", "new york city"), 12000, 8.5),
        t("movie:godfather", "The Godfather", 1972, setOf(18, 80), listOf("mafia", "crime family", "1940s", "new york city", "italian american"), 20000, 8.7),
        t("movie:casino", "Casino", 1995, setOf(80, 18), listOf("las vegas", "mafia", "1970s", "biography"), 6000, 8.0),
        t("movie:publicenemies", "Public Enemies", 2009, setOf(80, 18, 36), listOf("biography", "bank robber", "great depression", "1930s", "fbi", "john dillinger"), 4500, 6.6),
        // "vietnam war" would put it on the Cold War shelf; Crime keeps it off.
        t("movie:americangangster", "American Gangster", 2007, setOf(80, 18), listOf("biography", "drug lord", "vietnam war", "1970s", "harlem"), 6500, 7.5),
        t("movie:blackmass", "Black Mass", 2015, setOf(80, 18), listOf("biography", "boston", "organized crime", "fbi informant", "1970s", "massachusetts"), 4800, 6.6),
        t("movie:untouchables", "The Untouchables", 1987, setOf(80, 18, 36), listOf("prohibition era", "gangster", "al capone", "1930s", "chicago"), 5800, 7.8),
        t("movie:killersflower", "Killers of the Flower Moon", 2023, setOf(80, 18, 36), listOf("oklahoma", "1920s", "osage", "murder", "native american", "based on true story"), 3500, 7.5),
        t("movie:bonnieclyde", "Bonnie and Clyde", 1967, setOf(80, 18), listOf("bank robbery", "great depression", "outlaw", "texas", "1930s"), 1900, 7.6),
        t("tv:boardwalk", "Boardwalk Empire", 2010, setOf(80, 18), listOf("prohibition era", "gangster", "1920s", "atlantic city"), 1800, 8.1),
        t("tv:sopranos", "The Sopranos", 1999, setOf(18, 80), listOf("mafia", "new jersey", "mobster"), 2800, 8.6),
        t("tv:narcos", "Narcos", 2015, setOf(80, 18), listOf("drug cartel", "colombia", "1980s", "biography"), 2500, 8.1),
        t("tv:peaky", "Peaky Blinders", 2013, setOf(80, 18), listOf("gangster", "1920s", "birmingham, england", "world war i veteran"), 9000, 8.5),
        // True crime documentary.
        t("tv:makingmurderer", "Making a Murderer", 2015, setOf(99, 80), listOf("true crime", "wisconsin", "wrongful conviction"), 600, 7.9),
    )

    // ---------------------------------------------------------------- biopics and non-history: never in the hub

    val NOT_HISTORY = listOf(
        t("movie:bohemian", "Bohemian Rhapsody", 2018, setOf(10402, 18), listOf("biography", "rock band", "1970s", "1980s", "london, england"), 16000, 8.0),
        t("movie:elvis", "Elvis", 2022, setOf(10402, 18, 36), listOf("biography", "rock and roll", "1950s", "1960s", "1970s", "musician"), 3500, 7.4),
        t("movie:fordferrari", "Ford v Ferrari", 2019, setOf(18, 28, 36), listOf("biography", "race car", "le mans", "1960s", "sports"), 7500, 8.0),
        t("movie:socialnetwork", "The Social Network", 2010, setOf(18), listOf("biography", "facebook", "harvard university"), 12000, 7.4),
        t("movie:amy", "Amy", 2015, setOf(99, 10402), listOf("biography", "singer"), 900, 7.8),
        t("tv:crown", "The Crown", 2016, setOf(18), listOf("british monarchy", "biography", "20th century", "queen elizabeth ii"), 1500, 8.2),
        t("movie:freesolo", "Free Solo", 2018, setOf(99, 12), listOf("rock climbing", "yosemite"), 1600, 7.9),
        t("tv:planetearth", "Planet Earth", 2006, setOf(99), listOf("nature", "natural history", "wildlife"), 1800, 8.9),
        t("tv:lastdance", "The Last Dance", 2020, setOf(99), listOf("basketball", "1990s", "chicago bulls"), 1100, 8.5),
        t("movie:nationaltreasure", "National Treasure", 2004, setOf(12, 28, 53, 9648), listOf("treasure hunt", "declaration of independence", "founding fathers"), 7000, 6.6),
        t("movie:vampirehunter", "Abraham Lincoln: Vampire Hunter", 2012, setOf(14, 27, 28), listOf("abraham lincoln", "vampire", "american civil war"), 4000, 5.7),
        t("movie:prideprejudice", "Pride & Prejudice", 2005, setOf(18, 10749), listOf("19th century", "regency england", "based on novel or book"), 8000, 7.9),
    )

    // ---------------------------------------------------------------- history that belongs

    val HISTORY = listOf(
        // Documentaries
        t("movie:shallnot", "They Shall Not Grow Old", 2018, setOf(99, 36, 10752), listOf("world war i", "trench warfare", "archive footage"), 1100, 8.1),
        t("movie:apollo11doc", "Apollo 11", 2019, setOf(99, 36), listOf("apollo 11", "nasa", "moon landing"), 700, 8.0),
        t("tv:wwiicolour", "World War II in Colour", 2009, setOf(99, 10768), listOf("world war ii", "archive footage"), 150, 8.4),
        // History Movies (fiction)
        t("movie:youngwashington", "Young Washington", 2026, setOf(36, 18), listOf("george washington", "american revolution"), 40, 7.0),
        t("movie:lincoln", "Lincoln", 2012, setOf(36, 18), listOf("abraham lincoln", "american civil war", "president of the united states", "biography"), 5500, 6.9),
        t("movie:glory", "Glory", 1989, setOf(18, 36, 10752), listOf("american civil war", "union army"), 1900, 7.6),
        t("movie:patriot", "The Patriot", 2000, setOf(18, 36, 28, 10752), listOf("american revolution", "redcoat", "south carolina"), 5000, 7.0),
        t("movie:spr", "Saving Private Ryan", 1998, setOf(18, 36, 10752), listOf("world war ii", "d-day", "omaha beach"), 16000, 8.2),
        t("movie:oppenheimer", "Oppenheimer", 2023, setOf(18, 36), listOf("biography", "atomic bomb", "manhattan project", "world war ii"), 9000, 8.1),
        t("movie:imitation", "The Imitation Game", 2014, setOf(36, 18, 53, 10752), listOf("biography", "world war ii", "enigma code", "cryptography"), 16000, 8.0),
        t("movie:braveheart", "Braveheart", 1995, setOf(28, 18, 36, 10752), listOf("scotland", "13th century", "rebellion", "biography"), 10000, 7.9),
        t("movie:gladiator", "Gladiator", 2000, setOf(28, 18, 12), listOf("ancient rome", "gladiator", "roman empire"), 18000, 8.2),
        t("movie:dunkirk", "Dunkirk", 2017, setOf(10752, 28, 18), listOf("world war ii", "dunkirk evacuation", "1940s"), 17000, 7.5),
        t("movie:hiddenfigures", "Hidden Figures", 2016, setOf(36, 18), listOf("nasa", "space race", "1960s", "biography"), 8000, 8.0),
        // The West (Butch Cassidy and 3:10 to Yuma carry TMDB's Crime genre; westerns keep their outlaws)
        t("movie:butchcassidy", "Butch Cassidy and the Sundance Kid", 1969, setOf(36, 18, 37, 80), listOf("outlaw", "bank robbery", "wyoming"), 1700, 7.6),
        t("movie:yuma", "3:10 to Yuma", 2007, setOf(37, 80, 18), listOf("outlaw", "stagecoach", "arizona", "1880s"), 4500, 7.1),
        t("movie:unforgiven", "Unforgiven", 1992, setOf(37), listOf("gunslinger", "outlaw", "1880s", "wyoming"), 4500, 7.9),
        // History series
        t("tv:bandofbrothers", "Band of Brothers", 2001, setOf(18, 10768), listOf("world war ii", "paratrooper", "miniseries"), 3500, 8.6),
        t("tv:johnadams", "John Adams", 2008, setOf(18, 10768), listOf("john adams", "american revolution"), 400, 7.9),
        t("tv:vikings", "Vikings", 2013, setOf(18, 10759), listOf("viking", "middle ages", "9th century"), 6500, 8.1),
    )

    val ALL = KEN_BURNS + CRIME + NOT_HISTORY + HISTORY

    /** The fixed "today" for the Ken Burns shelf order. */
    const val TODAY = "2026-10-21"
}
