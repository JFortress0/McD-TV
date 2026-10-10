package com.mcd.tv.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Taste: what each profile likes, learned from what it watches, and the rankings built on it
// (More Like This, Picked for You, Live TV For You).
// Pure Kotlin (no Android, no Prefs, no network), so every rule here is unit tested on the JVM.
// Recommender.kt gathers the data (TMDB, Library, Prefs) and calls into this file.

/** What a title is about. Ids are TMDB ids; [people] = directors / creators plus the top billed cast. */
data class Features(
    val key: String, // "movie:603"
    val genres: Set<Int>,
    val keywords: Set<Int>,
    val people: Set<Int>,
    val year: Int = 0,
    val rating: Double = 0.0,
    val votes: Int = 0,
    val language: String = "",
) {
    val animated: Boolean get() = Taste.ANIMATION in genres
    val family: Boolean get() = Taste.FAMILY in genres || Taste.KIDS in genres
}

/** One thing a profile did with a title: [weight] > 0 liked it, < 0 didn't. [at] = when (epoch ms). */
data class Signal(val f: Features, val weight: Double, val at: Long)

/** Choices a person made in Settings > Taste. Keyword ids come from the topic names (resolved by TMDB search). */
data class TasteChoices(
    val likeGenres: Set<Int> = emptySet(),
    val avoidGenres: Set<Int> = emptySet(),
    val likeKeywords: Set<Int> = emptySet(),
    val avoidKeywords: Set<Int> = emptySet(),
    /** Topic words as typed (also matched against Live TV guide text). */
    val likeWords: List<String> = emptyList(),
    val avoidWords: List<String> = emptyList(),
)

/**
 * A profile's learned taste. Weights are roughly -1..1 (normalized by how much the profile has watched).
 * [strength] 0..1 says how much there is to go on (0 = brand new profile: rankings fall back to quality).
 */
class TasteProfile(
    val genres: Map<Int, Double>,
    val keywords: Map<Int, Double>,
    val people: Map<Int, Double>,
    val decades: Map<Int, Double>,
    val choices: TasteChoices,
    val strength: Double,
) {
    /** How well [f] fits this profile, about -1..1 (choices dominate: an avoided genre is a hard no). */
    fun score(f: Features): Double {
        if (f.genres.any { Taste.genreIn(it, choices.avoidGenres) } || f.keywords.any { it in choices.avoidKeywords }) return -1.0
        var s = 0.0
        if (f.genres.isNotEmpty()) {
            // TV genre ids ("War & Politics") are learned under their movie ids ("War"), so look them up that way.
            val g = f.genres.sumOf { genres[Taste.canonicalGenre(it)] ?: 0.0 } / f.genres.size
            s += 0.45 * g
        }
        if (f.keywords.isNotEmpty()) s += 0.30 * clamp(f.keywords.sumOf { keywords[it] ?: 0.0 })
        if (f.people.isNotEmpty()) s += 0.15 * clamp(f.people.sumOf { people[it] ?: 0.0 })
        if (f.year > 0) s += 0.10 * (decades[f.year / 10 * 10] ?: 0.0)
        // Explicit likes: a boost on top of what was learned.
        val likedG = f.genres.count { Taste.genreIn(it, choices.likeGenres) }
        if (likedG > 0) s += 0.25 + 0.10 * (likedG - 1)
        if (f.keywords.any { it in choices.likeKeywords }) s += 0.30
        return clamp(s)
    }

    private fun clamp(v: Double) = Taste.clampValue(v)

    companion object {
        val EMPTY = TasteProfile(emptyMap(), emptyMap(), emptyMap(), emptyMap(), TasteChoices(), 0.0)
    }
}

object Taste {
    // TMDB genre ids used by the rules below.
    const val ACTION = 28
    const val ANIMATION = 16
    const val COMEDY = 35
    const val CRIME = 80
    const val DOCUMENTARY = 99
    const val DRAMA = 18
    const val FAMILY = 10751
    const val HISTORY = 36
    const val HORROR = 27
    const val MYSTERY = 9648
    const val ROMANCE = 10749
    const val THRILLER = 53
    const val WAR = 10752
    const val WESTERN = 37
    const val KIDS = 10762
    const val WAR_POLITICS = 10768
    const val ACTION_ADVENTURE = 10759
    const val REALITY = 10764
    const val TALK = 10767
    const val NEWS = 10763

    /** Older signals count less: half the weight after this many days. */
    const val HALF_LIFE_DAYS = 150.0

    private const val DAY_MS = 86_400_000.0

    fun clampValue(v: Double, lo: Double = -1.0, hi: Double = 1.0) = min(hi, max(lo, v))

    /** Movie and TV genre ids mean the same thing for taste ("Action & Adventure" counts as Action). */
    fun canonicalGenre(id: Int): Int = when (id) {
        ACTION_ADVENTURE -> ACTION
        WAR_POLITICS -> WAR
        10765 -> 878 // Sci-Fi & Fantasy -> Science Fiction
        else -> id
    }

    /**
     * True when genre [id] (movie or TV id) is one of [set] (chosen as movie ids). TV's combined
     * "Sci-Fi & Fantasy" counts for Fantasy and for Sci-Fi; "Action & Adventure" for Action and Adventure.
     */
    fun genreIn(id: Int, set: Set<Int>): Boolean {
        if (set.isEmpty()) return false
        if (id in set || canonicalGenre(id) in set) return true
        return when (id) {
            10765 -> 14 in set || 878 in set
            ACTION_ADVENTURE -> 12 in set
            else -> false
        }
    }

    fun decay(at: Long, now: Long): Double {
        if (at <= 0L) return 0.5
        val days = max(0.0, (now - at) / DAY_MS)
        return exp(-ln(2.0) * days / HALF_LIFE_DAYS)
    }

    /**
     * Learns a profile from its [signals]. Each feature's weight is the decayed sum of signal weights,
     * divided by the total decayed weight, so one title can't dominate and dislikes pull weights down.
     */
    fun build(signals: List<Signal>, choices: TasteChoices, now: Long): TasteProfile {
        if (signals.isEmpty()) return TasteProfile(emptyMap(), emptyMap(), emptyMap(), emptyMap(), choices, 0.0)
        val g = HashMap<Int, Double>()
        val k = HashMap<Int, Double>()
        val p = HashMap<Int, Double>()
        val d = HashMap<Int, Double>()
        var total = 0.0
        // Keywords seen in many signals are a theme ("based on true story"); one-offs are noise.
        for (s in signals) {
            val w = s.weight * decay(s.at, now)
            total += abs(w)
            s.f.genres.forEach { gid -> g.merge(canonicalGenre(gid), w, Double::plus) }
            s.f.keywords.forEach { kid -> k.merge(kid, w, Double::plus) }
            s.f.people.forEach { pid -> p.merge(pid, w, Double::plus) }
            if (s.f.year > 0) d.merge(s.f.year / 10 * 10, w, Double::plus)
        }
        if (total <= 0.0) return TasteProfile(emptyMap(), emptyMap(), emptyMap(), emptyMap(), choices, 0.0)
        // Genres appear on most titles: scale so the top liked genre is about 1.
        fun norm(m: Map<Int, Double>, scale: Double): Map<Int, Double> =
            m.mapValues { (_, v) -> clampValue(v / total * scale) }.filterValues { abs(it) >= 0.02 }
        val strength = min(1.0, total / 8.0) // ~8 finished titles = full confidence
        return TasteProfile(
            genres = norm(g, 2.0),
            keywords = norm(k, 3.0),
            people = norm(p, 3.0),
            decades = norm(d, 1.5),
            choices = choices,
            strength = strength,
        )
    }

    /** Weighted rating: few votes are pulled toward an average of 6.5 (0..1). */
    fun quality(rating: Double, votes: Int): Double {
        val m = 400.0
        val c = 6.5
        val v = max(0, votes).toDouble()
        val wr = (v / (v + m)) * rating + (m / (v + m)) * c
        return clampValue((wr - 4.0) / 5.0, 0.0, 1.0) // 4.0 -> 0, 9.0 -> 1
    }

    /**
     * How much [c] is like [seed] (0..1). Keywords carry the most weight: they describe the story
     * ("boston", "fbi informant", "irish mob") where genres only describe the shelf.
     * [idf] weights keywords by rarity within the candidate pool (common ones like "based on novel" count less).
     */
    fun similarity(seed: Features, c: Features, idf: (Int) -> Double = { 1.0 }): Double {
        val sg = seed.genres.map(::canonicalGenre).toSet()
        val cg = c.genres.map(::canonicalGenre).toSet()
        val genre = jaccard(sg, cg)
        val kw = if (seed.keywords.isEmpty() || c.keywords.isEmpty()) 0.0 else {
            val inter = seed.keywords.intersect(c.keywords).sumOf(idf)
            val base = seed.keywords.sumOf(idf)
            if (base <= 0.0) 0.0 else min(1.0, inter / base * 2.5) // sharing 40% of the seed's themes is a full match
        }
        val shared = seed.people.intersect(c.people).size
        val people = min(1.0, shared / 2.0)
        val era = if (seed.year > 0 && c.year > 0) 1.0 - min(1.0, abs(seed.year - c.year) / 30.0) else 0.5
        var s = 0.32 * genre + 0.40 * kw + 0.18 * people + 0.10 * era
        // Cartoons and family films are rarely "like" a grown-up film, and the other way round.
        if (c.animated != seed.animated) s *= 0.25
        if (c.family && !seed.family) s *= 0.5
        // A main genre missing entirely (crime film suggesting a romcom) is a poor match.
        if (sg.isNotEmpty() && cg.isNotEmpty() && sg.intersect(cg).isEmpty()) s *= 0.4
        return s
    }

    fun jaccard(a: Set<Int>, b: Set<Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Keyword rarity inside a pool of candidates: log(N / (1 + count)), at least 0.2. */
    fun idfOf(pool: Collection<Features>): (Int) -> Double {
        val counts = HashMap<Int, Int>()
        pool.forEach { f -> f.keywords.forEach { counts.merge(it, 1, Int::plus) } }
        val n = max(2, pool.size).toDouble()
        return { id -> max(0.2, ln(n / (1 + (counts[id] ?: 0)))) }
    }

    /** A candidate for a ranking: [sourceRank] = position in TMDB's own recommendations (-1 if not there). */
    data class Candidate(val f: Features, val sourceRank: Int = -1)

    /**
     * More Like This: titles most like [seed], adjusted for the watching profile's [taste].
     * [exclude] = keys never shown (hidden, the seed itself). [watched] = keys pushed down (already seen).
     * Returns candidate keys, best first, at most [max].
     */
    fun rankSimilar(
        seed: Features,
        candidates: List<Candidate>,
        taste: TasteProfile,
        exclude: Set<String>,
        watched: Set<String> = emptySet(),
        max: Int = 20,
    ): List<String> {
        val pool = candidates.filter { it.f.key != seed.key && it.f.key !in exclude }.distinctBy { it.f.key }
        val idf = idfOf(pool.map { it.f } + seed)
        return pool.map { c ->
            val sim = similarity(seed, c.f, idf)
            val rec = if (c.sourceRank >= 0) 1.0 - min(1.0, c.sourceRank / 40.0) else 0.0
            var s = 0.62 * sim + 0.13 * rec + 0.25 * quality(c.f.rating, c.f.votes)
            s *= tasteFactor(taste, c.f)
            if (c.f.votes < 60) s *= 0.4 // barely known: often a poor or mislabeled match
            if (c.f.key in watched) s *= 0.55
            c.f.key to s
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /**
     * Picked for You: [candidates] (from the profile's own seeds and liked genres) ranked by taste and quality.
     * [sourceRank] here counts how many seeds suggested the title (more seeds = stronger).
     */
    fun rankForYou(
        candidates: List<Candidate>,
        taste: TasteProfile,
        exclude: Set<String>,
        max: Int = 24,
    ): List<String> {
        val pool = candidates.filter { it.f.key !in exclude }
        val votesBySeed = pool.groupingBy { it.f.key }.eachCount()
        return pool.distinctBy { it.f.key }.map { c ->
            val fit = (taste.score(c.f) + 1.0) / 2.0 // 0..1
            val seeds = min(1.0, ((votesBySeed[c.f.key] ?: 1) - 1) / 3.0)
            var s = 0.50 * fit + 0.30 * quality(c.f.rating, c.f.votes) + 0.20 * seeds
            if (taste.score(c.f) <= -1.0) s = 0.0 // avoided genre or topic
            if (c.f.votes < 100) s *= 0.5
            c.f.key to s
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** 0 for avoided titles, else 0.7..1.3 by fit (scaled by how much the profile has to go on). */
    fun tasteFactor(taste: TasteProfile, f: Features): Double {
        val t = taste.score(f)
        if (t <= -1.0) return 0.0
        val w = 0.3 * max(0.35, taste.strength)
        return 1.0 + w * t
    }

    // =====================================================================================
    // Live TV: For You
    // =====================================================================================

    /** What a network is mostly about. Tags map to the same taste as movies (see [TAG_GENRES]). */
    enum class Tag { HISTORY, CRIME, COMEDY, MOVIES, ACTION, SPORTS, NEWS, KIDS, REALITY, DOCS, MUSIC, LOCAL }

    private val TAG_GENRES: Map<Tag, Set<Int>> = mapOf(
        Tag.HISTORY to setOf(WAR, HISTORY, DOCUMENTARY, WESTERN),
        Tag.CRIME to setOf(CRIME, MYSTERY, THRILLER),
        Tag.COMEDY to setOf(COMEDY),
        Tag.MOVIES to setOf(DRAMA, THRILLER, ACTION),
        Tag.ACTION to setOf(ACTION, 878, WESTERN),
        Tag.DOCS to setOf(DOCUMENTARY, HISTORY),
        Tag.KIDS to setOf(FAMILY, ANIMATION, KIDS),
        Tag.REALITY to setOf(REALITY),
        Tag.NEWS to setOf(NEWS),
    )

    /** Known US networks by the words in their names (lowercase, whole words or phrases). */
    private val NETWORKS: List<Pair<List<String>, Set<Tag>>> = listOf(
        listOf("history", "military history", "ahc", "american heroes", "smithsonian", "military channel") to setOf(Tag.HISTORY, Tag.DOCS),
        listOf("discovery", "science", "nat geo", "national geographic", "nat geo wild", "pbs", "vice", "curiosity", "magnolia") to setOf(Tag.DOCS),
        listOf("a&e", "a & e", "investigation discovery", "id", "oxygen", "court tv", "law & crime", "law and crime", "true crime", "reelz", "crime") to setOf(Tag.CRIME),
        listOf("comedy central", "comedy", "tbs", "adult swim", "fx", "fxx", "laff", "dry bar", "tv land", "nick at nite", "comedy tv") to setOf(Tag.COMEDY),
        listOf("amc", "tnt", "paramount network", "ifc", "sundance", "fxm", "tcm", "ion", "usa network", "usa", "bravo", "lifetime movies", "grit", "insp", "western") to setOf(Tag.MOVIES),
        listOf("hbo", "cinemax", "showtime", "starz", "epix", "mgm", "max", "encore", "the movie channel") to setOf(Tag.MOVIES, Tag.ACTION),
        listOf("syfy", "action", "grit") to setOf(Tag.ACTION),
        listOf("espn", "espn2", "espnu", "fox sports", "fs1", "fs2", "nfl network", "nfl redzone", "redzone", "nba tv", "mlb network", "nhl network",
            "cbs sports", "nbc sports", "golf", "acc network", "sec network", "big ten", "btn", "tennis", "ufc", "bein", "willow", "dazn", "marquee", "bally", "pokergo", "poker") to setOf(Tag.SPORTS),
        listOf("cnn", "fox news", "msnbc", "newsmax", "cnbc", "bloomberg", "news", "weather", "abc news", "cbs news", "nbc news", "c-span", "newsnation") to setOf(Tag.NEWS),
        listOf("disney", "nick", "nickelodeon", "cartoon", "boomerang", "pbs kids", "nick jr", "universal kids", "baby", "kids") to setOf(Tag.KIDS),
        listOf("tlc", "hgtv", "food network", "bravo", "e!", "mtv", "vh1", "we tv", "lifetime", "travel", "cooking", "own") to setOf(Tag.REALITY),
        listOf("mtv live", "vevo", "cmt", "bet", "music", "stingray") to setOf(Tag.MUSIC),
        listOf("abc", "cbs", "nbc", "fox", "cw", "pbs", "mynetwork", "telemundo", "univision") to setOf(Tag.LOCAL),
    )

    /** Big networks people tune to most: a small head start before anything is learned. */
    private val MAJOR = setOf(
        "espn", "fox sports 1", "fs1", "nfl network", "history", "discovery", "amc", "tnt", "tbs", "fx", "comedy central", "a&e",
        "usa network", "abc", "cbs", "nbc", "fox", "paramount network", "national geographic", "hbo", "investigation discovery",
    )

    private val WORD = Regex("[a-z0-9&!+'.-]+")

    private fun words(s: String): List<String> = WORD.findAll(s.lowercase(Locale.ROOT)).map { it.value }.toList()

    /** Phrases split into words once (the same few hundred are checked against every channel). */
    private val phraseWords = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun phraseOf(p: String): List<String> = phraseWords.getOrPut(p) { words(p) }

    /** True when the phrase (one or more words) appears as whole words in [w]. */
    private fun hasPhrase(w: List<String>, phrase: String): Boolean {
        val p = phraseOf(phrase)
        if (p.isEmpty() || p.size > w.size) return false
        for (i in 0..w.size - p.size) {
            var ok = true
            for (j in p.indices) if (w[i + j] != p[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    /** Network tags for a clean channel name ("ESPN" -> SPORTS, "History" -> HISTORY + DOCS). */
    fun channelTags(cleanName: String): Set<Tag> {
        val w = words(cleanName)
        val out = HashSet<Tag>()
        for ((names, tags) in NETWORKS) if (names.any { hasPhrase(w, it) }) out.addAll(tags)
        return out
    }

    /**
     * The same network under different names ("ESPN HD", "US: ESPN FHD", "ESPN (East)") -> "espn".
     * Feeds of the same network are collapsed to the best copy in For You.
     */
    fun networkKey(cleanName: String): String {
        var w = words(cleanName).filter { it !in FEED_WORDS }
        if (w.isEmpty()) w = words(cleanName)
        return w.joinToString(" ")
    }

    private val FEED_WORDS = setOf("hd", "fhd", "uhd", "sd", "4k", "east", "west", "e", "w", "us", "usa", "feed", "backup", "hevc", "1080p", "720p", "plus+")

    /** Guide words that point at a tag ("WWII", "detective", "stand-up"). */
    private val TAG_WORDS: Map<Tag, List<String>> = mapOf(
        Tag.HISTORY to listOf("war", "wwii", "ww2", "world war", "military", "battle", "army", "navy", "marines", "soldier", "soldiers", "combat", "veteran", "veterans", "d-day", "vietnam", "history"),
        Tag.CRIME to listOf("murder", "detective", "crime", "cops", "fbi", "mafia", "mob", "gangster", "heist", "killer", "homicide", "investigation", "prison", "cartel"),
        Tag.COMEDY to listOf("comedy", "stand-up", "standup", "comedian", "comedians", "sitcom", "roast", "laugh"),
        Tag.SPORTS to listOf("nfl", "nba", "mlb", "nhl", "ncaa", "football", "basketball", "baseball", "hockey"),
    )

    /** Inputs for one channel's For You score. */
    data class ChannelInput(
        val index: Int,
        val cleanName: String,
        val section: String,
        val domestic: Boolean,
        val favorite: Boolean,
        /** Minutes watched on this channel (this profile), with the last time. */
        val minutes: Double = 0.0,
        val lastWatched: Long = 0L,
        /** What's on now (guide title + description), or "". */
        val nowText: String = "",
        /** What's on in the next few hours (guide titles + descriptions), or "". */
        val soonText: String = "",
    )

    data class ChannelPick(val index: Int, val score: Double, val reason: Reason)
    enum class Reason { WATCH_AGAIN, ON_NOW, YOUR_KIND }

    /**
     * How much the profile likes each network tag, from its movie / show taste and Live TV habits.
     * [tagMinutes] = minutes watched per tag on Live TV.
     */
    fun tagWeights(taste: TasteProfile, tagMinutes: Map<Tag, Double>, kids: Boolean): Map<Tag, Double> {
        val out = HashMap<Tag, Double>()
        for (tag in Tag.entries) {
            val gs = TAG_GENRES[tag].orEmpty()
            var w = 0.0
            if (gs.isNotEmpty()) {
                w += gs.maxOf { taste.genres[it] ?: 0.0 }
                if (gs.any { it in taste.choices.likeGenres }) w += 0.6
                if (gs.any { it in taste.choices.avoidGenres }) w -= 1.5
            }
            val m = tagMinutes[tag] ?: 0.0
            if (m > 0) w += min(1.0, ln(1 + m) / ln(1 + 600.0)) // ~10 hours of a kind of channel = full weight
            out[tag] = w
        }
        // Sports have their own Games block; they still count here so the networks you watch show up.
        if (kids) {
            out[Tag.KIDS] = (out[Tag.KIDS] ?: 0.0) + 2.0
        } else {
            out[Tag.KIDS] = min(out[Tag.KIDS] ?: 0.0, -0.3) // grown-up profiles: kids channels only if watched (tagMinutes)
            if ((tagMinutes[Tag.KIDS] ?: 0.0) > 30) out[Tag.KIDS] = 0.3
        }
        return out
    }

    /**
     * For You on Live TV: one copy per network, ranked by what this profile watches, the kinds of channels
     * its taste points to, and what's on now. Only domestic channels; 24/7 loops, PPV and international
     * feeds are left out unless they were watched. Returns at most [max] picks, best first.
     */
    fun rankChannels(
        channels: List<ChannelInput>,
        taste: TasteProfile,
        tagMinutes: Map<Tag, Double>,
        kids: Boolean,
        now: Long,
        max: Int = 60,
    ): List<ChannelPick> {
        val matcher = ProgrammeMatcher(taste, tagMinutes, kids)
        val weights = matcher.weights
        val avoidWords = matcher.avoidWords
        val best = HashMap<String, ChannelPick>()
        for (c in channels) {
            val watched = c.minutes >= 3.0
            val excludedSection = c.section == LiveOrganizer.H24 || c.section == LiveOrganizer.EVENTS || c.section == LiveOrganizer.INTERNATIONAL
            if (!watched && !c.favorite && (!c.domestic || excludedSection)) continue
            val tags = channelTags(c.cleanName)
            if (kids && Tag.KIDS !in tags && !watched && !c.favorite) continue
            val nameWords = words(c.cleanName)
            if (avoidWords.any { hasPhrase(nameWords, it) }) continue

            // Habit: minutes watched, fading over a month.
            val recency = if (c.lastWatched > 0) exp(-ln(2.0) * max(0.0, (now - c.lastWatched) / DAY_MS) / 30.0) else 0.0
            val habit = if (c.minutes > 0) min(1.0, ln(1 + c.minutes) / ln(1 + 240.0)) * (0.5 + 0.5 * recency) else 0.0
            val kind = if (tags.isEmpty()) 0.0 else tags.maxOf { weights[it] ?: 0.0 }
            // On now: guide text matching liked tags or topics.
            val onNow = matcher.score(c.nowText)
            // Coming up in the next few hours (a poker final at 9, a WWII documentary at 10) counts a little less.
            val soon = max(0.0, matcher.score(c.soonText))
            val major = if (MAJOR.any { hasPhrase(nameWords, it) && nameWords.size <= phraseOf(it).size + 2 }) 0.25 else 0.0
            val score = 3.0 * habit + (if (c.favorite) 1.2 else 0.0) + 1.2 * kind + 1.0 * onNow + 0.5 * soon + major
            if (score <= 0.15) continue
            val reason = when {
                habit >= 0.25 || c.favorite -> Reason.WATCH_AGAIN
                onNow >= 0.5 -> Reason.ON_NOW
                else -> Reason.YOUR_KIND
            }
            val key = networkKey(c.cleanName)
            val prev = best[key]
            // Keep the copy that scores best; on a tie, the earlier one (playlists list the main feed first).
            if (prev == null || score > prev.score + 1e-9) best[key] = ChannelPick(c.index, score, reason)
        }
        return best.values.sortedByDescending { it.score }.take(max)
    }

    /**
     * How well one programme (guide title + description) fits a profile: 1 = a topic it asked for
     * ("poker", "stand-up comedy"), up to 0.6 = the kind of show it likes (war, crime, comedy), 0 = nothing
     * known, below 0 = a topic it skips. Used for the For You ranking and to highlight shows in My Guide.
     */
    class ProgrammeMatcher(taste: TasteProfile, tagMinutes: Map<Tag, Double> = emptyMap(), kids: Boolean = false) {
        val weights: Map<Tag, Double> = tagWeights(taste, tagMinutes, kids)
        val likeWords: List<String> = taste.choices.likeWords.map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotBlank() }
        val avoidWords: List<String> = taste.choices.avoidWords.map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotBlank() }

        fun score(text: String): Double {
            if (text.isBlank()) return 0.0
            val w = words(text)
            if (w.isEmpty()) return 0.0
            if (avoidWords.any { hasPhrase(w, it) }) return -1.0
            if (likeWords.any { hasPhrase(w, it) }) return 1.0
            var s = 0.0
            for ((tag, list) in TAG_WORDS) {
                val tw = weights[tag] ?: 0.0
                if (tw > 0.2 && list.any { hasPhrase(w, it) }) s = max(s, 0.6 * min(1.0, tw))
            }
            return s
        }
    }

    private fun clamp(v: Double) = clampValue(v)
}
