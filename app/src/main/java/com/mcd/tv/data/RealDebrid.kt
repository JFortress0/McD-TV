package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            intervalSec = o.optInt("interval", 5).coerceIn(1, 60), // never poll in a tight loop
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

    private const val RECONNECT = "Real-Debrid needs reconnecting. Go to Settings > Real-Debrid."

    /** Only one token refresh at a time; the others wait and reuse its result. */
    private val refreshLock = Mutex()

    private suspend fun token(): String {
        if (!connected) throw IllegalStateException("Connect Real-Debrid in Settings first")
        if (System.currentTimeMillis() > Prefs.rdExpiresAt) refresh(stale = Prefs.rdAccessToken, force = false)
        return Prefs.rdAccessToken
    }

    /**
     * Refreshes the access token. [stale] is the token the caller saw; if another caller already
     * replaced it while we waited for the lock, its new token is used instead of refreshing again.
     * A refused refresh (400/401) means the grant is gone: tokens are cleared and the user must reconnect.
     */
    private suspend fun refresh(stale: String, force: Boolean) {
        refreshLock.withLock {
            if (Prefs.rdAccessToken != stale && Prefs.rdAccessToken.isNotBlank()) return
            if (!force && System.currentTimeMillis() <= Prefs.rdExpiresAt) return
            if (!connected) throw IllegalStateException(RECONNECT)
            val reply = try {
                Http.postForm(
                    "$OAUTH/token",
                    mapOf(
                        "client_id" to Prefs.rdClientId, "client_secret" to Prefs.rdClientSecret,
                        "code" to Prefs.rdRefreshToken, "grant_type" to "http://oauth.net/grant_type/device/1.0",
                    ),
                )
            } catch (e: HttpException) {
                if (e.code == 400 || e.code == 401) {
                    Prefs.clearRealDebrid()
                    throw IllegalStateException(RECONNECT)
                }
                throw e
            }
            saveToken(JSONObject(reply))
        }
    }

    /**
     * Runs one authorized API call. If RD answers 401 (token revoked or expired early),
     * refreshes the token once and retries.
     */
    internal suspend fun <T> authed(call: suspend (Map<String, String>) -> T): T {
        val t = token()
        return try {
            call(mapOf("Authorization" to "Bearer $t"))
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            refresh(stale = t, force = true)
            call(mapOf("Authorization" to "Bearer ${Prefs.rdAccessToken}"))
        }
    }

    internal suspend fun apiGet(path: String): String = authed { Http.get("$API$path", it) }
    internal suspend fun apiPost(path: String, fields: Map<String, String>): String = authed { Http.postForm("$API$path", fields, it) }
    internal suspend fun apiDelete(path: String): String = authed { Http.delete("$API$path", it) }


    /** Account status line for Settings, e.g. "jfortress • premium until 2027-01-02". */
    suspend fun accountSummary(): String {
        val o = JSONObject(apiGet("/user"))
        val exp = (o.s("expiration") ?: "").take(10)
        return "${o.s("username") ?: "?"} • ${o.s("type") ?: "?"}" + if (exp.isNotBlank()) " until $exp" else ""
    }

    /** Hoster / RD link -> direct streamable URL. */
    suspend fun unrestrict(link: String): String {
        val o = JSONObject(apiPost("/unrestrict/link", mapOf("link" to link)))
        return o.getString("download")
    }

    internal val videoExt = Regex("\\.(mkv|mp4|avi|m4v|mov|webm|ts)$", RegexOption.IGNORE_CASE)
    private val failedStatuses = setOf("magnet_error", "error", "virus", "dead")
    private val busyStatuses = setOf("queued", "downloading", "compressing", "uploading")

    internal suspend fun torrentInfo(id: String): JSONObject = JSONObject(apiGet("/torrents/info/$id"))

    /** Waits (up to ~10 s) while RD is still turning the magnet into a file list. */
    internal suspend fun waitForFiles(id: String): JSONObject {
        var info = torrentInfo(id)
        var waited = 0L
        while (info.optString("status") == "magnet_conversion" && waited < 10_000) {
            delay(1000)
            waited += 1000
            info = torrentInfo(id)
        }
        return info
    }

    private fun filesOf(info: JSONObject): List<JSONObject> {
        val files: JSONArray = info.optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { files.getJSONObject(it) }
    }

    private fun isVideo(f: JSONObject) = videoExt.containsMatchIn(f.optString("path"))

    /**
     * Picks the file to play:
     *  1. the addon's fileIdx (Stremio counts from 0, RD file ids from 1), else the old by-position match;
     *  2. for TV, the file named for the episode (S01E05, s1e5, 1x05);
     *  3. the largest video file.
     */
    private fun chooseFile(list: List<JSONObject>, fileIdx: Int?, season: Int?, episode: Int?): JSONObject? {
        if (fileIdx != null) {
            list.firstOrNull { it.optInt("id") == fileIdx + 1 }?.takeIf { isVideo(it) }?.let { return it }
            list.getOrNull(fileIdx)?.takeIf { isVideo(it) }?.let { return it }
        }
        val videos = list.filter { isVideo(it) }
        if (season != null && episode != null && season > 0 && episode > 0) {
            val rx = Regex(
                "(s0*$season[ ._-]?e0*$episode(?!\\d))|((?<!\\d)0*${season}x0*$episode(?!\\d))",
                RegexOption.IGNORE_CASE,
            )
            videos.filter { rx.containsMatchIn(it.optString("path").substringAfterLast('/')) }
                .maxByOrNull { it.optLong("bytes") }?.let { return it }
            videos.filter { rx.containsMatchIn(it.optString("path")) }
                .maxByOrNull { it.optLong("bytes") }?.let { return it }
        }
        return videos.maxByOrNull { it.optLong("bytes") }
    }

    /** The RD link for [chosen] in a downloaded torrent. RD gives one link per selected file, in file order. */
    private fun linkFor(info: JSONObject, chosen: JSONObject?): String? {
        val links = info.optJSONArray("links") ?: return null
        if (links.length() == 0) return null
        val selected = filesOf(info).filter { it.optInt("selected") == 1 }.sortedBy { it.optInt("id") }
        val i = if (chosen == null) 0 else selected.indexOfFirst { it.optInt("id") == chosen.optInt("id") }
        if (i < 0 || i >= links.length()) return null
        return links.getString(i)
    }

    /** The newest torrent with this hash already in your RD account (first page only), or null. */
    private suspend fun findExisting(hash: String): JSONObject? {
        val body = apiGet("/torrents?limit=100&page=1")
        if (body.isBlank()) return null // RD answers 204 with no body when the list is empty
        val a = JSONArray(body)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .firstOrNull { it.optString("hash").equals(hash, ignoreCase = true) }
    }

    /** A stream for the Jarvis web app: the file link, plus RD's browser-friendly versions when RD has them. */
    data class WebStream(val direct: String, val rdId: String, val mime: String, val filename: String, val transcode: JSONObject?)

    /**
     * Resolves a source for the web app's "Play here". Browsers can't call RD's API (it sends no CORS headers),
     * so the TV does every RD call and the phone only plays the links it gets back. The RD token stays on the TV.
     * [url] sources play as they are when RD does not know them.
     */
    suspend fun resolveForWeb(url: String?, infoHash: String?, fileIdx: Int?, season: Int?, episode: Int?): WebStream {
        if (!connected) {
            if (url != null) return WebStream(url, "", "", "", null)
            throw IllegalStateException("Connect Real-Debrid on the TV first (Settings > Real-Debrid).")
        }
        val o: JSONObject = when {
            infoHash != null -> unrestrictInfo(resolveHashLink(infoHash, fileIdx, season, episode))
            url != null -> hashInUrl(url)?.let { (h, idx) -> unrestrictInfo(resolveHashLink(h, fileIdx ?: idx, season, episode)) }
                ?: webUnrestrictUrl(url) ?: return WebStream(url, "", "", "", null)
            else -> throw IllegalStateException("This source has no playable link")
        }
        val direct = o.optString("download")
        if (direct.isBlank()) throw IllegalStateException("Real-Debrid gave no link for this source.")
        val id = o.optString("id")
        val tc = if (id.isBlank() || o.optInt("streamable", 1) == 0) null else try {
            JSONObject(apiGet("/streaming/transcode/$id"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // no transcodes: the phone plays the file itself
        }
        return WebStream(direct, id, o.optString("mimeType"), o.optString("filename"), tc)
    }

    /**
     * Debrid addon links (Torrentio and others) carry the torrent hash in the path, for example
     * /resolve/realdebrid/<key>/<infoHash>/<name>/<fileIdx>/<file>. Resolving the hash directly gets RD's
     * browser-friendly versions, which the addon's redirect hides. Returns the hash and file index, or null.
     */
    internal fun hashInUrl(url: String): Pair<String, Int?>? {
        val parts = runCatching { java.net.URI(url).rawPath.split('/').map { java.net.URLDecoder.decode(it, "UTF-8") } }.getOrNull() ?: return null
        val at = parts.indexOfFirst { HASH40.matches(it) }
        if (at < 0) return null
        val idx = parts.drop(at + 1).take(3).firstOrNull { it.length in 1..4 && it.all(Char::isDigit) }?.toInt()
        return parts[at].lowercase() to idx
    }
    private val HASH40 = Regex("[A-Fa-f0-9]{40}")

    private suspend fun unrestrictInfo(link: String): JSONObject = JSONObject(apiPost("/unrestrict/link", mapOf("link" to link)))

    /**
     * An addon URL through RD: RD unrestricts many links itself. Debrid addons often answer with a resolve URL
     * that redirects to an RD link, so up to 3 redirects are followed to find one. Null: RD does not know it.
     */
    private suspend fun webUnrestrictUrl(url: String): JSONObject? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        try {
            return unrestrictInfo(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("Real-Debrid needs reconnecting") == true) throw e
        } catch (e: Exception) {
            // not an RD link: look where it redirects
        }
        var cur = url
        repeat(3) {
            val next = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val c = java.net.URL(cur).openConnection() as java.net.HttpURLConnection
                    try {
                        c.instanceFollowRedirects = false
                        c.requestMethod = "HEAD"
                        c.connectTimeout = 8_000
                        c.readTimeout = 8_000
                        if (c.responseCode in 300..399) c.getHeaderField("Location")?.let { java.net.URL(java.net.URL(cur), it).toString() } else null
                    } finally {
                        c.disconnect()
                    }
                }.getOrNull()
            } ?: return null
            cur = next
            val host = runCatching { java.net.URL(cur).host.lowercase() }.getOrDefault("")
            if (host == "real-debrid.com" || host.endsWith(".real-debrid.com")) {
                return runCatching { unrestrictInfo(cur) }.getOrElse {
                    if (it is CancellationException) throw it
                    // RD's own download link that it won't unrestrict again: play it as is
                    JSONObject().put("download", cur)
                }
            }
        }
        return null
    }

    /**
     * Torrent hash -> direct URL, through your own RD account:
     * reuse the torrent if it is already in your account, otherwise add the magnet,
     * select the right file, and unrestrict once RD has it. Files RD has but this call did not add stay in your account.
     * Cached torrents finish in a few seconds. Uncached ones throw with the progress,
     * and torrents added here that do not play are deleted again so the RD cloud stays clean.
     */
    suspend fun resolveHash(infoHash: String, fileIdx: Int?, season: Int? = null, episode: Int? = null): String =
        unrestrict(resolveHashLink(infoHash, fileIdx, season, episode))

    /** [resolveHash] without the last step: the RD link (real-debrid.com/d/...) for the chosen file. */
    private suspend fun resolveHashLink(infoHash: String, fileIdx: Int?, season: Int?, episode: Int?): String {
        val hash = infoHash.trim().lowercase()

        var reuseId: String? = null
        val existing = try {
            findExisting(hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e // not connected / needs reconnecting
        } catch (e: Exception) {
            null // listing failed: just add the magnet
        }
        if (existing != null) {
            val id = existing.optString("id")
            when (existing.optString("status")) {
                "downloaded" -> {
                    val info = torrentInfo(id)
                    val chosen = chooseFile(filesOf(info), fileIdx, season, episode)
                    // If the file we want was not selected in that copy, fall through and add a fresh one.
                    linkFor(info, chosen)?.let { return it }
                }
                in busyStatuses ->
                    throw IllegalStateException("Not cached on Real-Debrid yet (${existing.optInt("progress")}% downloaded). Pick a cached source.")
                "waiting_files_selection", "magnet_conversion" -> reuseId = id
                else -> Unit // broken or unknown copy: add a fresh one
            }
        }

        val owned = reuseId == null
        val id = reuseId ?: JSONObject(apiPost("/torrents/addMagnet", mapOf("magnet" to "magnet:?xt=urn:btih:$hash"))).getString("id")
        try {
            var info = waitForFiles(id)
            var status = info.optString("status")
            if (status == "magnet_conversion") throw IllegalStateException("Real-Debrid is still reading this torrent. Pick another source.")
            if (status in failedStatuses) throw IllegalStateException("Real-Debrid: $status")
            val chosen = chooseFile(filesOf(info), fileIdx, season, episode)
            if (status == "waiting_files_selection") {
                apiPost("/torrents/selectFiles/$id", mapOf("files" to (chosen?.optInt("id")?.toString() ?: "all")))
            }
            repeat(8) {
                info = torrentInfo(id)
                status = info.optString("status")
                if (status == "downloaded") {
                    val link = linkFor(info, chosen) ?: info.optJSONArray("links")?.takeIf { it.length() > 0 }?.getString(0)
                    if (link != null) return link
                }
                if (status in failedStatuses) throw IllegalStateException("Real-Debrid: $status")
                delay(1500)
            }
            throw IllegalStateException("Not cached on Real-Debrid yet (${info.optInt("progress")}% downloaded). Pick a cached source.")
        } catch (e: Throwable) {
            // Not playable (uncached, failed, or the user backed out): remove it from the RD cloud.
            if (owned) withContext(NonCancellable) { runCatching { apiDelete("/torrents/delete/$id") } }
            throw e
        }
    }
}

/** One item in your Real-Debrid cloud (torrents you added to your own account). */
data class RdItem(val id: String, val name: String, val sizeGb: Double, val status: String, val progress: Int, val added: String)

/** One playable file inside a cloud item. */
data class RdFile(val name: String, val link: String, val sizeGb: Double)

object RdCloud {
    private val videoExt = RealDebrid.videoExt

    /** Everything in your RD cloud, newest first. */
    suspend fun list(): List<RdItem> {
        val body = RealDebrid.apiGet("/torrents?limit=200")
        if (body.isBlank()) return emptyList() // 204: empty cloud
        val a = JSONArray(body)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            RdItem(
                id = it.optString("id"),
                name = it.optString("filename"),
                sizeGb = it.optLong("bytes") / 1_073_741_824.0,
                status = it.optString("status"),
                progress = it.optInt("progress"),
                added = it.optString("added").take(10),
            )
        }
    }

    /** The video files in one item, in order, each with its RD link. */
    suspend fun files(id: String): List<RdFile> {
        val info = RealDebrid.torrentInfo(id)
        val links = info.optJSONArray("links") ?: JSONArray()
        val files = info.optJSONArray("files") ?: JSONArray()
        // RD returns one link per selected file, in file order.
        val selected = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optInt("selected") == 1 }
        return selected.mapIndexedNotNull { i, f ->
            val path = f.optString("path")
            if (!videoExt.containsMatchIn(path) || i >= links.length()) null
            else RdFile(path.substringAfterLast('/'), links.getString(i), f.optLong("bytes") / 1_073_741_824.0)
        }.sortedBy { it.name }
    }

    /** Adds a magnet link you supply to your RD account and selects its video files. */
    suspend fun addMagnet(magnet: String): String {
        val id = JSONObject(RealDebrid.apiPost("/torrents/addMagnet", mapOf("magnet" to magnet.trim()))).getString("id")
        val info = RealDebrid.waitForFiles(id)
        if (info.optString("status") == "waiting_files_selection") {
            val files = info.optJSONArray("files") ?: JSONArray()
            val ids = (0 until files.length()).map { files.getJSONObject(it) }
                .filter { videoExt.containsMatchIn(it.optString("path")) }.map { it.optInt("id") }
            RealDebrid.apiPost("/torrents/selectFiles/$id", mapOf("files" to if (ids.isEmpty()) "all" else ids.joinToString(",")))
        }
        return info.optString("filename", "Added")
    }
}

/** Turns any source into a URL the player can open. */
object Resolver {
    /**
     * meta (optional) supplies season/episode for TV when the source itself does not carry them,
     * so Real-Debrid picks the right file out of a season pack.
     */
    suspend fun resolve(s: StreamSource, meta: PlayMeta? = null): String = when {
        s.url != null && s.url.contains("real-debrid.com/d/") && RealDebrid.connected -> RealDebrid.unrestrict(s.url)
        s.url != null -> s.url
        s.infoHash != null -> {
            val tv = meta != null && meta.type == "tv" && meta.season > 0 && meta.episode > 0
            RealDebrid.resolveHash(
                s.infoHash,
                s.fileIdx,
                season = s.season ?: if (tv) meta?.season else null,
                episode = s.episode ?: if (tv) meta?.episode else null,
            )
        }
        else -> throw IllegalStateException("This source has no playable link")
    }

    /** Tries the top sources (filtered and sorted like the source list) until one resolves. Used by Play and Background Noise. */
    suspend fun best(list: List<StreamSource>, meta: PlayMeta? = null): Pair<StreamSource, String> {
        var last: Exception? = null
        // Same order as the source list: CAM and oversized files skipped unless nothing else is left.
        val episode = meta?.type == "tv" || list.any { it.episode != null }
        for (s in StreamInfo.playOrder(list, episode).take(5)) {
            try {
                return s to resolve(s, meta)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e
                // Real-Debrid needs reconnecting: no point trying the other torrents.
                if (e is IllegalStateException && e.message?.startsWith("Real-Debrid needs reconnecting") == true) throw e
            }
        }
        throw last ?: IllegalStateException("No sources found. Add an addon in Settings.")
    }
}
