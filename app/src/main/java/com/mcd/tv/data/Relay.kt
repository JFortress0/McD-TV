package com.mcd.tv.data

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Setup over the internet, so it works from any phone or computer, at home or away,
 * whatever the home network allows.
 *
 * How it works:
 *  - The TV makes a random link id and a random 256-bit key, and shows them as a QR code.
 *  - The McD TV Control web page (GitHub Pages) reads them from the QR link. The key sits
 *    after the "#", so browsers never send it to any server.
 *  - Page and TV exchange messages through ntfy.sh, a free public message relay. Every message
 *    is compressed and encrypted (AES-256-GCM) first, so the relay only ever sees random bytes,
 *    and nobody without the key can read or forge a message.
 */
object Relay {
    private const val BASE = "https://ntfy.sh/"
    const val CONTROL_PAGE = "https://jfortress0.github.io/McD-TV/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var generation = 0
    private val rng = SecureRandom()

    var status by mutableStateOf("Starting…")
    var lastMessageAt by mutableStateOf(0L)

    private val inTopic get() = "mcdtv-${Prefs.relayId}-in"
    private val outTopic get() = "mcdtv-${Prefs.relayId}-out"

    /** The link encoded in the QR code. */
    fun controlUrl(): String {
        ensureLink()
        return "$CONTROL_PAGE#${Prefs.relayId}.${Prefs.relayKey}"
    }

    private fun ensureLink() {
        if (Prefs.relayId.isBlank() || Prefs.relayKey.isBlank()) newLink()
    }

    /** Makes a fresh link. Phones and computers using the old link stop working. */
    @Synchronized
    fun newLink() {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        Prefs.relayId = (1..22).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        val key = ByteArray(32).also { rng.nextBytes(it) }
        Prefs.relayKey = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        Prefs.relaySince = ""
        generation++
        if (started) scope.launch { listen(generation) }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        ensureLink()
        scope.launch { listen(generation) }
        scope.launch { runCatching { publishState("TV started") } }
    }

    // ---------------- crypto: deflate, then AES-256-GCM; "v1:" + base64url(iv || ciphertext+tag) ----------------

    private fun key() = SecretKeySpec(Base64.decode(Prefs.relayKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), "AES")

    fun encrypt(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val zipped = ByteArrayOutputStream().also { out ->
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        }.toByteArray()
        deflater.end()
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val sealed = iv + c.doFinal(zipped)
        return "v1:" + Base64.encodeToString(sealed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decrypt(msg: String): JSONObject? = runCatching {
        if (!msg.startsWith("v1:")) return null
        val sealed = Base64.decode(msg.removePrefix("v1:"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        val zipped = c.doFinal(sealed.copyOfRange(12, sealed.size))
        val inflater = Inflater(true)
        inflater.setInput(zipped)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        JSONObject(out.toString("UTF-8"))
    }.getOrNull()

    // ---------------- listening for commands from the Control page ----------------

    private suspend fun listen(gen: Int) {
        var backoff = 2000L
        while (gen == generation) {
            var c: HttpURLConnection? = null
            try {
                val since = Prefs.relaySince.ifBlank { "12h" }
                c = URL("$BASE$inTopic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000 // ntfy sends a keepalive every ~45 s
                c.inputStream.bufferedReader().use { reader ->
                    status = "Linked. Waiting for your phone or computer."
                    backoff = 2000L
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (ev.optString("event") != "message") continue
                        Prefs.relaySince = ev.optString("id")
                        val cmd = decrypt(ev.optString("message")) ?: continue // not ours: ignore
                        lastMessageAt = System.currentTimeMillis()
                        handle(cmd)
                    }
                }
                delay(1000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status = "No internet connection to the setup relay. Retrying…"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { c?.disconnect() }
            }
        }
    }

    private suspend fun handle(cmd: JSONObject) {
        when (cmd.optString("cmd")) {
            "hello" -> publishState("Connected")
            "set" -> {
                apply(cmd.optJSONObject("data") ?: JSONObject())
                status = "Saved changes from the Control page"
                LocalWeb.lastMessage = "Saved changes from the Control page"
                publishState("Saved on the TV")
            }
            "magnet" -> {
                val note = runCatching { "Added to Real-Debrid: " + RdCloud.addMagnet(cmd.optString("magnet")) }
                    .getOrElse { "Real-Debrid add failed: ${it.message}" }
                publishState(note)
            }
        }
    }

    private fun apply(d: JSONObject) {
        d.optJSONArray("addon_urls")?.let { a ->
            val urls = List(a.length()) { a.getString(it) }
            Prefs.updateAddonUrls { urls }
        }
        d.optJSONArray("websites")?.let { a ->
            val sites = List(a.length()) { a.getJSONArray(it) }.map { it.optString(0) to it.optString(1) }
            Prefs.updateWebsites { sites }
        }
        if (d.has("m3u_url")) Prefs.m3uUrl = d.optString("m3u_url")
        if (d.has("custom_stream_url")) Prefs.customUrl = d.optString("custom_stream_url")
        d.optString("tmdb_key").takeIf { it.isNotBlank() }?.let { Prefs.tmdbKey = it }
        d.optString("mdblist_key").takeIf { it.isNotBlank() }?.let { Prefs.mdblistKey = it }
        d.optString("origin_filter").takeIf { it.isNotBlank() }?.let { k ->
            OriginFilter.entries.firstOrNull { it.key == k }?.let { Prefs.origin = it }
        }
        if (d.has("us_only")) Prefs.usOnly = d.optBoolean("us_only")
        if (d.has("slow_connection")) Prefs.slowConnection = d.optBoolean("slow_connection")
        if (d.has("play_intro")) Prefs.playIntro = d.optBoolean("play_intro")
    }

    /** Sends the TV's current setup to the Control page (encrypted). */
    suspend fun publishState(note: String) {
        val names = JSONObject()
        Prefs.addonUrls.forEach { u -> names.put(u, Addons.nameOf(u, fallback = "")) }
        val state = JSONObject()
            .put("type", "state")
            .put("note", note)
            .put("ts", System.currentTimeMillis())
            .put("addon_urls", JSONArray(Prefs.addonUrls))
            .put("addon_names", names)
            .put("websites", JSONArray(Prefs.websites.map { JSONArray(listOf(it.first, it.second)) }))
            .put("m3u_url", Prefs.m3uUrl)
            .put("custom_stream_url", Prefs.customUrl)
            .put("tmdb_set", Prefs.tmdbKey.isNotBlank())
            .put("mdblist_set", Prefs.mdblistKey.isNotBlank())
            .put("origin_filter", Prefs.origin.key)
            .put("us_only", Prefs.usOnly)
            .put("slow_connection", Prefs.slowConnection)
            .put("play_intro", Prefs.playIntro)
            .put("rd_connected", RealDebrid.connected)
            .put("version", com.mcd.tv.BuildConfig.VERSION_NAME)
        Http.postText(BASE + outTopic, encrypt(state.toString()))
    }
}
