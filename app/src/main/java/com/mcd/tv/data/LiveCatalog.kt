package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// ======================= Live TV: smart channel organizer =======================
//
// Big IPTV playlists (16k+ channels) arrive as provider groups like "US| AT&T RAW 60fps" with names like
// "AT&T: 365BLK RAW". This file turns that into clean names and a handful of sections (Sports, News, …)
// with sub-groups (Sports -> Football, Basketball…; International -> by country).
//
// Pure Kotlin (no Android types), so the rules can be unit tested on the JVM.
// Note: the object is LiveOrganizer, because com.mcd.tv.data.LiveCatalog is already the addon catalog class.

/** A sub-group chip inside a section: [items] are indices into [LiveIndex.channels]. */
class LiveSubgroup(val label: String, val items: IntArray)

/** One section of the Live TV rail. [items] are indices into [LiveIndex.channels], in playlist order. */
class LiveSection(val name: String, val items: IntArray, val subgroups: List<LiveSubgroup>) {
    val count: Int get() = items.size
}

/**
 * A playlist organized for browsing. Built once per playlist load (see [LiveOrganizer.indexFor]).
 * [channels] = playable channels (separators, VOD and duplicate URLs removed), [names] = their clean names.
 */
class LiveIndex internal constructor(
    /** The list this index was built from (identity is the cache key). */
    val source: List<Channel>,
    val channels: List<Channel>,
    val names: Array<String>,
    val sections: List<LiveSection>,
) {
    private val byUrl: HashMap<String, Int> = HashMap<String, Int>(channels.size * 2).also { m ->
        channels.forEachIndexed { i, ch -> m[ch.url] = i }
    }

    /** Every channel, in playlist order. */
    val all: IntArray = IntArray(channels.size) { it }

    val size: Int get() = channels.size

    fun indexOf(url: String): Int = byUrl[url] ?: -1

    /** Indices for these stream URLs (favorites, recents), skipping ones no longer in the playlist. */
    fun indicesOf(urls: List<String>): IntArray {
        val out = ArrayList<Int>(urls.size)
        for (u in urls) { val i = byUrl[u]; if (i != null) out.add(i) }
        return out.toIntArray()
    }

    fun channelsOf(items: IntArray): List<Channel> = items.map { channels[it] }

    /** Channels whose clean or original name contains [query] (case-insensitive), at most [max]. */
    fun search(query: String, max: Int = 200): IntArray {
        val q = query.trim()
        if (q.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        for (i in channels.indices) {
            if (names[i].contains(q, ignoreCase = true) || channels[i].name.contains(q, ignoreCase = true)) {
                out.add(i)
                if (out.size >= max) break
            }
        }
        return out.toIntArray()
    }
}

object LiveOrganizer {
    const val SPORTS = "Sports"
    const val NEWS = "News"
    const val ENTERTAINMENT = "Entertainment"
    const val MOVIES = "Movies"
    const val KIDS = "Kids"
    const val H24 = "24/7"
    const val MUSIC = "Music"
    const val LOCAL = "Local"
    const val EVENTS = "Events & PPV"
    const val INTERNATIONAL = "International"
    const val OTHER = "Other"

    /** Rail order. */
    val SECTION_ORDER = listOf(SPORTS, NEWS, ENTERTAINMENT, MOVIES, KIDS, H24, MUSIC, LOCAL, EVENTS, INTERNATIONAL, OTHER)

    /** Sports chip order. */
    val SPORT_ORDER = listOf(
        "Football", "Basketball", "Baseball", "Hockey", "Soccer", "Combat", "Motorsports",
        "Golf", "Tennis", "College", "Networks", "Other Sports",
    )

    /** Country used when nothing in the group or name says where a channel is from. */
    const val UNKNOWN_COUNTRY = "Other"

    private const val MAX_CHIPS = 24

    // ---------------------------------------------------------------- countries

    private val COUNTRY_CODES: Map<String, String> = buildMap<String, String> {
        fun reg(name: String, vararg codes: String) = codes.forEach { code -> this[code] = name }
        reg("US", "US", "USA")
        reg("UK", "UK", "GB", "EN", "ENGLAND")
        reg("Canada", "CA", "CAN", "CANADA")
        reg("Latino", "LATINO", "LATIN", "LATAM", "LAT", "MX", "MEX", "MEXICO", "ES", "ESP", "SPAIN", "SPANISH", "ESPAÑOL", "ESPANOL")
        reg("France", "FR", "FRA", "FRANCE", "FRENCH")
        reg("Germany", "DE", "GER", "GERMANY", "GERMAN", "DEU")
        reg("Italy", "IT", "ITA", "ITALY", "ITALIA")
        reg("India", "IN", "IND", "INDIA", "HINDI", "PUNJABI", "DESI")
        reg("Arabic", "AR", "ARA", "ARAB", "ARABIC")
        reg("Portuguese", "PT", "POR", "PORTUGAL", "BR", "BRA", "BRAZIL", "BRASIL")
        reg("Netherlands", "NL", "NED", "DUTCH")
        reg("Poland", "PL", "POL", "POLAND")
        reg("Turkey", "TR", "TUR", "TURKEY", "TURKISH")
        reg("Russia", "RU", "RUS", "RUSSIA")
        reg("Greece", "GR", "GRE", "GREECE")
        reg("Australia", "AU", "AUS", "AUSTRALIA")
        reg("New Zealand", "NZ")
        reg("Ireland", "IE", "IRE", "IRELAND", "IRISH")
        reg("Philippines", "PH", "PHI", "FILIPINO", "PINOY")
        reg("Pakistan", "PK", "PAK", "PAKISTAN")
        reg("Africa", "AF", "AFR", "AFRICA", "AFRICAN", "NG", "ZA")
        reg("Caribbean", "CARIB", "CARIBBEAN", "JM")
        reg("Balkans", "EX-YU", "EXYU", "YU", "BALKAN", "BALKANS")
        reg("Albania", "AL", "ALB", "ALBANIA")
        reg("Romania", "RO", "ROM", "ROMANIA")
        reg("Nordic", "SE", "SWE", "NO", "NOR", "DK", "DEN", "FI", "FIN", "NORDIC", "SCANDINAVIA")
        reg("Belgium", "BE", "BEL")
        reg("Switzerland", "CH", "SUI")
        reg("Austria", "AT", "AUT")
        reg("Hungary", "HU", "HUN")
        reg("Czech & Slovak", "CZ", "SK", "CZE")
        reg("Korea", "KR", "KOR", "KOREA", "KOREAN")
        reg("Japan", "JP", "JPN", "JAPAN")
        reg("China", "CN", "CHN", "CHINA", "CHINESE")
        reg("Asia", "ASIA", "ASIAN")
        reg("Israel", "IL", "ISR", "ISRAEL")
        reg("Iran", "IR", "IRAN", "PERSIAN", "FARSI")
        reg("Kurdish", "KU", "KURD", "KURDISH")
        reg("Vietnam", "VN", "VIET")
        reg("Thailand", "TH", "THAI")
    }

    /** Country words that count anywhere in a group name (e.g. "SPANISH CHANNELS"). Long words only, to avoid false hits. */
    private val COUNTRY_WORDS: Map<String, String> = COUNTRY_CODES.filterKeys { it.length >= 5 || it == "UK" }

    /** Streaming providers used as name prefixes ("AT&T: 365BLK"). The prefix is dropped, the channel name kept. */
    private val PROVIDERS = setOf(
        "AT&T", "ATT", "PRIME", "AMAZON", "TUBI", "PLUTO", "PLUTO TV", "PLUTOTV", "SLING", "DIRECTV", "DTV", "ROKU",
        "SAMSUNG", "SAMSUNG TV PLUS", "XUMO", "PEACOCK", "FUBO", "FUBOTV", "HULU", "PHILO", "DISH", "SPECTRUM",
        "VERIZON", "FIOS", "XFINITY", "YOUTUBE TV", "YTTV", "VIZIO", "PLEX", "STIRR", "DISTRO", "FREEVEE", "LG",
        "TVE", "VIP", "FAST",
    )

    /** "US| …", "|US| …", "[US] …", "US: …", "US - …": the prefix token (group 1). */
    private val PREFIX = Regex("^\\s*[|\\[(]?\\s*([\\p{L}&][\\p{L}&+\\- ]{0,15}?)\\s*(?:[|:\\])]|-\\s)\\s*")

    private fun prefixToken(s: String): Pair<String, Int>? {
        val m = PREFIX.find(s) ?: return null
        return m.groupValues[1].trim().uppercase(Locale.ROOT) to m.range.last + 1
    }

    /** Country display name from a "US| …" style prefix, or null. */
    private fun prefixCountry(s: String): String? = prefixToken(s)?.let { COUNTRY_CODES[it.first] }

    /**
     * Where a channel is from: the group's prefix ("US| …"), a country word in the group ("LATINO SPORTS"),
     * or the name's prefix ("UK: BBC One"). [UNKNOWN_COUNTRY] when nothing says.
     */
    fun country(group: String, name: String): String {
        prefixCountry(group)?.let { return it }
        for (t in tokens(group.uppercase(Locale.ROOT))) COUNTRY_WORDS[t]?.let { return it }
        prefixCountry(name)?.let { return it }
        return UNKNOWN_COUNTRY
    }

    // ---------------------------------------------------------------- names

    /** Superscript / small-cap letters providers use for tags ("ᴿᴬᵂ", "ᴴᴰ", "ʀᴀᴡ", "⁴ᴷ"). */
    private val FANCY = Regex("[\\u0250-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F\\u2C7C\\u2C7D\\uA7F8\\uA7F9]+")

    /** Quality / format tags as whole words. */
    private val QUALITY = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:RAW|\\d{2,3}\\s?FPS|FHD|UHD|HD|SD|HQ|4K|8K|2160P|1080P|1080I|720P|480P|HEVC|H\\.?26[45]|X26[45]|HDR10\\+?|HDR|BACKUP|MULTI-?AUDIO)(?![\\p{L}\\p{N}])",
    )

    private val EMPTY_BRACKETS = Regex("\\(\\s*\\)|\\[\\s*]|\\{\\s*}")
    private val SPACES = Regex("\\s{2,}")
    private val LEAD_JUNK = Regex("^[\\s|:;,./\\\\\\-–—•·*#=_~★☆●◉►▶>]+")
    private val TRAIL_JUNK = Regex("[\\s|:;,./\\\\\\-–—•·*#=_~★☆●◉◄<(\\[]+$")

    /** Strips leading country and provider prefixes ("US| ", "|US| ", "AT&T: ", "[UK] "), up to three. */
    private fun stripPrefixes(s: String): String {
        var t = s.trim()
        repeat(3) {
            val (tok, end) = prefixToken(t) ?: return t
            if (tok in COUNTRY_CODES || tok in PROVIDERS) t = t.substring(end).trim() else return t
        }
        return t
    }

    /**
     * Display name: "US| ESPN FHD" -> "ESPN", "AT&T: 123 GO! RAW" -> "123 GO!", "|UK| SKY SPORTS F1 ᴴᴰ" -> "SKY SPORTS F1".
     * Never empty (falls back to the trimmed original).
     */
    fun cleanName(raw: String): String {
        val original = raw.trim()
        var t = stripPrefixes(original)
        t = FANCY.replace(t, " ")
        t = QUALITY.replace(t, " ")
        t = EMPTY_BRACKETS.replace(t, " ")
        t = SPACES.replace(t, " ")
        t = LEAD_JUNK.replace(t, "")
        t = TRAIL_JUNK.replace(t, "")
        t = t.trim()
        return t.ifEmpty { original.ifEmpty { "Channel" } }
    }

    /** Group label for chips: "US| AT&T RAW 60fps" -> "AT&T", "SPORTS| UFC" -> "Sports · UFC", "US| NEWS" -> "NEWS". */
    fun cleanGroup(raw: String): String {
        var t = stripPrefixes(raw)
        t = FANCY.replace(t, " ")
        t = QUALITY.replace(t, " ")
        t = EMPTY_BRACKETS.replace(t, " ")
        t = t.replace(Regex("\\s*\\|\\s*"), " · ")
        t = SPACES.replace(t, " ")
        t = LEAD_JUNK.replace(t, "")
        t = TRAIL_JUNK.replace(t, "").trim()
        if (t.isEmpty()) return "Other"
        // Long shouty words read better in title case ("ENTERTAINMENT" -> "Entertainment"); short ones are acronyms.
        return t.split(' ').joinToString(" ") { w ->
            if (w.length >= 5 && w.all { it.isUpperCase() }) w.substring(0, 1) + w.substring(1).lowercase(Locale.ROOT) else w
        }
    }

    private const val DECO = "#=-*★☆_~▬═━─•●"

    private fun decoRun(s: String, fromEnd: Boolean): Boolean {
        if (s.length < 3) return false
        val c = if (fromEnd) s[s.length - 1] else s[0]
        if (c !in DECO) return false
        return if (fromEnd) s[s.length - 2] == c && s[s.length - 3] == c else s[1] == c && s[2] == c
    }

    private fun looksLikeSeparator(t: String): Boolean {
        if (t.isEmpty()) return true
        if (decoRun(t, false) || decoRun(t, true)) return true
        var alnum = 0
        for (c in t) if (c.isLetterOrDigit()) alnum++
        return alnum == 0 || alnum * 10 < t.length * 4 // under 40% letters and digits
    }

    /** Playlist divider entries ("##### AT&T RAW 60fps #####", "===== SPORTS =====", "|US| ------") are hidden. */
    fun isSeparator(name: String): Boolean {
        val t = name.trim()
        return looksLikeSeparator(t) || looksLikeSeparator(stripPrefixes(t))
    }

    // ---------------------------------------------------------------- keywords

    // Section bits.
    private const val S_247 = 1 shl 0
    private const val S_EVENTS = 1 shl 1
    private const val S_SPORTS = 1 shl 2
    private const val S_NEWS = 1 shl 3
    private const val S_KIDS = 1 shl 4
    private const val S_MOVIES = 1 shl 5
    private const val S_MUSIC = 1 shl 6
    private const val S_LOCAL = 1 shl 7
    private const val S_ENT = 1 shl 8

    // Sport bits (each also sets S_SPORTS).
    private const val P_FOOTBALL = 1 shl 10 // NFL, NCAAF: always American football
    private const val P_FOOTBALL_WORD = 1 shl 11 // "FOOTBALL": soccer outside North America
    private const val P_CONF = 1 shl 12 // SEC, BIG TEN, ACC…: filed under Football
    private const val P_BASKET = 1 shl 13
    private const val P_BASE = 1 shl 14
    private const val P_HOCKEY = 1 shl 15
    private const val P_SOCCER = 1 shl 16
    private const val P_COMBAT = 1 shl 17
    private const val P_MOTOR = 1 shl 18
    private const val P_GOLF = 1 shl 19
    private const val P_TENNIS = 1 shl 20
    private const val P_COLLEGE = 1 shl 21
    private const val P_NETWORK = 1 shl 22

    /** Normalized keyword ("24/7" -> "24 7", "PAC-12" -> "PAC 12") -> bits. Phrases are up to three words. */
    private val KEYWORDS: HashMap<String, Int> = HashMap<String, Int>(512).also { m ->
        fun add(bits: Int, vararg kws: String) = kws.forEach { k ->
            val key = tokens(k.uppercase(Locale.ROOT)).joinToString(" ")
            m[key] = (m[key] ?: 0) or bits
        }
        add(S_247, "24/7", "24-7", "247", "24×7", "24X7")
        add(S_EVENTS, "PPV", "PAY PER VIEW", "PAY-PER-VIEW", "EVENTS", "LIVE EVENT", "LIVE EVENTS")

        add(S_SPORTS, "SPORT", "SPORTS", "DEPORTES", "ESPORTES", "SPORTSCENTER", "ESPNEWS", "STADIUM", "OUTDOOR CHANNEL", "CRICKET", "RUGBY", "OLYMPICS", "OLYMPIC")
        add(S_SPORTS or P_NETWORK,
            "ESPN", "ESPN+", "FS1", "FS2", "FOX SPORTS", "CBS SPORTS", "TNT SPORTS", "NBC SPORTS", "SKY SPORTS", "BT SPORT",
            "BEIN", "DAZN", "SPORTSNET", "TSN", "SUPERSPORT", "EUROSPORT", "BALLY", "BALLY SPORTS", "FANDUEL SPORTS", "MSG",
            "NESN", "YES NETWORK", "ALTITUDE", "MARQUEE", "ROOT SPORTS", "SPECTRUM SPORTSNET", "FOX DEPORTES", "ESPN DEPORTES",
            "MONUMENTAL", "SPORTSNET LA", "PREMIER SPORTS", "VIAPLAY")
        add(S_SPORTS or P_FOOTBALL, "NFL", "NCAAF", "REDZONE", "RED ZONE", "SUNDAY TICKET", "COLLEGE FOOTBALL", "CFL", "NFL NETWORK", "GAMEPASS", "XFL", "UFL")
        add(S_SPORTS or P_FOOTBALL_WORD, "FOOTBALL")
        add(S_SPORTS or P_CONF, "SEC", "SEC NETWORK", "BIG TEN", "BIG 10", "BTN", "ACC", "ACC NETWORK", "ACCN", "PAC-12", "PAC 12", "BIG 12", "LONGHORN NETWORK")
        add(S_SPORTS or P_BASKET, "NBA", "NBA TV", "NBA PASS", "NCAAB", "WNBA", "BASKETBALL", "COLLEGE BASKETBALL", "MARCH MADNESS", "NBA LEAGUE PASS")
        add(S_SPORTS or P_BASE, "MLB", "MLB NETWORK", "MLB TV", "BASEBALL", "MILB")
        add(S_SPORTS or P_HOCKEY, "NHL", "NHL NETWORK", "HOCKEY", "AHL")
        add(S_SPORTS or P_SOCCER,
            "SOCCER", "PREMIER LEAGUE", "EPL", "LALIGA", "LA LIGA", "SERIE A", "BUNDESLIGA", "LIGUE 1", "MLS", "UEFA", "FIFA",
            "LIGA MX", "FUTBOL", "FÚTBOL", "FUTEBOL", "CHAMPIONS LEAGUE", "EUROPA LEAGUE", "TUDN", "EFL", "SPFL", "EREDIVISIE",
            "MLS SEASON PASS", "COPA")
        add(S_SPORTS or P_COMBAT, "UFC", "BOXING", "WWE", "MMA", "PFL", "AEW", "BELLATOR", "TOP RANK", "FITE", "WRESTLING", "BKFC")
        add(S_SPORTS or P_MOTOR, "F1", "FORMULA 1", "FORMULA1", "FORMULA ONE", "NASCAR", "INDYCAR", "MOTOGP", "MOTORSPORT", "MOTORSPORTS", "MAVTV", "SPEED")
        add(S_SPORTS or P_GOLF, "GOLF", "PGA", "LPGA", "LIV GOLF", "GOLF CHANNEL")
        add(S_SPORTS or P_TENNIS, "TENNIS", "ATP", "WTA", "WIMBLEDON", "TENNIS CHANNEL")
        add(S_SPORTS or P_COLLEGE, "NCAA", "COLLEGE", "ESPNU", "CBS SPORTS NETWORK")

        add(S_NEWS,
            "NEWS", "CNN", "HLN", "MSNBC", "FOX NEWS", "FOX BUSINESS", "CNBC", "BLOOMBERG", "NEWSMAX", "NEWSNATION", "WEATHER",
            "WEATHER CHANNEL", "C-SPAN", "CSPAN", "CBSN", "SKY NEWS", "BBC NEWS", "AL JAZEERA", "EURONEWS", "CHEDDAR", "OAN",
            "LIVENOW", "NOTICIAS", "NEWSMAX2", "FRANCE 24", "DW", "CNN INTERNATIONAL", "NBC NEWS NOW", "ABC NEWS LIVE")
        add(S_KIDS,
            "KIDS", "KID", "CARTOON", "CARTOONS", "CARTOONITO", "CARTOON NETWORK", "NICK", "NICKELODEON", "NICK JR", "NICKTOONS",
            "TEENNICK", "DISNEY JR", "DISNEY JUNIOR", "DISNEY CHANNEL", "DISNEY XD", "BOOMERANG", "PBS KIDS", "BABY", "BABYTV",
            "BABY TV", "CBEEBIES", "CBBC", "UNIVERSAL KIDS", "INFANTIL", "JUNIOR", "KIDZ", "TOONS")
        add(S_MOVIES,
            "MOVIE", "MOVIES", "CINEMA", "CINE", "HBO", "SHOWTIME", "STARZ", "CINEMAX", "MGM+", "MGM", "EPIX", "TCM", "HALLMARK MOVIES",
            "FLIX", "FILM", "FILMS", "MOVIEPLEX", "ENCORE", "FXM", "SONY MOVIES", "PARAMOUNT+ WITH SHOWTIME", "THRILLER", "ACTION MAX")
        add(S_MUSIC, "MUSIC", "MTV", "MTV2", "MTV LIVE", "VH1", "BET JAMS", "BET SOUL", "CMT", "MUSIC CHOICE", "REVOLT", "STINGRAY", "VEVO", "RADIO", "AXS TV", "HITS")
        add(S_LOCAL, "LOCAL", "LOCALS", "ABC", "NBC", "CBS", "FOX", "PBS", "CW", "THE CW", "MY NETWORK", "MYNETWORKTV", "AFFILIATE", "AFFILIATES", "TELEMUNDO", "UNIVISION", "ION")
        add(S_ENT,
            "AMC", "FX", "FXX", "TBS", "TNT", "USA", "USA NETWORK", "BRAVO", "A&E", "HGTV", "FOOD", "FOOD NETWORK", "TLC", "DISCOVERY",
            "HISTORY", "SYFY", "E!", "LIFETIME", "OWN", "OXYGEN", "PARAMOUNT", "PARAMOUNT NETWORK", "COMEDY CENTRAL", "FREEFORM",
            "NAT GEO", "NATIONAL GEOGRAPHIC", "ANIMAL PLANET", "TRAVEL", "TRAVEL CHANNEL", "INVESTIGATION DISCOVERY", "BET", "VICE",
            "IFC", "SUNDANCE", "REELZ", "TV LAND", "WE TV", "GSN", "POP", "OVATION", "COOKING CHANNEL", "DIY", "SCIENCE", "AHC",
            "DESTINATION AMERICA", "GAME SHOW", "COMEDY", "REALITY", "DRAMA", "ENTERTAINMENT", "CLASSIC TV", "SITCOM", "SITCOMS")
    }

    private const val SPORT_BITS = P_FOOTBALL or P_FOOTBALL_WORD or P_CONF or P_BASKET or P_BASE or P_HOCKEY or
        P_SOCCER or P_COMBAT or P_MOTOR or P_GOLF or P_TENNIS or P_COLLEGE or P_NETWORK

    /** Upper-case words; '&', '+' and '!' stay inside words ("A&E", "ESPN+", "E!"). */
    private fun tokens(upper: String): List<String> {
        val out = ArrayList<String>(12)
        val sb = StringBuilder()
        for (c in upper) {
            if (c.isLetterOrDigit() || c == '&' || c == '+' || c == '!') sb.append(c)
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** All keyword bits found in [text] (group + name): single words (plus "ESPN2" -> "ESPN", "ESPN+" -> "ESPN") and 2-3 word phrases. */
    internal fun keywordBits(text: String): Int {
        val w = tokens(text.uppercase(Locale.ROOT))
        var bits = 0
        for (i in w.indices) {
            val t = w[i]
            KEYWORDS[t]?.let { bits = bits or it }
            // Variants: trailing digits or +/! dropped ("ESPN2", "ABC7", "ESPN+") when letters remain.
            val trimmed = t.trimEnd('+', '!').trimEnd { it.isDigit() }
            if (trimmed.isNotEmpty() && trimmed != t && trimmed.any { it.isLetter() }) KEYWORDS[trimmed]?.let { bits = bits or it }
            if (i + 1 < w.size) {
                val two = t + " " + w[i + 1]
                KEYWORDS[two]?.let { bits = bits or it }
                if (i + 2 < w.size) KEYWORDS[two + " " + w[i + 2]]?.let { bits = bits or it }
            }
        }
        if (bits and SPORT_BITS != 0) bits = bits or S_SPORTS
        return bits
    }

    /** US call letters with a channel number or suffix: "WABC 7", "KTLA5", "WNBC-DT", "(WGN)". */
    private val CALL_SIGN = Regex("(?<![A-Z0-9])(?:[KW][A-Z]{2,3}(?:-(?:TV|DT|CD|LD)\\d?|\\s?\\d{1,2})(?![A-Z0-9])|\\([KW][A-Z]{2,3}\\))")
    private val NOT_CALL_SIGNS = setOf("WEST", "WILD", "WOW", "WIN", "WAR", "WAY", "WEB", "WIFE", "WOOD", "KICK", "KILL", "KNOW", "WWE", "KIDS", "KIDZ", "WTA", "WNBA")

    private fun hasCallSign(name: String): Boolean {
        val u = name.uppercase(Locale.ROOT)
        return CALL_SIGN.findAll(u).any { m ->
            val letters = m.value.trimStart('(').takeWhile { it in 'A'..'Z' }
            letters !in NOT_CALL_SIGNS
        }
    }

    private fun isDomestic(country: String) = country == "US" || country == UNKNOWN_COUNTRY

    /** Section for a channel; check order matters (24/7 and Events before Sports, Sports before Local, Kids before Movies). */
    fun section(group: String, name: String, country: String = country(group, name)): String =
        sectionFor(keywordBits("$group $name"), name, country)

    private fun sectionFor(bits: Int, name: String, country: String): String = when {
        bits and S_247 != 0 -> H24
        bits and S_EVENTS != 0 -> EVENTS
        bits and S_SPORTS != 0 -> SPORTS
        bits and S_NEWS != 0 -> NEWS
        bits and S_KIDS != 0 -> KIDS
        bits and S_MOVIES != 0 -> MOVIES
        bits and S_MUSIC != 0 -> MUSIC
        isDomestic(country) && (bits and S_LOCAL != 0 || hasCallSign(name)) -> LOCAL
        !isDomestic(country) -> INTERNATIONAL
        bits and S_ENT != 0 -> ENTERTAINMENT
        country == "US" -> ENTERTAINMENT
        else -> OTHER
    }

    /** Sport chip for a Sports channel. */
    fun sport(group: String, name: String, country: String = country(group, name)): String =
        sportFor(keywordBits("$group $name"), country)

    private fun sportFor(bits: Int, country: String): String {
        val northAmerica = country == "US" || country == "Canada" || country == UNKNOWN_COUNTRY
        return when {
            bits and P_FOOTBALL != 0 -> "Football"
            bits and P_BASKET != 0 -> "Basketball"
            bits and P_BASE != 0 -> "Baseball"
            bits and P_HOCKEY != 0 -> "Hockey"
            bits and P_SOCCER != 0 -> "Soccer"
            bits and P_FOOTBALL_WORD != 0 && !northAmerica -> "Soccer"
            bits and P_COMBAT != 0 -> "Combat"
            bits and P_MOTOR != 0 -> "Motorsports"
            bits and P_GOLF != 0 -> "Golf"
            bits and P_TENNIS != 0 -> "Tennis"
            bits and (P_FOOTBALL_WORD or P_CONF) != 0 -> "Football"
            bits and P_COLLEGE != 0 -> "College"
            bits and P_NETWORK != 0 -> "Networks"
            else -> "Other Sports"
        }
    }

    // ---------------------------------------------------------------- index

    @Volatile private var cached: LiveIndex? = null

    /** The organized index for [list], built on a background thread and cached for the same list instance. */
    suspend fun indexFor(list: List<Channel>): LiveIndex {
        cached?.let { if (it.source === list) return it }
        val built = withContext(Dispatchers.Default) { build(list) }
        cached = built
        return built
    }

    /** Organizes a playlist. Drops separators, /movie/ and /series/ entries and repeated stream URLs. */
    fun build(source: List<Channel>): LiveIndex {
        val n = source.size
        val channels = ArrayList<Channel>(n)
        val names = ArrayList<String>(n)
        val sectionOf = ArrayList<String>(n)
        val subOf = ArrayList<String>(n)
        val seen = HashSet<String>(n * 2)
        val groupLabels = HashMap<String, String>()
        for (ch in source) {
            if (ch.url.contains("/movie/") || ch.url.contains("/series/")) continue
            if (isSeparator(ch.name)) continue
            if (!seen.add(ch.url)) continue
            val c = country(ch.group, ch.name)
            val bits = keywordBits(ch.group + " " + ch.name)
            val sec = sectionFor(bits, ch.name, c)
            val sub = when (sec) {
                SPORTS -> sportFor(bits, c)
                INTERNATIONAL -> c
                else -> groupLabels.getOrPut(ch.group) { cleanGroup(ch.group) }
            }
            channels.add(ch)
            names.add(cleanName(ch.name))
            sectionOf.add(sec)
            subOf.add(sub)
        }

        // Bucket indices by section, then by sub-group (both keep playlist order).
        val bySection = LinkedHashMap<String, ArrayList<Int>>()
        for (i in channels.indices) bySection.getOrPut(sectionOf[i]) { ArrayList() }.add(i)
        val sections = SECTION_ORDER.mapNotNull { name ->
            val idx = bySection[name] ?: return@mapNotNull null
            val subs = LinkedHashMap<String, ArrayList<Int>>()
            for (i in idx) subs.getOrPut(subOf[i]) { ArrayList() }.add(i)
            val ordered: List<Pair<String, List<Int>>> = when (name) {
                SPORTS -> SPORT_ORDER.mapNotNull { s -> subs[s]?.let { s to it } }
                else -> subs.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
            }
            // Cap the chip row; the smallest groups fold into one "More" chip.
            val chips = if (ordered.size <= MAX_CHIPS) {
                ordered
            } else {
                val rest = ordered.drop(MAX_CHIPS - 1).flatMap { it.second }.sorted()
                ordered.take(MAX_CHIPS - 1) + ("More" to rest)
            }
            LiveSection(
                name = name,
                items = idx.toIntArray(),
                subgroups = if (chips.size > 1) chips.map { LiveSubgroup(it.first, it.second.toIntArray()) } else emptyList(),
            )
        }
        return LiveIndex(source, channels, names.toTypedArray(), sections)
    }
}
