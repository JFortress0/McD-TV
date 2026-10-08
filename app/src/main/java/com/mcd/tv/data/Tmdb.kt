package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A movie or TV show as shown on a card. type is "movie" or "tv". */
data class Title(
    val id: Int,
    val type: String,
    val name: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val year: String,
    val originCountries: List<String> = emptyList(),
    val language: String = "",
)

/** Where titles may come from. Set in Settings or the web page. */
enum class OriginFilter(val key: String, val label: String) {
    ALL("all", "All countries"),
    US("us", "Made in the US only"),
    NO_ASIA("no_asia", "Hide Asian-made titles"),
}

/** East, South, Southeast and Central Asia (country codes and original languages). */
private val ASIAN_COUNTRIES = setOf(
    "JP", "KR", "KP", "CN", "TW", "HK", "MO", "MN", "IN", "PK", "BD", "LK", "NP", "BT", "MV", "AF",
    "TH", "VN", "LA", "KH", "MM", "MY", "SG", "ID", "PH", "BN", "TL", "KZ", "UZ", "TM", "KG", "TJ",
)
private val ASIAN_LANGUAGES = setOf(
    "ja", "ko", "zh", "cn", "hi", "ta", "te", "ml", "kn", "mr", "bn", "pa", "gu", "ur", "or", "as",
    "th", "vi", "id", "ms", "tl", "km", "lo", "my", "mn", "ne", "si", "dz", "kk", "uz",
)

/** True if the title passes the user's origin setting. */
fun Title.allowedByOrigin(f: OriginFilter): Boolean = when (f) {
    OriginFilter.ALL -> true
    OriginFilter.US -> if (originCountries.isNotEmpty()) "US" in originCountries else language == "en"
    OriginFilter.NO_ASIA -> originCountries.none { it in ASIAN_COUNTRIES } && language !in ASIAN_LANGUAGES
}

data class Genre(val id: Int, val name: String)

val MOVIE_GENRES = listOf(
    Genre(28, "Action"), Genre(12, "Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
    Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(14, "Fantasy"), Genre(36, "History"),
    Genre(27, "Horror"), Genre(10402, "Music"), Genre(9648, "Mystery"), Genre(10749, "Romance"),
    Genre(878, "Sci-Fi"), Genre(53, "Thriller"), Genre(10752, "War"), Genre(37, "Western"),
)
val TV_GENRES = listOf(
    Genre(10759, "Action & Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
    Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(10762, "Kids"), Genre(9648, "Mystery"),
    Genre(10764, "Reality"), Genre(10765, "Sci-Fi & Fantasy"), Genre(10768, "War & Politics"), Genre(37, "Western"),
)

data class CastMember(val name: String, val character: String, val photo: String?)
data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int)
data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val still: String?,
    val airDate: String,
)

data class Details(
    val title: Title,
    val tagline: String,
    val runtimeMin: Int,
    val genres: List<String>,
    val imdbId: String?,
    val cast: List<CastMember>,
    val similar: List<Title>,
    val seasons: List<SeasonInfo>,
    val nextEpisode: Episode?,
    /** US subscription services carrying this title (e.g. Netflix), from TMDB / JustWatch. */
    val providers: List<Service> = emptyList(),
)

/** Streaming services shown on the Services screen (TMDB watch-provider ids, US). */
data class Service(val id: Int, val name: String) {
    /** Fire TV / Android TV app packages for each service, tried in order. */
    val packages: List<String> get() = when (id) {
        8 -> listOf("com.netflix.ninja", "com.netflix.mediaclient")
        9, 119 -> listOf("com.amazon.avod", "com.amazon.amazonvideo.livingroom")
        337 -> listOf("com.disney.disneyplus")
        15 -> listOf("com.hulu.livingroomplus", "com.hulu.plus")
        350, 2 -> listOf("com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv")
        386, 387 -> listOf("com.peacocktv.peacockandroid")
        1899, 384 -> listOf("com.wbd.stream", "com.hbo.hbonow")
        531, 2303 -> listOf("com.cbs.ott", "com.cbs.app")
        283 -> listOf("com.crunchyroll.crunchyroid")
        43 -> listOf("com.bydeluxe.d3.android.program.starz")
        else -> emptyList()
    }
}

val SERVICES = listOf(
    Service(8, "Netflix"), Service(9, "Amazon Prime"), Service(337, "Disney+"), Service(15, "Hulu"),
    Service(350, "Apple TV+"), Service(386, "Peacock"), Service(1899, "Max"), Service(531, "Paramount+"),
    Service(283, "Crunchyroll"), Service(43, "Starz"),
)

/** JSON null-safe string (Android's optString returns the text "null" for JSON nulls). */
fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * TMDB client: posters, descriptions, cast, seasons. Needs a free TMDB API key,
 * entered from your phone (Settings > Phone setup). Accepts a v3 key or a v4 read token.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"

    fun img(path: String?, size: String = "w342"): String? = path?.let { IMG + size + it }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val key = Prefs.tmdbKey
        if (key.isBlank()) throw IllegalStateException("Add your TMDB API key: Settings > Phone setup")
        val all = params.toMutableMap()
        val headers = mutableMapOf<String, String>()
        if (key.length > 40) headers["Authorization"] = "Bearer $key" else all["api_key"] = key
        all.putIfAbsent("language", "en-US")
        val url = "$BASE$path?" + Http.form(all)
        return JSONObject(Http.get(url, headers))
    }

    private fun parse(o: JSONObject, forcedType: String?): Title? {
        val type = forcedType ?: o.s("media_type") ?: return null
        if (type != "movie" && type != "tv") return null
        val date = o.s("release_date") ?: o.s("first_air_date") ?: ""
        return Title(
            id = o.optInt("id"),
            type = type,
            name = o.s("title") ?: o.s("name") ?: "Untitled",
            overview = o.s("overview") ?: "",
            poster = o.s("poster_path"),
            backdrop = o.s("backdrop_path"),
            rating = o.optDouble("vote_average", 0.0),
            year = date.take(4),
            originCountries = (o.optJSONArray("origin_country")
                ?: o.optJSONArray("production_countries"))?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    when (val v = a.get(i)) { is String -> v; is JSONObject -> v.s("iso_3166_1"); else -> null }
                }
            } ?: emptyList(),
            language = o.s("original_language") ?: "",
        )
    }

    /** Every list in the app passes through here, so the origin setting applies everywhere. */
    private fun parseList(arr: JSONArray?, forcedType: String?): List<Title> {
        if (arr == null) return emptyList()
        val f = Prefs.origin
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), forcedType) }.filter { it.allowedByOrigin(f) }
    }

    /** Adds TMDB's own origin filter where it supports one (discover lists), so pages stay full. */
    private fun originParams(): Map<String, String> =
        if (Prefs.origin == OriginFilter.US) mapOf("with_origin_country" to "US") else emptyMap()

    suspend fun byGenre(type: String, genreId: Int, topRated: Boolean, page: Int = 1): List<Title> = discover(
        type,
        buildMap {
            put("with_genres", genreId.toString())
            if (topRated) { put("sort_by", "vote_average.desc"); put("vote_count.gte", if (type == "movie") "1000" else "300") }
        },
        page,
    )

    private fun region(): Map<String, String> = if (Prefs.usOnly) mapOf("region" to "US") else emptyMap()

    private suspend fun list(path: String, type: String?, params: Map<String, String> = emptyMap()) =
        parseList(get(path, params).optJSONArray("results"), type)

    suspend fun trending() = list("/trending/all/week", null)
    suspend fun popular(type: String) = list("/$type/popular", type, region())
    /**
     * Top Rated, US edition: American titles only, ranked by vote average, with a
     * minimum vote count so a handful of votes can't top the chart. Movies are limited
     * to US-certified titles (G through R). Note: no public source splits ratings by
     * the voter's country or age, so this filters the titles, not the voters.
     */
    suspend fun topRated(type: String): List<Title> {
        val p = mutableMapOf(
            "sort_by" to "vote_average.desc",
            "vote_count.gte" to if (type == "movie") "2500" else "800",
            "with_origin_country" to "US",
        )
        if (type == "movie") {
            p["certification_country"] = "US"
            p["certification"] = "G|PG|PG-13|R"
        }
        return discover(type, p)
    }
    suspend fun nowPlaying() = list("/movie/now_playing", "movie", region())
    suspend fun search(q: String) = list("/search/multi", null, mapOf("query" to q, "include_adult" to "false"))

    suspend fun discover(type: String, params: Map<String, String>, page: Int = 1): List<Title> {
        val p = params.toMutableMap()
        p["page"] = page.toString()
        p.putIfAbsent("sort_by", "popularity.desc")
        p["include_adult"] = "false" // adult titles are never shown anywhere in McD TV
        originParams().forEach { (k, v) -> p.putIfAbsent(k, v) }
        if (Prefs.usOnly) p.putIfAbsent("watch_region", "US")
        return list("/discover/$type", type, p)
    }

    suspend fun byService(type: String, providerId: Int, page: Int = 1) = discover(
        type,
        mapOf("with_watch_providers" to providerId.toString(), "watch_region" to "US"),
        page,
    )

    suspend fun byYear(type: String, year: Int) = discover(
        type,
        if (type == "movie") mapOf("primary_release_year" to "$year") else mapOf("first_air_date_year" to "$year"),
    )

    /** Family-friendly picks for Family Movie Night (genre 10751 = Family). */
    suspend fun familyMovies(page: Int) = discover(
        "movie",
        mapOf("with_genres" to "10751", "vote_count.gte" to "300", "certification_country" to "US", "certification.lte" to "PG"),
        page,
    )

    suspend fun details(type: String, id: Int): Details {
        val o = get("/$type/$id", mapOf("append_to_response" to "credits,similar,external_ids,watch/providers"))
        val t = parse(o, type) ?: throw IllegalStateException("Not found")
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val castArr = o.optJSONObject("credits")?.optJSONArray("cast")
        val cast = castArr?.let { a ->
            (0 until minOf(a.length(), 20)).map {
                val c = a.getJSONObject(it)
                CastMember(c.s("name") ?: "", c.s("character") ?: "", c.s("profile_path"))
            }
        } ?: emptyList()
        val seasons = o.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optInt("season_number") > 0 }
                .map { SeasonInfo(it.optInt("season_number"), it.s("name") ?: "Season", it.optInt("episode_count")) }
        } ?: emptyList()
        val runtime = if (type == "movie") o.optInt("runtime") else
            o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else 0 } ?: 0
        val imdb = o.s("imdb_id") ?: o.optJSONObject("external_ids")?.s("imdb_id")
        val next = o.optJSONObject("next_episode_to_air")?.let { parseEpisode(it) }
        return Details(
            title = t,
            tagline = o.s("tagline") ?: "",
            runtimeMin = runtime,
            genres = genres,
            imdbId = imdb,
            cast = cast,
            similar = parseList(o.optJSONObject("similar")?.optJSONArray("results"), type),
            seasons = seasons,
            nextEpisode = next,
            providers = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
                ?.optJSONArray("flatrate")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map { Service(it.optInt("provider_id"), it.s("provider_name") ?: "") }
                } ?: emptyList(),
        )
    }

    private fun parseEpisode(e: JSONObject) = Episode(
        season = e.optInt("season_number"),
        number = e.optInt("episode_number"),
        name = e.s("name") ?: "Episode",
        overview = e.s("overview") ?: "",
        still = e.s("still_path"),
        airDate = e.s("air_date") ?: "",
    )

    suspend fun season(tvId: Int, season: Int): List<Episode> {
        val o = get("/tv/$tvId/season/$season")
        val a = o.optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).map { parseEpisode(a.getJSONObject(it)) }
    }
}
