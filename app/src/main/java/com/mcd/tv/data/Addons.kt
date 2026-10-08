package com.mcd.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One playable option from an addon, shown in the source picker. */
data class StreamSource(
    val addon: String,
    val name: String,
    val title: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val quality: String,
    val sizeText: String,
    val sizeGb: Double,
    val seeders: Int?,
    val cached: Boolean,
)

data class AddonInfo(val manifestUrl: String, val name: String, val description: String, val streams: Boolean)

/**
 * Client for the open Stremio addon protocol (manifest.json + /stream/{type}/{id}.json).
 * McD TV ships with no addons: you add manifest URLs yourself in Settings.
 */
object Addons {
    /** Accepts stremio:// links and bare addon URLs. Returns the manifest.json URL. */
    fun normalize(input: String): String {
        var u = input.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        if (!u.startsWith("http")) u = "https://$u"
        if (!u.endsWith("manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    private fun base(manifestUrl: String) = manifestUrl.removeSuffix("/manifest.json")

    suspend fun manifest(url: String): AddonInfo {
        val o = JSONObject(Http.get(url))
        val res = o.optJSONArray("resources")
        var streams = false
        if (res != null) for (i in 0 until res.length()) {
            val r = res.get(i)
            val name = if (r is JSONObject) r.optString("name") else r.toString()
            if (name == "stream") streams = true
        }
        return AddonInfo(url, o.s("name") ?: "Addon", o.s("description") ?: "", streams)
    }

    /** Adds an addon after checking its manifest loads. */
    suspend fun install(input: String): AddonInfo {
        val url = normalize(input)
        val info = manifest(url)
        Prefs.addonUrls = Prefs.addonUrls + url
        return info
    }

    fun remove(url: String) {
        Prefs.addonUrls = Prefs.addonUrls - url
    }

    private val qualityRx = Regex("(2160p|4k|1080p|720p|480p)", RegexOption.IGNORE_CASE)
    private val sizeRx = Regex("([0-9]+(?:\\.[0-9]+)?)\\s?(GB|MB)", RegexOption.IGNORE_CASE)
    private val seedRx = Regex("👤\\s?([0-9]+)")

    private fun qualityRank(q: String) = when (q) {
        "4K" -> 4; "1080p" -> 3; "720p" -> 2; "480p" -> 1; else -> 0
    }

    /**
     * Asks every installed addon for streams. id is an IMDb id for movies,
     * or "tt123:season:episode" for TV. Slow addons are skipped after 20 s.
     */
    suspend fun streams(type: String, id: String): List<StreamSource> = coroutineScope {
        val stremioType = if (type == "tv") "series" else "movie"
        Prefs.addonUrls.map { m ->
            async {
                withTimeoutOrNull(20_000) {
                    runCatching {
                        val addonName = runCatching { manifest(m).name }.getOrDefault("Addon")
                        val o = JSONObject(Http.get("${base(m)}/stream/$stremioType/$id.json"))
                        val arr = o.optJSONArray("streams")
                        if (arr == null) emptyList() else (0 until arr.length()).map { parse(addonName, arr.getJSONObject(it)) }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten().let { sort(it) }
    }

    private fun parse(addon: String, s: JSONObject): StreamSource {
        val name = s.s("name") ?: addon
        val title = s.s("title") ?: s.s("description") ?: ""
        val text = "$name $title"
        val q = qualityRx.find(text)?.value?.lowercase()?.let { if (it == "2160p" || it == "4k") "4K" else it } ?: "Unknown"
        val sizeMatch = sizeRx.find(title)
        val sizeGb = sizeMatch?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: 0.0
            if (it.groupValues[2].equals("MB", true)) n / 1024 else n
        } ?: 0.0
        val url = s.s("url")
        // Addons mark debrid-cached results with "+" or a lightning bolt in the name.
        val cached = name.contains("+]") || name.contains("RD+") || name.contains("⚡") ||
            (url != null && name.contains("RD", ignoreCase = false) && !name.contains("download", true))
        return StreamSource(
            addon = addon,
            name = name.replace("\n", " "),
            title = title,
            url = url,
            infoHash = s.s("infoHash"),
            fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null,
            quality = q,
            sizeText = sizeMatch?.value ?: "",
            sizeGb = sizeGb,
            seeders = seedRx.find(title)?.groupValues?.get(1)?.toIntOrNull(),
            cached = cached,
        )
    }

    /** Cached first. Normal mode: best quality. Slow connection: 720p/1080p, smaller files first. */
    fun sort(list: List<StreamSource>): List<StreamSource> {
        val slow = Prefs.slowConnection
        return list.sortedWith(
            compareByDescending<StreamSource> { it.cached }
                .thenByDescending {
                    if (slow) (if (it.quality == "720p") 3 else if (it.quality == "1080p") 2 else 0) else qualityRank(it.quality)
                }
                .thenBy { if (slow) it.sizeGb else -it.sizeGb },
        )
    }
}
