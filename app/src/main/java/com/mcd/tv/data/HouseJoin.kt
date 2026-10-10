package com.mcd.tv.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Join my other TVs" with an 8-digit code. A new TV shows the code; on a phone already set up, Jarvis >
 * Settings > Add a TV takes the code and sends this house's link to the TV (docs/app/index.html, addTv).
 * The TV then joins the house (HouseSync.join): settings, Real-Debrid and every profile arrive within a minute.
 *
 *  - topic = "mcdtv-join-" + first 24 hex characters of SHA-256("jarvis-join|" + code), on ntfy.sh
 *  - key   = SHA-256("jarvis-join-key|" + code), base64url: the message is sealed like every relay message
 *  - message {type: "house_invite", house: "<id>.<key>", ts}
 * The code is only good while this screen waits (15 minutes), and only for messages sent after it was shown.
 */
object HouseJoin {
    private const val WAIT_MS = 15 * 60_000L
    private val rng = SecureRandom()

    fun newCode(): String = (0 until 8).map { rng.nextInt(10) }.joinToString("")

    private fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun topic(code: String): String =
        "mcdtv-join-" + sha("jarvis-join|$code").joinToString("") { "%02x".format(it) }.take(24)

    private fun keyB64(code: String): String =
        Base64.encodeToString(sha("jarvis-join-key|$code"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Waits for a phone to send the house link for [code] (messages after [sinceSec], unix seconds), then joins.
     * True when joined, false when the wait ran out.
     */
    suspend fun await(code: String, sinceSec: Long): Boolean {
        val end = System.currentTimeMillis() + WAIT_MS
        val key = keyB64(code)
        val url = "https://ntfy.sh/${topic(code)}/json?poll=1&since=$sinceSec"
        while (System.currentTimeMillis() < end) {
            val body = runCatching { Http.get(url, timeoutMs = 15_000) }.getOrNull()
            body?.lineSequence()?.forEach { line ->
                val ev = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                if (ev.optString("event") != "message") return@forEach
                val m = Relay.decryptWith(key, ev.optString("message")) ?: return@forEach
                if (m.optString("type") != "house_invite") return@forEach
                val link = m.optString("house")
                val dot = link.indexOf('.')
                if (dot <= 0 || dot == link.length - 1) return@forEach
                HouseSync.join(link.substring(0, dot), link.substring(dot + 1))
                Prefs.putJson("join_seen", "1")
                return true
            }
            delay(3_000)
        }
        return false
    }

    /** Offer "Join my other TVs" once, on a TV installed in the last 3 days (a new TV), never in the QA run. */
    fun shouldOffer(ctx: Context): Boolean {
        if (Prefs.qaMode || Prefs.json("join_seen") == "1") return false
        val installed = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime }.getOrDefault(0L)
        return installed > 0 && System.currentTimeMillis() - installed < 3 * 86_400_000L
    }

    fun markSeen() = Prefs.putJson("join_seen", "1")
}
