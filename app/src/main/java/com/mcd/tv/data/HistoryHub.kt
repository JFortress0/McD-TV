package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * The History hub (Browse > History, and the History row on Home). Gathers the shelves from TMDB and the
 * profile's own history; the rules live in HistoryEras.kt.
 *  - Ken Burns: his real crew credits (TMDB person lookup), every documentary he made.
 *  - Historical Dramas: History-genre dramas found by era and presidential plot keywords, kept only when the
 *    keywords say they depict one of the eras (or a president); the hand-placed marquee dramas lead.
 *  - By Era: titles found by each era's plot keywords, kept only when their own keywords place them there.
 *  - More Like <title>: More Like This (Recommender) for the newest history title the profile watched.
 * Every shelf lists titles whether or not a source has them yet, like the rest of Jarvis: Detail and
 * Sources show what can play. "Not for me" titles and Settings > Taste "Topics to skip" are left out
 * (Ken Burns keeps his whole filmography apart from "Not for me").
 *
 * Lookups that never change (keyword ids, marquee title ids, Ken Burns' id, each title's eras) are kept on
 * the TV, so only the first visit makes many small requests.
 */
object HistoryHub {
    private val net = Semaphore(6) // parallel TMDB requests
    private const val SHELF_TTL_MS = 15 * 60_000L
    private const val CLASS_MAX = 1500
    /** Bump when the rules in HistoryEras change, so saved eras are worked out again. */
    private const val CLASS_VERSION = 1

    /** Genres the personal row keeps: History, Documentary, War, War & Politics, Western. */
    private val HISTORYISH = setOf(36, 99, 10752, 10768, 37)

    /** A shelf of the hub. [id]: "more", "kenburns", "dramas" or "era:<era id>". */
    data class Shelf(val id: String, val label: String, val titles: List<Title>)

    private fun keyOf(t: Title) = "${t.type}:${t.id}"

    /** The History row on Home: every profile except Kids whose Settings > Taste likes History or War (Dad by default). */
    fun homeRowFor(profile: String = Prefs.activeProfile): Boolean {
        if (profile == Prefs.KIDS_PROFILE) return false
        val likes = Recommender.choicesJson(profile).optJSONArray("likeGenres") ?: return false
        return (0 until likes.length()).any { likes.optInt(it) == HistoryEras.HISTORY || likes.optInt(it) == HistoryEras.WAR }
    }

    // ---------------------------------------------------------------- shelf cache (memory)

    private class Cached(val titles: List<Title>, val at: Long)
    private val shelves = HashMap<String, Cached>()

    /** One shelf, reused for 15 minutes (the hub and its See all grids share it). */
    private suspend fun cached(profile: String, id: String, build: suspend () -> List<Title>): List<Title> {
        val k = "$profile|$id"
        val now = System.currentTimeMillis()
        synchronized(shelves) { shelves[k] }?.let { if (now - it.at < SHELF_TTL_MS) return it.titles }
        val list = build()
        if (list.isNotEmpty()) synchronized(shelves) { shelves[k] = Cached(list, now) }
        return list
    }

    // ---------------------------------------------------------------- lookups kept on the TV

    private fun loadMap(key: String): JSONObject = runCatching { JSONObject(Prefs.json(key).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    /** TMDB keyword ids for names (exact names only; 0 = TMDB has none). */
    private suspend fun keywordIds(names: List<String>): List<Int> = coroutineScope {
        val wanted = names.distinct()
        val cache = loadMap("history_kwids")
        val found = wanted.filter { !cache.has(it) }
            .map { n -> async { n to runCatching { net.withPermit { Tmdb.keywordId(n, exact = true) } } } }.awaitAll()
            .filter { it.second.isSuccess } // offline: try again next time
        if (found.isNotEmpty()) {
            found.forEach { (n, r) -> cache.put(n, r.getOrNull() ?: 0) }
            synchronized(this@HistoryHub) {
                val fresh = loadMap("history_kwids")
                found.forEach { (n, r) -> fresh.put(n, r.getOrNull() ?: 0) }
                Prefs.putJson("history_kwids", fresh.toString())
            }
        }
        wanted.mapNotNull { n -> cache.optInt(n, 0).takeIf { it > 0 } }
    }

    private fun Title.toJson() = JSONObject().put("id", id).put("type", type).put("name", name).put("overview", overview)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("rating", rating).put("year", year)
        .put("date", releaseDate).put("lang", language).put("votes", votes)
        .put("genres", org.json.JSONArray(genreIds)).put("oc", org.json.JSONArray(originCountries))

    private fun JSONObject.toTitle() = Title(
        optInt("id"), optString("type"), optString("name"), optString("overview"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null }, optDouble("rating", 0.0), optString("year"),
        originCountries = optJSONArray("oc")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        language = optString("lang"), releaseDate = optString("date"), votes = optInt("votes"),
        genreIds = optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
    )

    /**
     * The marquee titles found on TMDB (exact name, within a year), with their entries. Kept on the TV for
     * 30 days; a title TMDB doesn't have yet is looked for again after a week.
     */
    private suspend fun marqueeTitles(): List<Pair<Marquee, Title>> = marqueeLock.withLock {
        marqueeMem ?: marqueeLoad().also { if (it.isNotEmpty()) marqueeMem = it }
    }
    private val marqueeLock = Mutex() // the shelves load in parallel; look the marquee titles up once
    @Volatile private var marqueeMem: List<Pair<Marquee, Title>>? = null

    private suspend fun marqueeLoad(): List<Pair<Marquee, Title>> = coroutineScope {
        val now = System.currentTimeMillis()
        val cache = loadMap("history_marquee")
        fun k(m: Marquee) = "${m.type}|${m.name}|${m.year}"
        val got = HistoryEras.MARQUEE.map { m ->
            async {
                val c = cache.optJSONObject(k(m))
                val age = now - (c?.optLong("at") ?: 0L)
                val fresh = c != null && age < (if (c.has("t")) 30L else 7L) * 86_400_000L
                if (fresh) return@async Triple(m, c?.optJSONObject("t")?.toTitle(), false)
                val r = runCatching {
                    net.withPermit {
                        listOf(m.year, m.year - 1, m.year + 1).firstNotNullOfOrNull { y -> Tmdb.findTitle(m.type, m.name, y, exact = true) }
                    }
                }
                if (r.isFailure) Triple(m, c?.optJSONObject("t")?.toTitle(), false) else Triple(m, r.getOrNull(), true)
            }
        }.awaitAll()
        if (got.any { it.third }) synchronized(this@HistoryHub) {
            val fresh = loadMap("history_marquee")
            got.filter { it.third }.forEach { (m, t) ->
                fresh.put(k(m), JSONObject().put("at", now).apply { if (t != null) put("t", t.toJson()) })
            }
            Prefs.putJson("history_marquee", fresh.toString())
        }
        got.mapNotNull { (m, t, _) -> t?.let { m to it } }
    }

    private suspend fun kenBurnsId(): Int? {
        Prefs.json("history_kenburns").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val id = net.withPermit { Tmdb.findPerson("Ken Burns", "Directing") } ?: return null
        Prefs.putJson("history_kenburns", id.toString())
        return id
    }

    // ---------------------------------------------------------------- eras of each title

    private class Classified(val result: EraResult, val keywords: List<String>)
    private val classCache = LinkedHashMap<String, Classified>()
    @Volatile private var classLoaded = false

    private fun loadClassCache() {
        if (classLoaded) return
        synchronized(classCache) {
            if (classLoaded) return
            runCatching {
                val o = JSONObject(Prefs.json("history_class").ifBlank { "{}" })
                if (o.optInt("v") == CLASS_VERSION) {
                    val m = o.optJSONObject("m") ?: JSONObject()
                    m.keys().forEach { k -> classCache[k] = decode(m.optString(k)) }
                }
            }
            classLoaded = true
        }
    }

    private fun saveClassCache() = synchronized(classCache) {
        while (classCache.size > CLASS_MAX) classCache.remove(classCache.keys.first())
        val m = JSONObject()
        classCache.forEach { (k, c) -> m.put(k, encode(c)) }
        Prefs.putJson("history_class", JSONObject().put("v", CLASS_VERSION).put("m", m).toString())
    }

    /** "founding:4,civilwar:2|p|keyword;keyword". Keywords are kept only for titles on a shelf (Topics to skip). */
    private fun encode(c: Classified): String =
        c.result.eras.joinToString(",") { "${it.id}:${c.result.scores[it] ?: 0}" } + "|" + (if (c.result.presidential) "p" else "") + "|" +
            c.keywords.take(30).joinToString(";") { it.replace(";", " ").replace("|", " ") }

    private fun decode(s: String): Classified {
        val parts = s.split("|")
        val scores = parts.getOrElse(0) { "" }.split(",").mapNotNull { e ->
            val era = Era.fromId(e.substringBefore(":")) ?: return@mapNotNull null
            era to (e.substringAfter(":").toIntOrNull() ?: HistoryEras.MIN_SCORE)
        }
        val kws = parts.getOrElse(2) { "" }.split(";").filter { it.isNotBlank() }
        return Classified(EraResult(scores.map { it.first }, parts.getOrElse(1) { "" } == "p", scores.toMap()), kws)
    }

    /** Eras of each title, from its plot keywords (one small request per title the TV has not seen). */
    private suspend fun classifyAll(titles: List<Title>): Map<String, Classified> = coroutineScope {
        loadClassCache()
        val out = HashMap<String, Classified>()
        val missing = ArrayList<Title>()
        synchronized(classCache) {
            titles.distinctBy(::keyOf).forEach { t -> classCache[keyOf(t)]?.let { out[keyOf(t)] = it } ?: missing.add(t) }
        }
        val fetched = missing.map { t -> async { t to runCatching { net.withPermit { Tmdb.keywordNames(t.type, t.id) } }.getOrNull() } }.awaitAll()
        var changed = false
        for ((t, names) in fetched) {
            val r = HistoryEras.classify(t.name, t.year.toIntOrNull() ?: 0, t.genreIds.toSet(), names ?: emptyList(), t.language)
            val c = Classified(r, if (r.eras.isNotEmpty() || r.presidential) names.orEmpty().map(HistoryEras::norm) else emptyList())
            out[keyOf(t)] = c
            if (names != null) { synchronized(classCache) { classCache[keyOf(t)] = c }; changed = true } // offline: try again next time
        }
        if (changed) saveClassCache()
        out
    }

    // ---------------------------------------------------------------- filters

    /** "Not for me" titles, and titles whose keywords or name hit a Settings > Taste "Topics to skip" word. */
    private fun blocked(profile: String): (Title, Classified?) -> Boolean {
        val hidden = Library.hidden(profile).map(::keyOf).toSet()
        val words = Recommender.choicesJson(profile).optJSONArray("avoidWords")
        val avoid = if (words == null) emptyList() else (0 until words.length()).map { HistoryEras.norm(words.optString(it)) }.filter { it.isNotEmpty() }
        return { t, c ->
            keyOf(t) in hidden || avoid.any { w ->
                val name = " ${HistoryEras.norm(t.name)} "
                name.contains(" $w ") || c?.keywords.orEmpty().any { k -> " $k ".contains(" $w ") }
            }
        }
    }

    private fun american(t: Title) = if (t.originCountries.isNotEmpty()) "US" in t.originCountries else t.language == "en"

    // ---------------------------------------------------------------- shelves

    /** Titles that depict [era]: the marquee titles first, then the best keyword matches. */
    suspend fun eraShelf(era: Era, profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "era:${era.id}") { eraShelfInner(era, profile) }.take(max)

    private suspend fun eraShelfInner(era: Era, profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().filter { era in it.first.eras }.map { it.second } }
        val ids = keywordIds(HistoryEras.DISCOVER_TERMS.getValue(era)).joinToString("|")
        val pools = if (ids.isEmpty()) emptyList() else listOf("movie" to 1, "movie" to 2, "tv" to 1).map { (type, page) ->
            async {
                runCatching {
                    net.withPermit {
                        Tmdb.discover(
                            type,
                            mapOf(
                                "with_keywords" to ids, "sort_by" to "vote_count.desc",
                                "vote_count.gte" to (if (type == "movie") "40" else "10"),
                                "without_genres" to (if (type == "movie") "16,10751,14,878,27" else "16,10762,10765"),
                            ),
                            page,
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }
        val lead = marquee.await()
        val found = pools.awaitAll().flatten()
        val cls = classifyAll(lead + found)
        val block = blocked(profile)
        val leadKeys = lead.map(::keyOf).toSet()
        val rest = found.distinctBy(::keyOf)
            .filter { keyOf(it) !in leadKeys && cls[keyOf(it)]?.result?.eras?.contains(era) == true }
            .sortedByDescending { HistoryEras.rank(cls[keyOf(it)]?.result?.scores?.get(era) ?: 0, it.votes, it.rating, american(it)) }
        (lead + rest).filterNot { block(it, cls[keyOf(it)]) }.take(60)
    }

    /** Biopics and period dramas: Young Washington and its kin (founding era, wars, presidents). */
    suspend fun dramas(profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "dramas") { dramasInner(profile) }.take(max)

    private suspend fun dramasInner(profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().filter { it.first.drama }.map { it.second } }
        val terms = HistoryEras.DISCOVER_TERMS.filterKeys { it != Era.WEST }.values.flatten() + HistoryEras.PRESIDENTIAL_TERMS
        val ids = keywordIds(terms).joinToString("|")
        val queries = listOf(
            Triple("movie", 1, mapOf("with_genres" to "36", "without_genres" to "99,16,10751,14,878,27", "vote_count.gte" to "150")),
            Triple("movie", 2, mapOf("with_genres" to "36", "without_genres" to "99,16,10751,14,878,27", "vote_count.gte" to "150")),
            Triple("tv", 1, mapOf("with_genres" to "18", "without_genres" to "99,16,10762,10765", "vote_count.gte" to "30")),
        )
        val pools = if (ids.isEmpty()) emptyList() else queries.map { (type, page, p) ->
            async {
                runCatching { net.withPermit { Tmdb.discover(type, p + mapOf("with_keywords" to ids, "sort_by" to "vote_count.desc"), page) } }
                    .getOrDefault(emptyList())
            }
        }
        val lead = marquee.await()
        val found = pools.awaitAll().flatten()
        val cls = classifyAll(lead + found)
        val block = blocked(profile)
        val leadKeys = lead.map(::keyOf).toSet()
        val rest = found.distinctBy(::keyOf)
            .filter { t -> keyOf(t) !in leadKeys && cls[keyOf(t)]?.let { HistoryEras.isHistoricalDrama(t.genreIds.toSet(), it.result) } == true }
            .sortedByDescending { HistoryEras.rank(cls[keyOf(it)]?.result?.best ?: 0, it.votes, it.rating, american(it)) }
        (lead + rest).filterNot { block(it, cls[keyOf(it)]) }.take(60)
    }

    /** Every documentary Ken Burns made, from his TMDB credits (see HistoryEras.kenBurnsOrder). */
    suspend fun kenBurns(profile: String = Prefs.activeProfile): List<Title> = cached(profile, "kenburns") {
        val id = kenBurnsId() ?: throw IllegalStateException("Couldn't find Ken Burns on TMDB.")
        val p = net.withPermit { Tmdb.person(id) }
        val byKey = p.crew.associate { keyOf(it.first) to it.first }
        val credits = p.crew.map { (t, jobs) -> Credit(keyOf(t), t.name, t.releaseDate, t.genreIds.toSet(), jobs.split(", "), t.votes) }
        val hidden = Library.hidden(profile).map(::keyOf).toSet()
        HistoryEras.kenBurnsOrder(credits, today()).filter { it !in hidden }.mapNotNull(byKey::get)
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ---------------------------------------------------------------- personal

    /** A title from the TV's cached features (genres, year, language), for classification. */
    private fun stub(type: String, id: Int, name: String, f: Features) =
        Title(id, type, name, "", null, null, f.rating, if (f.year > 0) f.year.toString() else "", language = f.language, genreIds = f.genres.toList(), votes = f.votes)

    /**
     * The newest history title this profile watched (then its favorites and likes) and the titles most like
     * it that are history too. Null when it has watched no history yet.
     */
    suspend fun moreLike(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? = try {
        moreLikeInner(profile)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("HistoryHub", "More Like row failed: $e")
        null
    }

    private suspend fun moreLikeInner(profile: String): Pair<String, List<Title>>? = coroutineScope {
        val history = Library.history(profile)
        val watched = history.map { Watched(it.meta.historyKey, it.meta.name, it.progress, it.finished, it.updatedAt) } +
            (Library.likes(profile) + Library.favorites(profile)).map { Watched(keyOf(it), it.name, 1f, true, 0L) }
        // Check the newest dozen the profile got into: genres from the cached features, eras from keywords.
        val recent = watched.filter { it.finished || it.progress >= 0.25f }.sortedByDescending { it.at }.distinctBy { it.key }.take(12)
        val stubs = recent.map { w ->
            async {
                val type = w.key.substringBefore(':')
                val id = w.key.substringAfter(':').toIntOrNull() ?: 0
                Recommender.features(type, id)?.let { stub(type, id, w.name, it) }
            }
        }.awaitAll().filterNotNull()
        val kb = runCatching { kenBurns(profile).map(::keyOf).toSet() }.getOrDefault(emptySet())
        val cls = classifyAll(stubs.filter { t -> t.genreIds.any { it in HISTORYISH } })
        val verdict = stubs.associate { t ->
            keyOf(t) to HistoryEras.isHistoryTitle(t.genreIds.toSet(), cls[keyOf(t)]?.result ?: EraResult.NONE, keyOf(t) in kb)
        }
        val seed = HistoryEras.pickSeed(recent) { verdict[it] == true }
        if (seed == null) {
            android.util.Log.i("HistoryHub", "no seed: recent=${recent.map { it.key }} checked=$verdict")
            return@coroutineScope null
        }
        val type = seed.key.substringBefore(':')
        val id = seed.key.substringAfter(':').toInt()
        val like = Recommender.moreLikeThis(type, id, emptyList(), profile, 40).filter { t -> t.genreIds.any { it in HISTORYISH } }
        // Few history titles in More Like This: add the seed's era shelf, and Ken Burns when it is one of his.
        val seedStub = stubs.first { keyOf(it) == seed.key }
        val seedEra = (cls[seed.key] ?: classifyAll(listOf(seedStub))[seed.key])?.result?.eras?.firstOrNull()
        val extra = if (like.size >= 12) emptyList() else
            (if (seedEra != null) eraShelf(seedEra, profile) else emptyList()) +
                (if (seed.key in kb) runCatching { kenBurns(profile) }.getOrDefault(emptyList()) else emptyList())
        android.util.Log.i("HistoryHub", "seed ${seed.key} ${seed.name} genres=${seedStub.genreIds} era=$seedEra like=${like.size} extra=${extra.size}")
        val seen = history.filter { it.finished || it.progress >= 0.6f }.map { it.meta.historyKey }.toSet() + seed.key
        val block = blocked(profile)
        val list = (like + extra).distinctBy(::keyOf).filter { keyOf(it) !in seen && !block(it, null) }.take(20)
        if (list.size >= 4) seed.name to list else null
    }

    /** Era shelves in order: the eras this profile watched most first (so the hub follows what it watches). */
    suspend fun eraOrder(profile: String = Prefs.activeProfile): List<Era> = try {
        coroutineScope {
            val recent = Library.history(profile).filter { it.finished || it.progress >= 0.25f }.take(40)
            val stubs = recent.map { h ->
                async { Recommender.features(h.meta.type, h.meta.tmdbId)?.let { stub(h.meta.type, h.meta.tmdbId, h.meta.name, it) } }
            }.awaitAll().filterNotNull().filter { t -> t.genreIds.any { it in HISTORYISH } }
            val counts = HashMap<Era, Int>()
            classifyAll(stubs).values.forEach { c -> c.result.eras.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            HistoryEras.orderEras(counts)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Era.entries.toList()
    }

    /**
     * Home's History row: More Like <the latest history title>, else Ken Burns. When Home's Because You Watched
     * already shows that same title, Ken Burns is shown instead, so the two rows never repeat each other.
     */
    suspend fun homeRow(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? {
        val now = System.currentTimeMillis()
        val because = Library.history(profile)
            .filter { (it.finished || it.progress >= 0.7f) && now - it.updatedAt < 60 * 86_400_000L }
            .maxByOrNull { it.updatedAt }?.meta?.name
        moreLike(profile)?.let { (name, list) -> if (name != because) return "History · More Like $name" to list }
        return runCatching { kenBurns(profile) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "History · Ken Burns" to it.take(20) }
    }

    /** A shelf by id, for its See all grid. */
    suspend fun shelf(id: String, profile: String = Prefs.activeProfile): List<Title> = when {
        id == "kenburns" -> kenBurns(profile)
        id == "dramas" -> dramas(profile, 60)
        id == "more" -> moreLike(profile)?.second ?: emptyList()
        id.startsWith("era:") -> Era.fromId(id.substringAfter(':'))?.let { eraShelf(it, profile, 60) } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Automated QA only: marks one title watched ("tv|The Civil War|1990"), so the emulator run can show the
     * personal row seeded from real history.
     */
    suspend fun qaWatch(spec: String) {
        val p = spec.split("|")
        if (p.size < 2) return
        val t = Tmdb.findTitle(p[0], p[1], p.getOrNull(2)?.toIntOrNull(), exact = true)
        android.util.Log.i("HistoryHub", "qa_watched $spec -> ${t?.type}:${t?.id}")
        if (t != null) Library.markWatched(PlayMeta(t.type, t.id, t.name, t.poster, t.backdrop))
    }
}
