package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** What the player needs to record history and resume. */
data class PlayMeta(
    val type: String,
    val tmdbId: Int,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int = 0,
    val episode: Int = 0,
) {
    /** One history entry per movie, or per show (latest episode wins). */
    val historyKey: String get() = "$type:$tmdbId"
    val label: String get() = if (type == "tv" && season > 0) "$name  S${season}E$episode" else name
}

data class HistoryEntry(val meta: PlayMeta, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = progress > 0.92f
}

/**
 * Favorites, Watchlist, watch history (Continue Watching) and the Background Noise show list.
 * Stored on the device as JSON. Phase 6 adds Trakt sync on top.
 */
object Library {
    private fun Title.toJson() = JSONObject()
        .put("id", id).put("type", type).put("name", name).put("overview", overview)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("rating", rating).put("year", year)

    private fun JSONObject.toTitle() = Title(
        optInt("id"), optString("type"), optString("name"), optString("overview"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null },
        optDouble("rating", 0.0), optString("year"),
    )

    private fun loadTitles(key: String): List<Title> = runCatching {
        val a = JSONArray(Prefs.json(key).ifBlank { "[]" })
        (0 until a.length()).map { a.getJSONObject(it).toTitle() }
    }.getOrDefault(emptyList())

    private fun saveTitles(key: String, list: List<Title>) =
        Prefs.putJson(key, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    private fun toggle(key: String, t: Title): Boolean {
        val list = loadTitles(key)
        val has = list.any { it.id == t.id && it.type == t.type }
        saveTitles(key, if (has) list.filterNot { it.id == t.id && it.type == t.type } else listOf(t) + list)
        return !has
    }

    private fun contains(key: String, t: Title) = loadTitles(key).any { it.id == t.id && it.type == t.type }

    fun favorites() = loadTitles("lib_favorites")
    fun isFavorite(t: Title) = contains("lib_favorites", t)
    fun toggleFavorite(t: Title) = toggle("lib_favorites", t)

    fun watchlist() = loadTitles("lib_watchlist")
    fun inWatchlist(t: Title) = contains("lib_watchlist", t)
    fun toggleWatchlist(t: Title) = toggle("lib_watchlist", t)

    fun noiseShows() = loadTitles("lib_noise")
    fun inNoise(t: Title) = contains("lib_noise", t)
    fun toggleNoise(t: Title) = toggle("lib_noise", t)

    /** "Not for me": hidden from home rows. */
    fun hidden() = loadTitles("lib_hidden")
    fun hide(t: Title) { if (!contains("lib_hidden", t)) toggle("lib_hidden", t) }

    // ---- History ----
    private fun PlayMeta.toJson() = JSONObject().put("type", type).put("tmdbId", tmdbId).put("name", name)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("season", season).put("episode", episode)

    private fun JSONObject.toMeta() = PlayMeta(
        optString("type"), optInt("tmdbId"), optString("name"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null },
        optInt("season"), optInt("episode"),
    )

    fun history(): List<HistoryEntry> = runCatching {
        val a = JSONArray(Prefs.json("lib_history").ifBlank { "[]" })
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryEntry(o.getJSONObject("meta").toMeta(), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun continueWatching() = history().filter { !it.finished && it.positionMs > 60_000 }

    fun resumePosition(meta: PlayMeta): Long =
        history().firstOrNull { it.meta.historyKey == meta.historyKey && it.meta.season == meta.season && it.meta.episode == meta.episode }
            ?.takeIf { !it.finished }?.positionMs ?: 0L

    fun record(meta: PlayMeta, positionMs: Long, durationMs: Long) {
        if (meta.type == "tv" && meta.season > 0 && durationMs > 0) recordEpisode(meta, positionMs.toFloat() / durationMs)
        val rest = history().filterNot { it.meta.historyKey == meta.historyKey }
        val list = listOf(HistoryEntry(meta, positionMs, durationMs, System.currentTimeMillis())) + rest
        Prefs.putJson(
            "lib_history",
            JSONArray().apply {
                list.take(200).forEach {
                    put(JSONObject().put("meta", it.meta.toJson()).put("pos", it.positionMs).put("dur", it.durationMs).put("at", it.updatedAt))
                }
            }.toString(),
        )
    }

    fun markWatched(meta: PlayMeta) = record(meta, 1, 1)

    // ---- Per-episode progress (history keeps only the latest episode per show) ----
    private const val EPISODES_KEY = "lib_episodes"
    private const val EPISODES_MAX = 3000

    private fun loadEpisodes(): JSONObject = runCatching { JSONObject(Prefs.json(EPISODES_KEY).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun recordEpisode(meta: PlayMeta, progress: Float) {
        val o = loadEpisodes()
        val k = "${meta.tmdbId}:${meta.season}:${meta.episode}"
        o.remove(k) // re-insert so the newest entries stay at the end
        o.put(k, progress.coerceIn(0f, 1f).toDouble())
        if (o.length() > EPISODES_MAX) {
            val drop = mutableListOf<String>()
            val keys = o.keys()
            while (keys.hasNext() && o.length() - drop.size > EPISODES_MAX) drop.add(keys.next())
            drop.forEach { k2 -> o.remove(k2) }
        }
        Prefs.putJson(EPISODES_KEY, o.toString())
    }

    /** Watched fraction (0..1) of one episode, or null if it was never played. */
    fun progressFor(type: String, id: Int, season: Int, episode: Int): Float? {
        if (type != "tv") {
            return history().firstOrNull { it.meta.historyKey == "$type:$id" }?.progress
        }
        val o = loadEpisodes()
        val k = "$id:$season:$episode"
        if (o.has(k)) return o.optDouble(k, 0.0).toFloat()
        // Older installs: only the latest episode per show is in history.
        return history().firstOrNull { it.meta.historyKey == "tv:$id" && it.meta.season == season && it.meta.episode == episode }?.progress
    }

    /** Watched fraction for every played episode of one season, keyed by episode number. */
    fun seasonProgress(tvId: Int, season: Int): Map<Int, Float> {
        val out = mutableMapOf<Int, Float>()
        history().firstOrNull { it.meta.historyKey == "tv:$tvId" && it.meta.season == season }
            ?.let { out[it.meta.episode] = it.progress }
        val o = loadEpisodes()
        val prefix = "$tvId:$season:"
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(prefix)) k.removePrefix(prefix).toIntOrNull()?.let { e -> out[e] = o.optDouble(k, 0.0).toFloat() }
        }
        return out
    }
}
