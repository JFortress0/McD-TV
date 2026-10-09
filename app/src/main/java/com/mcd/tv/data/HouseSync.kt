package com.mcd.tv.data

import android.os.Build
import android.util.Base64
import com.mcd.tv.data.sync.Backoff
import com.mcd.tv.data.sync.ProfileSync
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
 *
 * Profile data (history, progress, My List, favorites, Live TV favorites, profile names) travels on the same
 * topic as "pdelta", "pdigest" and "pstate" messages, handled by [ProfileSync]. It is paused while a TV is
 * joining (until the first settings copy arrives) and during "Copy once", so a friend's TV that copies
 * settings never gets or sends anyone's profiles.
 */
object HouseSync {
    private const val BASE = "https://ntfy.sh/"
    /** One encrypted message must stay under ntfy's 4 KB, so the settings are sent in parts of this size. */
    private const val CHUNK = 1800

    /**
     * The settings all linked TVs share. Profile data is not here: ProfileSync merges it entry by entry
     * (a whole-copy "newest wins" like this would lose changes made on two TVs at once).
     */
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

    /** Profile sync runs only in a house this TV fully belongs to: not while joining, not during "Copy once". */
    fun profileSyncAllowed(): Boolean = !joining() && !copyOnce()

    /** For ProfileSync: [json] encrypted with the house key (the length is what counts against ntfy's limit). */
    internal fun sealForHouse(json: String): String = Relay.encryptWith(Prefs.houseKey, json)

    /**
     * Backoff for every post to the house topic (settings and profile sync). ntfy.sh limits messages per day
     * per home IP, shared by all TVs: after a 429 this TV stops posting for 30 minutes (doubling up to 6 hours)
     * instead of retrying in a loop; after a 5xx or a network error it waits 30 s, doubling up to 30 minutes.
     * Saved, so a restart does not start hammering again. Nothing is dropped: it is sent after the pause.
     */
    private val backoff = Backoff()
    @Volatile private var backoffLoaded = false
    @Volatile private var lastRequestAt = 0L

    private fun loadBackoffLocked() {
        if (backoffLoaded) return
        backoffLoaded = true
        val p = Prefs.json("sync_pause").split(",").mapNotNull { it.trim().toLongOrNull() }
        if (p.size == 3) backoff.restore(p[0], p[1], p[2])
    }

    /** False while posts are paused after a 429 or errors. */
    fun canPublish(now: Long = System.currentTimeMillis()): Boolean = synchronized(backoff) {
        loadBackoffLocked()
        backoff.canSend(now)
    }

    /** Posts one sealed message to the house and updates the backoff. Returns the HTTP status (-1: no answer). */
    internal suspend fun postSealed(sealed: String): Int {
        val code = try {
            Http.postText(BASE + topic, sealed)
            200
        } catch (e: HttpException) {
            e.code
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
        val now = System.currentTimeMillis()
        synchronized(backoff) {
            loadBackoffLocked()
            val before = backoff.pausedUntil
            // Jitter keeps the TVs of a house from all coming back at the same moment.
            val jitter = when {
                code == 429 -> rng.nextInt(5 * 60_000).toLong()
                code !in 200..299 -> rng.nextInt(10_000).toLong()
                else -> 0L
            }
            backoff.onResult(code, now, jitter)
            if (backoff.pausedUntil != before || code == 429) {
                Prefs.putJson("sync_pause", "${backoff.pausedUntil},${backoff.rateWindowMs},${backoff.lastRateLimitAt}")
            }
        }
        return code
    }

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
        scope.launch { if (canPublish()) runCatching { publishRequest() } }
        ProfileSync.start()
    }

    /** Settings copied by "Copy once": everything shared except Real-Debrid, so the other person keeps their own. */
    private val ONCE = SHARED.filterNot { it.startsWith("rd_") }

    /** True while waiting for a one-time copy (kept in settings, so it survives a restart). */
    private fun copyOnce(): Boolean = Prefs.json("house_once") == "1"

    /**
     * Joins another TV's house and takes its settings. With [once], this TV takes one copy (keeping its own
     * Real-Debrid), then leaves at once: later changes on either side never reach the other.
     */
    @Synchronized
    fun join(id: String, key: String, once: Boolean = false) {
        Prefs.putJson("house_once", if (once) "1" else "")
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
        ProfileSync.onHouseChanged()
        val gen = ++generation
        scope.launch { listen(gen) }
        scope.launch {
            delay(1500)
            if (canPublish()) runCatching { publishRequest() } // else watch() asks again after the pause
        }
    }

    /** Stops sharing: this TV keeps its current settings in a house of its own. */
    @Synchronized
    fun leave() {
        synchronized(lock) {
            newHouse()
            lastText = snapshot()
        }
        ProfileSync.onHouseChanged()
        val gen = ++generation
        scope.launch { listen(gen) }
    }

    /**
     * Sends this TV's settings to the house whenever they change. A failed send is retried after the
     * backoff (the change is not lost). While joining, asks the house again every 5 minutes.
     */
    private suspend fun watch() {
        while (true) {
            delay(4000)
            if (!canPublish()) continue
            if (joining()) {
                val t = System.currentTimeMillis()
                if (t - lastRequestAt > 5 * 60_000L) runCatching { publishRequest() }
                continue
            }
            var previous = ""
            var current = ""
            val stamp = synchronized(lock) {
                if (joining()) {
                    0L // wait for the other TV's copy before sending ours
                } else {
                    val now = snapshot()
                    if (now == lastText) {
                        0L
                    } else {
                        previous = lastText
                        current = now
                        lastText = now
                        System.currentTimeMillis().also { Prefs.houseStamp = it }
                    }
                }
            }
            if (stamp > 0L) {
                val ok = runCatching { publishSync(stamp) }.getOrDefault(false)
                // Not sent: mark it unsent again, so the next round (after the backoff) sends it.
                if (!ok) synchronized(lock) { if (lastText == current) lastText = previous }
            }
        }
    }

    private suspend fun post(o: JSONObject): Boolean = postSealed(Relay.encryptWith(Prefs.houseKey, o.toString())) in 200..299

    private suspend fun publishRequest() {
        lastRequestAt = System.currentTimeMillis()
        // "once": a friend's TV doing "Copy once". The house does not count it as one of its TVs.
        post(JSONObject().put("type", "sync_req").put("from", Prefs.deviceId).put("ts", System.currentTimeMillis()).put("once", copyOnce()))
    }

    /** True when every part was sent. */
    private suspend fun publishSync(stamp: Long): Boolean {
        val text = snapshot()
        val parts = text.chunked(CHUNK).ifEmpty { listOf("{}") }
        val sid = randomId()
        parts.forEachIndexed { i, chunk ->
            if (i > 0) delay(500L) // ntfy's burst limit is shared by the whole house
            val ok = post(
                JSONObject()
                    .put("type", "sync")
                    .put("from", Prefs.deviceId)
                    .put("sid", sid)
                    .put("part", i)
                    .put("parts", parts.size)
                    .put("ts", stamp)
                    .put("chunk", chunk),
            )
            if (!ok) return false
        }
        return true
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
        val from = m.optString("from")
        if (from == Prefs.deviceId) return
        if (profileSyncAllowed() && !m.optBoolean("once", false)) ProfileSync.notePeer(from)
        when (m.optString("type")) {
            "pdelta", "pstate", "pdigest" -> if (profileSyncAllowed()) runCatching { ProfileSync.onMessage(m) }
            "sync_req" -> {
                // A TV joined or opened: send ours, unless we have nothing yet ourselves.
                if (joining()) return
                val now = System.currentTimeMillis()
                if (now - lastReplyAt < 20_000L || !canPublish(now)) return
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
                val once = copyOnce()
                val wasJoining = joining()
                val applied = synchronized(lock) {
                    if (ts <= Prefs.houseStamp) return@synchronized false
                    Prefs.importKeys(if (once) ONCE else SHARED, data)
                    Prefs.houseStamp = ts
                    lastText = snapshot()
                    true
                }
                if (applied && once) {
                    Prefs.putJson("house_once", "")
                    leave()
                    LocalWeb.lastMessage = "Settings copied from the other TV"
                    runCatching { Relay.publishState("Settings copied. This TV keeps its own settings from now on.") }
                    return
                }
                if (applied && wasJoining) ProfileSync.onJoinedHouse() // now compare profiles with the house
                if (applied) {
                    LocalWeb.lastMessage = "Settings updated from your other TV"
                    runCatching { Relay.publishState("Settings copied from your other TV") }
                }
            }
        }
    }
}
