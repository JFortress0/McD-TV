package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Scores from several sites for one title. null = not available. */
data class Ratings(
    val imdb: Double? = null,
    val rtCritics: Int? = null,   // Rotten Tomatoes Tomatometer (critics), percent
    val rtAudience: Int? = null,  // Rotten Tomatoes Popcornmeter (audience), percent
    val metacritic: Int? = null,
    val letterboxd: Double? = null,
)

/**
 * IMDb, Rotten Tomatoes (critics and audience) and Metacritic scores from MDBList,
 * a free ratings service (mdblist.com). Needs a free MDBList API key: GitHub secret
 * MDBLIST_API_KEY, or the Jarvis Control page.
 */
object RatingsSource {
    /** Results per IMDb id ("tt…") or TMDB id ("tm:movie:603"), including "no scores" results, so misses are not asked for again. */
    private val cache = ConcurrentHashMap<String, Ratings>()

    /** At most 4 MDBList requests at a time (poster rows ask for many titles at once). */
    private val gate = Semaphore(4)

    /** After a key / rate-limit error, poster lookups pause for a while instead of hammering MDBList. */
    @Volatile private var pausedUntil = 0L

    val configured: Boolean get() = Prefs.mdblistKey.isNotBlank()

    suspend fun forImdb(imdbId: String): Ratings {
        cache[imdbId]?.let { return it }
        if (!configured) return Ratings()
        return fetch(imdbId, "i=${Http.enc(imdbId)}")
    }

    /** Scores already loaded for a TMDB title, or null (no network). Lets poster cards draw them at once. */
    fun cachedTmdb(type: String, tmdbId: Int): Ratings? = cache[tmdbKey(type, tmdbId)]

    private fun tmdbKey(type: String, tmdbId: Int) = "tm:$type:$tmdbId"

    /**
     * Scores for a TMDB title (type "movie" or "tv"), via MDBList's TMDB lookup.
     * Used by poster cards: never throws (errors give empty Ratings), at most 4 requests at once.
     */
    suspend fun forTmdb(type: String, tmdbId: Int): Ratings {
        val key = tmdbKey(type, tmdbId)
        cache[key]?.let { return it }
        if (!configured || tmdbId <= 0 || (type != "movie" && type != "tv")) return Ratings()
        if (System.currentTimeMillis() < pausedUntil) return Ratings()
        return gate.withPermit {
            cache[key]?.let { return@withPermit it }
            try {
                fetch(key, "tm=$tmdbId&m=${if (type == "tv") "show" else "movie"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code == 401 || e.code == 403 || e.code == 429) pausedUntil = System.currentTimeMillis() + 10 * 60_000L
                Ratings()
            } catch (e: Exception) {
                Ratings()
            }
        }
    }

    /** One MDBList lookup ([query] is "i=tt…" or "tm=…&m=…"); the result is cached under [cacheKey]. */
    private suspend fun fetch(cacheKey: String, query: String): Ratings {
        val o = try {
            JSONObject(Http.get("https://mdblist.com/api/?apikey=${Http.enc(Prefs.mdblistKey)}&$query"))
        } catch (e: HttpException) {
            // Unknown title: remember the miss. Key, rate-limit and server errors may clear up, so retry those later.
            if (e.code in 400..499 && e.code != 401 && e.code != 403 && e.code != 429) {
                return Ratings().also { cache[cacheKey] = it }
            }
            throw e
        } catch (e: JSONException) {
            return Ratings().also { cache[cacheKey] = it }
        }
        val list = o.optJSONArray("ratings")
        fun num(source: String): Double? {
            if (list == null) return null
            for (i in 0 until list.length()) {
                val r = list.optJSONObject(i) ?: continue
                if (r.optString("source") == source && !r.isNull("value")) {
                    val v = r.optDouble("value", Double.NaN)
                    if (!v.isNaN() && v > 0) return v
                }
            }
            return null
        }
        val result = Ratings(
            imdb = num("imdb"),
            rtCritics = num("tomatoes")?.toInt(),
            rtAudience = num("popcorn")?.toInt() ?: num("tomatoesaudience")?.toInt(),
            metacritic = num("metacritic")?.toInt(),
            letterboxd = num("letterboxd"),
        )
        cache[cacheKey] = result
        return result
    }
}
