package com.mcd.tv.data

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Shared settings between your TVs, with no account.
 *
 * Every TV has a "house": a random id and a 256-bit key. TVs with the same house keep the same
 * settings (keys, addons, playlist, Real-Debrid, preferences). The Control page links a new TV to
 * another TV's house ("house_join"); after that, a change on any of them reaches all of them.
 *
 * How it works:
 *  - Each TV listens on the ntfy topic "mcdtv-<house id>-house". Every message is encrypted with the
 *    house key (same format as [Relay]), so the relay only ever sees random bytes.
 *  - Every few seconds a TV checks whether its shared settings changed (from the Control page, the TV's
 *    own Settings screen, a Real-Debrid token refresh...). If so, it sends them to the house ("sync"),
 *    stamped with the time of the change.
 *  - A TV takes a copy only when its stamp is newer than the one it has. A TV that just joined has stamp 0,
 *    so it takes the first copy it gets. It also asks the others to send theirs ("sync_req").
 *  - ntfy keeps messages for about 12 hours, so a TV that was off picks up changes when it opens.
 */
object HouseSync {
    private const val BASE = "https://ntfy.sh/"
    /** One encrypted message must stay under ntfy's 4 KB, so the settings are sent in parts of this size. */
    private const val CHUNK = 1800

    /** The settings all linked TVs share. Profile data (watch history, lists, Live TV favorites and recents) stays per TV. */
    private val SHARED = listOf(
        "addon_urls", "websites",
        "m3u_url", "custom_stream_url",
        "tmdb_key", "mdblist_key", "jarvis_key", "jarvis_model",
        "origin_filter", "us_only", "slow_connection", "play_intro", "max_movie_gb", "max_episode_gb",
        "rd_client_id", "rd_client_secret", "rd_access", "rd_refresh", "rd_expires",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()
    private val lock = Any()
    @Volatile private var started = false
    @Volatile private var generation = 0
    @Volatile private var lastText = ""
    @Volatile private var lastReplyAt = 0L
    /** Parts of "sync" messages being put back together, by message id. */
    private val pending = LinkedHashMap<String, Array<String?>>()

    private val topic get() = "mcdtv-${Prefs.houseId}-house"

    fun houseLink(): String = "${Prefs.houseId}.${Prefs.houseKey}"

    /**
     * True right after joining, until the first copy from another TV arrives (stamp 0). Kept in settings,
     * so a restart in between never sends this TV's empty settings over the other TV's.
     */
    fun joining(): Boolean = Prefs.houseStamp <= 0L

    /** A short name for the Control page. */
    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return when {
            maker.equals("Amazon", ignoreCase = true) -> "Fire TV"
            maker.equals("Google", ignoreCase = true) || model.contains("Chromecast", ignoreCase = true) -> "Google TV"
            model.isNotBlank() -> model
            else -> "TV"
        }
    }

    private fun randomId(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..22).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun randomKey(): String {
        val key = ByteArray(32).also { rng.nextBytes(it) }
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun newHouse() {
        Prefs.houseId = randomId()
        Prefs.houseKey = randomKey()
        Prefs.houseSince = ""
        Prefs.houseStamp = System.currentTimeMillis()
    }

    private fun snapshot(): String = Prefs.exportKeys(SHARED).toString()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (Prefs.deviceId.isBlank()) Prefs.deviceId = randomId()
        if (Prefs.houseId.isBlank() || Prefs.houseKey.isBlank()) newHouse()
        lastText = snapshot()
        val gen = generation
        scope.launch { listen(gen) }
        scope.launch { watch() }
        scope.launch { runCatching { publishRequest() } }
    }

    /** Joins another TV's house and takes its settings. */
    @Synchronized
    fun join(id: String, key: String) {
        if (id == Prefs.houseId && key == Prefs.houseKey) {
            scope.launch { runCatching { publishRequest() } }
            return
        }
        synchronized(lock) {
            Prefs.houseId = id
            Prefs.houseKey = key
            Prefs.houseSince = ""
            Prefs.houseStamp = 0L // take the first copy from the other TVs
            lastText = snapshot()
        }
        val gen = ++generation
        scope.launch { listen(gen) }
        scope.launch {
            delay(1500)
            runCatching { publishRequest() }
        }
    }

    /** Stops sharing: this TV keeps its current settings in a house of its own. */
    @Synchronized
    fun leave() {
        synchronized(lock) {
            newHouse()
            lastText = snapshot()
        }
        val gen = ++generation
        scope.launch { listen(gen) }
    }

    /** Sends this TV's settings to the house whenever they change. */
    private suspend fun watch() {
        while (true) {
            delay(4000)
            val stamp = synchronized(lock) {
                if (joining()) {
                    0L // wait for the other TV's copy before sending ours
                } else {
                    val now = snapshot()
                    if (now == lastText) {
                        0L
                    } else {
                        lastText = now
                        System.currentTimeMillis().also { Prefs.houseStamp = it }
                    }
                }
            }
            if (stamp > 0L) runCatching { publishSync(stamp) }
        }
    }

    private suspend fun post(o: JSONObject) {
        Http.postText(BASE + topic, Relay.encryptWith(Prefs.houseKey, o.toString()))
    }

    private suspend fun publishRequest() {
        post(JSONObject().put("type", "sync_req").put("from", Prefs.deviceId).put("ts", System.currentTimeMillis()))
    }

    private suspend fun publishSync(stamp: Long) {
        val text = snapshot()
        val parts = text.chunked(CHUNK).ifEmpty { listOf("{}") }
        val sid = randomId()
        parts.forEachIndexed { i, chunk ->
            post(
                JSONObject()
                    .put("type", "sync")
                    .put("from", Prefs.deviceId)
                    .put("sid", sid)
                    .put("part", i)
                    .put("parts", parts.size)
                    .put("ts", stamp)
                    .put("chunk", chunk),
            )
        }
    }

    private suspend fun listen(gen: Int) {
        var backoff = 2000L
        while (gen == generation) {
            var c: HttpURLConnection? = null
            try {
                val since = Prefs.houseSince.ifBlank { "12h" }
                c = URL("$BASE$topic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000
                c.inputStream.bufferedReader().use { reader ->
                    backoff = 2000L
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (ev.optString("event") != "message") continue
                        if (gen != generation) break
                        Prefs.houseSince = ev.optString("id")
                        val msg = Relay.decryptWith(Prefs.houseKey, ev.optString("message")) ?: continue
                        handle(msg)
                    }
                }
                delay(1000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { c?.disconnect() }
            }
        }
    }

    private suspend fun handle(m: JSONObject) {
        if (m.optString("from") == Prefs.deviceId) return
        when (m.optString("type")) {
            "sync_req" -> {
                // A TV joined or opened: send ours, unless we have nothing yet ourselves.
                if (joining()) return
                val now = System.currentTimeMillis()
                if (now - lastReplyAt < 20_000L) return
                lastReplyAt = now
                runCatching { publishSync(Prefs.houseStamp) }
            }
            "sync" -> {
                val sid = m.optString("sid")
                val parts = m.optInt("parts", 0)
                val part = m.optInt("part", -1)
                if (sid.isBlank() || parts !in 1..40 || part !in 0 until parts) return
                val full: String = synchronized(pending) {
                    val arr = pending.getOrPut(sid) { arrayOfNulls(parts) }
                    if (arr.size != parts) return
                    arr[part] = m.optString("chunk")
                    while (pending.size > 8) pending.remove(pending.keys.first())
                    if (arr.any { it == null }) return
                    pending.remove(sid)
                    arr.joinToString("")
                }
                val data = runCatching { JSONObject(full) }.getOrNull() ?: return
                val ts = m.optLong("ts", 0L)
                val applied = synchronized(lock) {
                    if (ts <= Prefs.houseStamp) return@synchronized false
                    Prefs.importKeys(SHARED, data)
                    Prefs.houseStamp = ts
                    lastText = snapshot()
                    true
                }
                if (applied) {
                    LocalWeb.lastMessage = "Settings updated from your other TV"
                    runCatching { Relay.publishState("Settings copied from your other TV") }
                }
            }
        }
    }
}
