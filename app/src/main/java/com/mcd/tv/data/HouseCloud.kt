package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The house's copy on the Jarvis relay (rd-proxy/netlify/functions/house.mts), so phones never depend on one TV:
 *
 *  - Setup: every TV keeps one encrypted snapshot current (settings, keys, playlist, addons and each profile's
 *    lists and Live TV favorites). A phone with the house link reads it any time, with every TV off.
 *  - Live TV favorites: every TV and phone merges its list with the shared "livefav" list (syncLiveFavorites),
 *    so all of them end up with the same channels.
 *  - "Connect a phone": a short-lived 8-digit code that hands the house link to a phone (no TV link needed).
 *
 * Encrypted with keys from the house key (same tags and format as ResumeCloud); the relay sees only tags.
 */
object HouseCloud {
    private const val ENDPOINT = "https://jarvis-rd.netlify.app/house"
    private const val SNAP_EVERY_MS = 2 * 60_000L
    private const val SNAP_REFRESH_MS = 6 * 3600_000L
    @Volatile private var lastSnapCheck = 0L
    private val rng = SecureRandom()

    private fun post(url: String, body: JSONObject): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- setup snapshot

    private fun ref(type: String, id: Int) = JSONObject().put("type", type).put("id", id)

    private fun listsOf(pid: String): JSONObject {
        val live = runCatching { JSONArray(Prefs.json("live_favorites@$pid").ifBlank { "[]" }) }.getOrDefault(JSONArray())
        return JSONObject()
            .put("watchlist", JSONArray(Library.watchlist(pid).take(100).map { ref(it.type, it.id) }))
            .put("favorites", JSONArray(Library.favorites(pid).take(100).map { ref(it.type, it.id) }))
            .put("continue", JSONArray(Library.continueWatching(pid).take(60).map {
                // A finished episode: the phone shows the next one ("next": true, nothing watched yet).
                val upNext = it.meta.type == "tv" && it.finished
                val (s, e) = if (upNext) ContinueRules.playEpisode(it.meta.season, it.meta.episode, it.progress) else it.meta.season to it.meta.episode
                ref(it.meta.type, it.meta.tmdbId).put("season", s).put("episode", e)
                    .put("progress", if (upNext) 0.0 else Math.round(it.progress * 100) / 100.0)
                    .apply { if (upNext) put("next", true) }
            }))
            .put("live_favorites", live)
    }

    /** What a phone gets from a TV (Relay's web_init), for every profile at once. */
    private fun snapshot(): JSONObject {
        val profiles = JSONArray().apply {
            Prefs.PROFILE_IDS.forEach { id -> put(JSONObject().put("id", id).put("name", Prefs.profileName(id))) }
        }
        val lists = JSONObject().apply { Prefs.PROFILE_IDS.forEach { put(it, listsOf(it)) } }
        return JSONObject()
            .put("type", "house_setup")
            .put("profiles", profiles)
            .put("tmdb_key", Prefs.tmdbKey)
            .put("mdblist_key", Prefs.mdblistKey)
            .put("jarvis_key", Prefs.jarvisKey)
            .put("m3u_url", Prefs.m3uUrl)
            .put("origin_filter", Prefs.origin.key)
            .put("us_only", Prefs.usOnly)
            .put("slow_connection", Prefs.slowConnection)
            .put("max_movie_gb", Prefs.maxMovieGb)
            .put("max_episode_gb", Prefs.maxEpisodeGb)
            .put("rd_connected", RealDebrid.connected)
            .put("house", HouseSync.houseLink())
            .put("addon_urls", JSONArray(Prefs.addonUrls))
            .put("websites", JSONArray(Prefs.websites.map { JSONArray().put(it.first).put(it.second) }))
            .put("lists", lists)
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Sends the snapshot when it changed (or every 6 hours). Checked at most every 2 minutes. */
    suspend fun pushSnapshot(force: Boolean = false) {
        withContext(Dispatchers.IO) { runCatching { pushSnapshotNow(force) } }
    }

    private fun pushSnapshotNow(force: Boolean) {
        if (Prefs.qaMode) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSnapCheck < SNAP_EVERY_MS) return
        lastSnapCheck = now
        val key = ResumeCloud.houseKey() ?: return
        val body = snapshot().toString()
        val sig = sha(body)
        val last = Prefs.json("house_snap_sig").split("@")
        if (!force && last.size == 2 && last[0] == sig && now - (last[1].toLongOrNull() ?: 0L) < SNAP_REFRESH_MS) return
        val snap = JSONObject(body).put("ts", now).toString()
        val url = "$ENDPOINT?h=${ResumeCloud.enc(ResumeCloud.tag(key, "house"))}&k=${ResumeCloud.enc(ResumeCloud.tag(key, "setup"))}"
        if (post(url, JSONObject().put("t", now).put("v", ResumeCloud.seal(key, snap))) == 200) {
            Prefs.putJson("house_snap_sig", "$sig@$now")
        }
    }

    // ---------------------------------------------------------------- Live TV favorites from phones

    private val LIVE_NUM = Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?(?:\?.*)?$""")
    private fun num(u: String): String? = LIVE_NUM.find(u)?.groupValues?.get(1)

    private val favLock = Mutex()

    private fun localFavUrls(profile: String): List<String> = runCatching {
        val a = JSONArray(Prefs.json("live_favorites@$profile").ifBlank { "[]" })
        List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * Merges [profile]'s Live TV favorites with the shared copy on the relay (LiveFavMerge): channels added on any
     * TV or phone are kept, and a channel removed anywhere is removed everywhere. Applies the result here and
     * sends it back when the shared copy differs. True when this TV's favorites changed.
     *
     * "livefav_base@pN": the list this TV and the shared copy last agreed on. "livefav_extra@pN": shared channels
     * this TV's playlist does not have; they stay in the shared list and are never read as removed here.
     */
    suspend fun syncLiveFavorites(profile: String): Boolean = withContext(Dispatchers.IO) {
        if (Prefs.qaMode || profile !in Prefs.PROFILE_IDS) return@withContext false
        favLock.withLock { runCatching { syncLiveFavoritesNow(profile) }.getOrDefault(false) }
    }

    private suspend fun syncLiveFavoritesNow(profile: String): Boolean {
        val key = ResumeCloud.houseKey() ?: return false
        val remote = ResumeCloud.fetchLiveFavs(key, profile) ?: return false
        val baseKey = "livefav_base@$profile"
        val extraKey = "livefav_extra@$profile"
        val base = LiveFavMerge.parse(Prefs.json(baseKey))
        val extra = LiveFavMerge.parse(Prefs.json(extraKey))
        val localUrls = localFavUrls(profile)
        val local = localUrls.mapNotNull { num(it) }.toSet()
        val merged = LiveFavMerge.merge(base, local + (extra intersect base), remote)
        var changed = false
        // Channels with no stream number in their URL are left alone: they can't be shared.
        localUrls.filter { u -> num(u)?.let { it !in merged } == true }.forEach {
            Prefs.setLiveFavorite(profile, it, false, share = false); changed = true
        }
        val missing = merged - local
        if (missing.isNotEmpty()) {
            val byNum = runCatching { M3u.load() }.getOrDefault(emptyList()).associateBy { num(it.url) }
            missing.forEach { id -> byNum[id]?.url?.let { Prefs.setLiveFavorite(profile, it, true, share = false); changed = true } }
        }
        val have = localFavUrls(profile).mapNotNull { num(it) }.toSet()
        Prefs.putJson(extraKey, LiveFavMerge.format(merged - have))
        val saved = merged == remote || ResumeCloud.pushLiveFavIds(key, profile, merged)
        if (saved) Prefs.putJson(baseKey, LiveFavMerge.format(merged))
        return changed
    }

    // ---------------------------------------------------------------- connect a phone with a code

    private fun codeTag(code: String, what: String): String =
        android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest("jarvis-pair|$what|$code".toByteArray(Charsets.UTF_8)),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        ).take(22)

    private fun codeKey(code: String): String =
        android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest("jarvis-pair-key|$code".toByteArray(Charsets.UTF_8)),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

    fun newCode(): String = (0 until 8).map { rng.nextInt(10) }.joinToString("")

    /**
     * Puts the house link (and the snapshot, so the phone is set up at once) on the relay under [code] for
     * 15 minutes. The phone types the code in Jarvis > Settings > Connect with a code. True when it was saved.
     */
    suspend fun offerCode(code: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            pushSnapshot(force = true)
            val msg = JSONObject().put("type", "house_link").put("house", HouseSync.houseLink()).put("ts", System.currentTimeMillis())
            val url = "$ENDPOINT?h=${codeTag(code, "h")}&k=${codeTag(code, "k")}"
            post(url, JSONObject().put("t", System.currentTimeMillis()).put("v", Relay.encryptWith(codeKey(code), msg.toString())).put("ttl", 15 * 60_000)) == 200
        }.getOrDefault(false)
    }
}
