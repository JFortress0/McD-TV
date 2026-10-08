package com.mcd.tv.data

import org.json.JSONObject

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
 * MDBLIST_API_KEY, or the McD TV Control page.
 */
object RatingsSource {
    private val cache = HashMap<String, Ratings>()

    val configured: Boolean get() = Prefs.mdblistKey.isNotBlank()

    suspend fun forImdb(imdbId: String): Ratings {
        cache[imdbId]?.let { return it }
        if (!configured) return Ratings()
        val o = JSONObject(Http.get("https://mdblist.com/api/?apikey=${Http.enc(Prefs.mdblistKey)}&i=${Http.enc(imdbId)}"))
        val list = o.optJSONArray("ratings")
        fun num(source: String): Double? {
            if (list == null) return null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
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
        cache[imdbId] = result
        return result
    }
}
