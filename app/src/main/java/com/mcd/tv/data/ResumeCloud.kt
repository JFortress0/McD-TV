package com.mcd.tv.data

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Resume sync: where each profile left off, shared with the house's other TVs and the phones (web app)
 * through the Jarvis relay (rd-proxy/netlify/functions/resume.mts), so playback picks up on any device,
 * even when the device it was watched on is off.
 *
 * Encrypted here with a key derived from the house key; the relay only sees opaque tags and ciphertext.
 * Same format as the web app (docs/app/index.html, RC):
 *  - tag(label) = base64url(HMAC-SHA256(houseKey, "jarvis-resume|" + label)), first 22 characters.
 *    house = tag("house"), profile = tag("profile|p1"), title = tag("title|movie:603").
 *  - value = base64url(iv(12) || AES-256-GCM(HMAC-SHA256(houseKey, "jarvis-resume|enc"), JSON)).
 *  - JSON {type, id, name, poster, backdrop, season, episode, pos, dur, at} (milliseconds).
 * A player app on a phone (Infuse) can report where it stopped: "pp" (seconds) at "pt" (time).
 */
object ResumeCloud {
    private const val ENDPOINT = "https://jarvis-rd.netlify.app/resume"
    /** While playing, one save per title every 2 minutes; stopping or pausing the player sends at once. */
    private const val PUSH_EVERY_MS = 2 * 60_000L
    private const val PULL_EVERY_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()
    private val lock = Any()
    private val lastPush = HashMap<String, Long>()
    private val pending = HashMap<String, Job>()
    private val lastPull = HashMap<String, Long>()

    /** Goes up when positions from another device were merged in (screens re-read Continue Watching). */
    var version by mutableIntStateOf(0)
        private set

    // ---------------------------------------------------------------- crypto

    private fun houseKey(): ByteArray? = runCatching {
        Base64.decode(Prefs.houseKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()?.takeIf { it.size >= 16 }

    private fun hmac(key: ByteArray, s: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(s.toByteArray(Charsets.UTF_8))

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun tag(key: ByteArray, label: String): String = b64(hmac(key, "jarvis-resume|$label")).take(22)

    private fun seal(key: ByteArray, json: String): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hmac(key, "jarvis-resume|enc"), "AES"), GCMParameterSpec(128, iv))
        return b64(iv + c.doFinal(json.toByteArray(Charsets.UTF_8)))
    }

    private fun open(key: ByteArray, v: String): String? = runCatching {
        val all = Base64.decode(v, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(hmac(key, "jarvis-resume|enc"), "AES"), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- saving

    /**
     * A position was saved on this TV (Library.record). Goes to the cloud at most every 2 minutes per
     * title while playing, or right away when [now] (player stopped, paused by leaving, video ended).
     */
    fun onLocal(profile: String, entry: HistoryEntry, now: Boolean = false) {
        if (Prefs.qaMode) return
        val k = "$profile|${entry.meta.historyKey}"
        synchronized(lock) {
            pending.remove(k)?.cancel()
            val since = System.currentTimeMillis() - (lastPush[k] ?: 0L)
            val wait = if (now || since >= PUSH_EVERY_MS) 0L else PUSH_EVERY_MS - since
            pending[k] = scope.launch {
                if (wait > 0) delay(wait)
                synchronized(lock) { lastPush[k] = System.currentTimeMillis(); pending.remove(k) }
                runCatching { push(profile, entry) }
            }
        }
    }

    private fun push(profile: String, e: HistoryEntry) {
        val key = houseKey() ?: return
        val m = e.meta
        val body = JSONObject()
            .put("type", m.type).put("id", m.tmdbId).put("name", m.name)
            .put("poster", m.poster ?: "").put("backdrop", m.backdrop ?: "")
            .put("season", m.season).put("episode", m.episode)
            .put("pos", e.positionMs).put("dur", e.durationMs).put("at", e.updatedAt)
        val payload = JSONObject()
            .put("k", tag(key, "title|${m.historyKey}"))
            .put("t", e.updatedAt)
            .put("v", seal(key, body.toString()))
            .toString()
        val c = URL("$ENDPOINT?h=${enc(tag(key, "house"))}&p=${enc(tag(key, "profile|$profile"))}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            c.responseCode // 200 saved or already newer; anything else: the next save tries again
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- Live TV favorites

    private val LIVE_NUM = Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?(?:\?.*)?$""")
    private val favJobs = HashMap<String, Job>()

    /**
     * [profile]'s Live TV favorites changed (here, from a phone, or from another TV): a few seconds later they go
     * to the relay as stream numbers, so the phones have them even when every TV is off. Profile tag
     * "livefav|pN", key "list", value {ids, at}; the web app reads it (docs/app/index.html, RC.favGet).
     */
    fun onLiveFavorites(profile: String) {
        if (Prefs.qaMode) return
        synchronized(lock) {
            favJobs.remove(profile)?.cancel()
            favJobs[profile] = scope.launch {
                delay(5_000)
                synchronized(lock) { favJobs.remove(profile) }
                runCatching { pushLiveFavs(profile) }
            }
        }
    }

    private fun liveFavIds(profile: String): List<String> = runCatching {
        val a = JSONArray(Prefs.json("live_favorites@$profile").ifBlank { "[]" })
        List(a.length()) { a.optString(it) }.mapNotNull { LIVE_NUM.find(it)?.groupValues?.get(1) }.distinct().take(300)
    }.getOrDefault(emptyList())

    private fun pushLiveFavs(profile: String) {
        val key = houseKey() ?: return
        val ids = liveFavIds(profile)
        val sig = "v1:" + ids.joinToString(",")
        if (Prefs.json("livefav_sent@$profile") == sig) return // already there
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("k", tag(key, "list"))
            .put("t", now)
            .put("v", seal(key, JSONObject().put("ids", JSONArray(ids)).put("at", now).toString()))
            .toString()
        val c = URL("$ENDPOINT?h=${enc(tag(key, "house"))}&p=${enc(tag(key, "livefav|$profile"))}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode == 200) Prefs.putJson("livefav_sent@$profile", sig)
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- reading

    /** One position from the cloud, decrypted. [at] = when it was saved (or reported by a player app). */
    data class Remote(val meta: PlayMeta, val positionMs: Long, val durationMs: Long, val at: Long)

    private fun fetch(profile: String): List<Remote> {
        val key = houseKey() ?: return emptyList()
        val c = URL("$ENDPOINT?h=${enc(tag(key, "house"))}&p=${enc(tag(key, "profile|$profile"))}").openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (c.responseCode != 200) return emptyList()
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val e = JSONObject(text).optJSONObject("e") ?: return emptyList()
        val out = ArrayList<Remote>()
        for (k in e.keys()) {
            val r = e.optJSONObject(k) ?: continue
            val o = open(key, r.optString("v"))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val meta = PlayMeta(
                o.optString("type"), o.optInt("id"), o.optString("name"),
                o.optString("poster").ifBlank { null }, o.optString("backdrop").ifBlank { null },
                o.optInt("season"), o.optInt("episode"),
            )
            if (meta.tmdbId <= 0 || meta.type !in setOf("movie", "tv")) continue
            var pos = o.optLong("pos")
            var at = o.optLong("at")
            val dur = o.optLong("dur")
            // A phone's player app reported where it stopped (seconds), after this save.
            if (r.has("pp") && r.optLong("pt") > at) {
                pos = r.optLong("pp") * 1000
                at = r.optLong("pt")
            }
            out.add(Remote(meta, pos.coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE), dur, at))
        }
        return out
    }

    /**
     * Merges positions saved on other devices into this TV's history (newer than ours only), then bumps
     * [version]. At most every 20 seconds per profile unless [force].
     */
    suspend fun pull(profile: String = Prefs.activeProfile, force: Boolean = false) {
        if (Prefs.qaMode) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!force && now - (lastPull[profile] ?: 0L) < PULL_EVERY_MS) return
            lastPull[profile] = now
        }
        // Favorites that changed while the app was closed, or arrived from another TV: send them (only when they differ).
        for (p in Prefs.PROFILE_IDS) onLiveFavorites(p)
        val remote = withContext(Dispatchers.IO) { runCatching { fetch(profile) }.getOrDefault(emptyList()) }
        if (remote.isEmpty()) return
        val local = Library.history(profile).associateBy { it.meta.historyKey }
        var changed = false
        for (r in remote) {
            val l = local[r.meta.historyKey]
            if (l != null && l.updatedAt >= r.at - 1_000) continue
            Library.applyCloud(profile, r.meta, r.positionMs, r.durationMs, r.at)
            changed = true
        }
        if (changed) withContext(Dispatchers.Main) { version++ }
    }
}
