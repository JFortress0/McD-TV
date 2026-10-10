package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
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
 *  - Live TV favorites a phone changes (ResumeCloud's "livefav" list) are pulled here and applied to this TV.
 *    The TVs then agree with each other through ProfileSync as before.
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
                ref(it.meta.type, it.meta.tmdbId).put("season", it.meta.season).put("episode", it.meta.episode)
                    .put("progress", Math.round(it.progress * 100) / 100.0)
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

    /**
     * Applies [profile]'s favorites list from the relay when a phone (or another TV) saved it after this TV last
     * looked and it differs from this TV's. True when this TV's favorites changed.
     */
    suspend fun pullLiveFavorites(profile: String): Boolean = withContext(Dispatchers.IO) {
        if (Prefs.qaMode) return@withContext false
        runCatching {
            val key = ResumeCloud.houseKey() ?: return@runCatching false
            val c = URL(
                "https://jarvis-rd.netlify.app/resume?h=${ResumeCloud.enc(ResumeCloud.tag(key, "house"))}" +
                    "&p=${ResumeCloud.enc(ResumeCloud.tag(key, "livefav|$profile"))}",
            ).openConnection() as HttpURLConnection
            val text = try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                if (c.responseCode != 200) return@runCatching false
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
            val rec = JSONObject(text).optJSONObject("e")?.optJSONObject(ResumeCloud.tag(key, "list")) ?: return@runCatching false
            val at = rec.optLong("t")
            val seenKey = "livefav_seen@$profile"
            if (at <= (Prefs.json(seenKey).toLongOrNull() ?: 0L)) return@runCatching false
            val o = JSONObject(ResumeCloud.open(key, rec.optString("v")) ?: return@runCatching false)
            val a = o.optJSONArray("ids") ?: return@runCatching false
            val ids = List(a.length()) { a.optString(it) }.filter { it.all(Char::isDigit) && it.isNotEmpty() }
            val local = runCatching { JSONArray(Prefs.json("live_favorites@$profile").ifBlank { "[]" }) }.getOrDefault(JSONArray())
            val localUrls = List(local.length()) { local.optString(it) }
            val localIds = localUrls.mapNotNull { num(it) }.toSet()
            var changed = false
            if (ids.toSet() != localIds) {
                // Removed elsewhere: drop here. Added elsewhere: add this TV's copy of the channel.
                localUrls.filter { u -> num(u)?.let { it !in ids } == true }.forEach { Prefs.setLiveFavorite(profile, it, false); changed = true }
                val missing = ids.filter { it !in localIds }
                if (missing.isNotEmpty()) {
                    val byNum = runCatching { M3u.load() }.getOrDefault(emptyList()).associateBy { num(it.url) }
                    missing.forEach { id -> byNum[id]?.url?.let { Prefs.setLiveFavorite(profile, it, true); changed = true } }
                }
            }
            Prefs.putJson(seenKey, at.toString())
            changed
        }.getOrDefault(false)
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
