package com.mcd.tv.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real-Debrid client using the official REST API.
 * Sign-in uses RD's device flow: the TV shows a short code, you enter it at
 * real-debrid.com/device on your phone. No passwords or keys are typed on the TV.
 */
object RealDebrid {
    /** Real-Debrid's published client id for open-source apps (device flow). */
    private const val OPEN_CLIENT_ID = "X245A4XAIBGVM"
    private const val OAUTH = "https://api.real-debrid.com/oauth/v2"
    private const val API = "https://api.real-debrid.com/rest/1.0"

    data class DeviceCode(val deviceCode: String, val userCode: String, val verifyUrl: String, val intervalSec: Int, val expiresSec: Int)

    val connected: Boolean get() = Prefs.rdRefreshToken.isNotBlank()

    suspend fun startDeviceLogin(): DeviceCode {
        val o = JSONObject(Http.get("$OAUTH/device/code?client_id=$OPEN_CLIENT_ID&new_credentials=yes"))
        return DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            verifyUrl = o.s("direct_verification_url") ?: o.s("verification_url") ?: "https://real-debrid.com/device",
            intervalSec = o.optInt("interval", 5),
            expiresSec = o.optInt("expires_in", 600),
        )
    }

    /** Call every intervalSec. Returns true once the user approved and tokens are saved. */
    suspend fun pollDeviceLogin(code: DeviceCode): Boolean {
        val creds = try {
            JSONObject(Http.get("$OAUTH/device/credentials?client_id=$OPEN_CLIENT_ID&code=${Http.enc(code.deviceCode)}"))
        } catch (e: HttpException) {
            return false // not approved yet
        }
        val id = creds.s("client_id") ?: return false
        val secret = creds.s("client_secret") ?: return false
        Prefs.rdClientId = id
        Prefs.rdClientSecret = secret
        saveToken(
            JSONObject(
                Http.postForm(
                    "$OAUTH/token",
                    mapOf(
                        "client_id" to id, "client_secret" to secret, "code" to code.deviceCode,
                        "grant_type" to "http://oauth.net/grant_type/device/1.0",
                    ),
                ),
            ),
        )
        return true
    }

    private fun saveToken(o: JSONObject) {
        Prefs.rdAccessToken = o.getString("access_token")
        Prefs.rdRefreshToken = o.getString("refresh_token")
        Prefs.rdExpiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
    }

    private suspend fun token(): String {
        if (!connected) throw IllegalStateException("Connect Real-Debrid in Settings first")
        if (System.currentTimeMillis() > Prefs.rdExpiresAt) {
            saveToken(
                JSONObject(
                    Http.postForm(
                        "$OAUTH/token",
                        mapOf(
                            "client_id" to Prefs.rdClientId, "client_secret" to Prefs.rdClientSecret,
                            "code" to Prefs.rdRefreshToken, "grant_type" to "http://oauth.net/grant_type/device/1.0",
                        ),
                    ),
                ),
            )
        }
        return Prefs.rdAccessToken
    }

    private suspend fun auth() = mapOf("Authorization" to "Bearer ${token()}")

    /** Account status line for Settings, e.g. "jfortress • premium until 2027-01-02". */
    suspend fun accountSummary(): String {
        val o = JSONObject(Http.get("$API/user", auth()))
        val exp = (o.s("expiration") ?: "").take(10)
        return "${o.s("username") ?: "?"} • ${o.s("type") ?: "?"}" + if (exp.isNotBlank()) " until $exp" else ""
    }

    /** Hoster / RD link -> direct streamable URL. */
    suspend fun unrestrict(link: String): String {
        val o = JSONObject(Http.postForm("$API/unrestrict/link", mapOf("link" to link), auth()))
        return o.getString("download")
    }

    private val videoExt = Regex("\\.(mkv|mp4|avi|m4v|mov|webm|ts)$", RegexOption.IGNORE_CASE)

    /**
     * Torrent hash -> direct URL, through your own RD account:
     * add magnet, select the right file, and unrestrict once RD has it.
     * Cached torrents finish in a few seconds. Uncached ones throw with the progress.
     */
    suspend fun resolveHash(infoHash: String, fileIdx: Int?): String {
        val h = auth()
        val added = JSONObject(Http.postForm("$API/torrents/addMagnet", mapOf("magnet" to "magnet:?xt=urn:btih:$infoHash"), h))
        val id = added.getString("id")
        var info = JSONObject(Http.get("$API/torrents/info/$id", h))
        val files: JSONArray = info.optJSONArray("files") ?: JSONArray()
        val list = (0 until files.length()).map { files.getJSONObject(it) }
        val chosen = fileIdx?.let { idx -> list.getOrNull(idx)?.takeIf { videoExt.containsMatchIn(it.optString("path")) } }
            ?: list.filter { videoExt.containsMatchIn(it.optString("path")) }.maxByOrNull { it.optLong("bytes") }
        Http.postForm("$API/torrents/selectFiles/$id", mapOf("files" to (chosen?.optInt("id")?.toString() ?: "all")), h)

        repeat(8) {
            info = JSONObject(Http.get("$API/torrents/info/$id", h))
            val status = info.optString("status")
            if (status == "downloaded") {
                val links = info.optJSONArray("links")
                if (links != null && links.length() > 0) return unrestrict(links.getString(0))
            }
            if (status in listOf("magnet_error", "error", "virus", "dead")) throw IllegalStateException("Real-Debrid: $status")
            delay(1500)
        }
        throw IllegalStateException("Not cached on Real-Debrid yet (${info.optInt("progress")}% downloaded). Pick a cached source.")
    }
}

/** Turns any source into a URL the player can open. */
object Resolver {
    suspend fun resolve(s: StreamSource): String = when {
        s.url != null && s.url.contains("real-debrid.com/d/") && RealDebrid.connected -> RealDebrid.unrestrict(s.url)
        s.url != null -> s.url
        s.infoHash != null -> RealDebrid.resolveHash(s.infoHash, s.fileIdx)
        else -> throw IllegalStateException("This source has no playable link")
    }

    /** Tries the top sources in order until one resolves. Used by Play and Mindless TV. */
    suspend fun best(list: List<StreamSource>): Pair<StreamSource, String> {
        var last: Exception? = null
        for (s in list.take(5)) {
            try {
                return s to resolve(s)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: IllegalStateException("No sources found. Add an addon in Settings.")
    }
}
