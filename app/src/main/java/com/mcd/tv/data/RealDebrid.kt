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

    private const val RECONNECT = "Real-Debrid needs reconnecting. Go to Settings > Playback."

    /** Only one token refresh at a time; the others wait and reuse its result. */
    private val refreshLock = Mutex()

    /** Paces every RD call this TV makes and backs off after "too many requests" (RdLimiter). */
    internal val limiter = RdLimiter(clock = { System.currentTimeMillis() })

    /** One new torrent per 25 s on this TV: RD refuses faster adds with error 35 (RdAddGate). */
    internal val addGate = RdAddGate(
        clock = { System.currentTimeMillis() },
        load = { Prefs.rdLastAdd },
        save = { Prefs.rdLastAdd = it },
    )

    /** How long until this TV may send the next new torrent to RD, in ms. */
    fun addWaitMs(): Long = addGate.waitMs()

    /**
     * POST /torrents/addMagnet through [addGate]. Returns RD's torrent id.
     * A 451 / error 35 on an add sent a full gap after the last one is RD refusing the file, and callers
     * remember it (RdFilter.markRefused). An add after a shorter gap must never be remembered as refused:
     * that 35 is RD's add throttle. The gate makes that case impossible; if it happens anyway, the code is dropped.
     */
    internal suspend fun postAdd(magnet: String): String {
        val ticket = addGate.acquireAdd()
        try {
            return JSONObject(apiPost("/torrents/addMagnet", mapOf("magnet" to magnet))).getString("id")
        } catch (e: RdRefused) {
            if (e.code == 35 && !ticket.fullGap) throw RdRefused(null, e.message.orEmpty(), e)
            throw e
        }
    }

    /** First page of the torrent list, kept 60 s so each attempt does not list the account again. */
    internal val torrents = RdTorrentCache(clock = { System.currentTimeMillis() })

    /**
     * Runs one RD request through [limiter]. "Too many requests" (HTTP 429 / error 34) starts the backoff
     * and becomes [RdBusy], so callers stop instead of trying more sources.
     */
    private suspend fun <T> limited(request: suspend () -> T): T {
        limiter.acquire()
        try {
            return request().also { limiter.onSuccess() }
        } catch (e: HttpException) {
            if (RdLimiter.isBusy(e.code, e.message.orEmpty())) {
                limiter.onBusy(e.retryAfter)
                throw RdBusy()
            }
            limiter.onSuccess() // RD answered (a refusal, not a rate limit)
            throw e
        }
    }

    private suspend fun token(): String {
        if (!connected) throw IllegalStateException("Connect Real-Debrid in Settings first")
        if (System.currentTimeMillis() > Prefs.rdExpiresAt) refresh(stale = Prefs.rdAccessToken, force = false)
        return Prefs.rdAccessToken
    }

    /**
     * Refreshes the access token. [stale] is the token the caller saw; if another caller already
     * replaced it while we waited for the lock, its new token is used instead of refreshing again.
     * A refused refresh (400/401/403) means the grant is gone: the user must reconnect on this TV.
     */
    private suspend fun refresh(stale: String, force: Boolean) {
        refreshLock.withLock {
            if (Prefs.rdAccessToken != stale && Prefs.rdAccessToken.isNotBlank()) return
            if (!force && System.currentTimeMillis() <= Prefs.rdExpiresAt) return
            if (!connected) throw IllegalStateException(RECONNECT)
            val reply = try {
                limited {
                    Http.postForm(
                        "$OAUTH/token",
                        mapOf(
                            "client_id" to Prefs.rdClientId, "client_secret" to Prefs.rdClientSecret,
                            "code" to Prefs.rdRefreshToken, "grant_type" to "http://oauth.net/grant_type/device/1.0",
                        ),
                    )
                }
            } catch (e: HttpException) {
                // RD refuses a dead grant with 400, 401 or 403 ("permission_denied", "{code} is invalid").
                // The tokens are NOT cleared here: HouseSync would copy the empty sign-in to every linked TV.
                // Reconnecting on this TV replaces them, and the new sign-in then reaches the other TVs.
                if (e.code == 400 || e.code == 401 || e.code == 403) throw IllegalStateException(RECONNECT)
                throw e
            }
            saveToken(JSONObject(reply))
        }
    }

    /**
     * Runs one authorized API call. If RD answers 401 (token revoked or expired early),
     * refreshes the token once and retries. Other refusals become a plain message (RdErrors).
     */
    internal suspend fun <T> authed(call: suspend (Map<String, String>) -> T): T {
        val t = token()
        return try {
            limited { call(mapOf("Authorization" to "Bearer $t")) }
        } catch (e: HttpException) {
            if (e.code != 401) throw refused(e)
            refresh(stale = t, force = true)
            try {
                limited { call(mapOf("Authorization" to "Bearer ${Prefs.rdAccessToken}")) }
            } catch (e2: HttpException) {
                throw refused(e2)
            }
        }
    }

    /** "HTTP 403 {"error":"ip_not_allowed",...}" to "Real-Debrid blocks the network this TV is on...". */
    private fun refused(e: HttpException): IllegalStateException =
        RdErrors.refusal(e.code, e.message.orEmpty(), e)

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

    /** RD's torrents/info is polled no faster than this. */
    private const val POLL_MS = 2_000L

    internal suspend fun torrentInfo(id: String): JSONObject =
        JSONObject(apiGet("/torrents/info/$id")).also { torrents.update(id, it.optString("status"), it.optInt("progress")) }

    /** Adds a magnet to the account and to this TV's copy of the list. Returns RD's torrent id. */
    private suspend fun addMagnetId(hash: String, magnet: String): String {
        val id = postAdd(magnet)
        torrents.add(RdTorrentCache.Entry(id, hash, "magnet_conversion", 0))
        return id
    }

    /** Waits (up to ~10 s) while RD is still turning the magnet into a file list. */
    internal suspend fun waitForFiles(id: String): JSONObject {
        var info = torrentInfo(id)
        var waited = 0L
        while (info.optString("status") == "magnet_conversion" && waited < 10_000) {
            delay(POLL_MS)
            waited += POLL_MS
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

    /** RD's torrent list JSON to cache entries. */
    internal fun entriesOf(body: String): List<RdTorrentCache.Entry> {
        if (body.isBlank()) return emptyList() // RD answers 204 with no body when the list is empty
        val a = JSONArray(body)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            RdTorrentCache.Entry(it.optString("id"), it.optString("hash").lowercase(), it.optString("status"), it.optInt("progress"))
        }
    }

    /**
     * The newest torrent with this hash already in your RD account (first page only), or null.
     * Uses this TV's copy of the list while it is under 60 s old.
     */
    private suspend fun findExisting(hash: String): RdTorrentCache.Entry? =
        accountList().firstOrNull { it.hash.equals(hash, ignoreCase = true) }

    private suspend fun accountList(): List<RdTorrentCache.Entry> =
        torrents.get() ?: entriesOf(apiGet("/torrents?limit=100&page=1")).also { torrents.put(it) }

    /**
     * Hashes of the torrents already in your account (first page, cached 60 s). Those play without a new add.
     * Empty when the list can't be read; busy and reconnect errors are passed on.
     */
    suspend fun accountHashes(): Set<String> {
        if (!connected) return emptySet()
        return try {
            accountList().map { it.hash.lowercase() }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RdBusy) {
            throw e
        } catch (e: IllegalStateException) {
            if (e.message?.startsWith("Real-Debrid needs reconnecting") == true) throw e
            emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Deletes a torrent this TV added, from the account and from the cached list. */
    private suspend fun deleteTorrent(id: String) {
        torrents.remove(id)
        apiDelete("/torrents/delete/$id")
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
            throw IllegalStateException("Connect Real-Debrid on the TV first (Settings > Playback).")
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
            if (e is RdBusy || e.message?.startsWith("Real-Debrid needs reconnecting") == true) throw e
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
                    if (it is CancellationException || it is RdBusy) throw it
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
            val id = existing.id
            // The cached list can be up to 60 s old: check the torrent itself. Gone (deleted on another TV): add it again.
            val current = try {
                torrentInfo(id)
            } catch (e: RdRefused) {
                torrents.remove(id)
                null
            }
            if (current != null) when (current.optString("status")) {
                "downloaded" -> {
                    val chosen = chooseFile(filesOf(current), fileIdx, season, episode)
                    // If the file we want was not selected in that copy, fall through and add a fresh one.
                    linkFor(current, chosen)?.let { return it }
                }
                in busyStatuses ->
                    throw IllegalStateException("Not cached on Real-Debrid yet (${current.optInt("progress")}% downloaded). Pick a cached source.")
                "waiting_files_selection", "magnet_conversion" -> reuseId = id
                else -> Unit // broken or unknown copy: add a fresh one
            }
        }

        val owned = reuseId == null
        // A refusal here (error 35) is final for this hash: it throws at once, with no retry and nothing to delete.
        val id = reuseId ?: addMagnetId(hash, "magnet:?xt=urn:btih:$hash")
        try {
            var info = waitForFiles(id)
            var status = info.optString("status")
            if (status == "magnet_conversion") throw IllegalStateException("Real-Debrid is still reading this torrent. Pick another source.")
            if (status in failedStatuses) throw IllegalStateException("Real-Debrid: $status")
            val chosen = chooseFile(filesOf(info), fileIdx, season, episode)
            if (status == "waiting_files_selection") {
                apiPost("/torrents/selectFiles/$id", mapOf("files" to (chosen?.optInt("id")?.toString() ?: "all")))
            }
            // About 10 s in all, one check every 2 s.
            repeat(6) { n ->
                if (n > 0) delay(POLL_MS)
                info = torrentInfo(id)
                status = info.optString("status")
                if (status == "downloaded") {
                    val link = linkFor(info, chosen) ?: info.optJSONArray("links")?.takeIf { it.length() > 0 }?.getString(0)
                    if (link != null) return link
                }
                if (status in failedStatuses) throw IllegalStateException("Real-Debrid: $status")
            }
            throw IllegalStateException("Not cached on Real-Debrid yet (${info.optInt("progress")}% downloaded). Pick a cached source.")
        } catch (e: Throwable) {
            // Not playable (uncached, failed, or the user backed out): remove it from the RD cloud.
            if (owned) withContext(NonCancellable) { runCatching { deleteTorrent(id) } }
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
        RealDebrid.torrents.put(RealDebrid.entriesOf(body))
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
        val id = RealDebrid.postAdd(magnet.trim())
        RealDebrid.torrents.clear() // the list changed; the next lookup reads it again
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
    /** Sources Play best source tries before it gives up. */
    private const val MAX_TRIES = 8

    /**
     * meta (optional) supplies season/episode for TV when the source itself does not carry them,
     * so Real-Debrid picks the right file out of a season pack.
     */
    suspend fun resolve(s: StreamSource, meta: PlayMeta? = null): String = try {
        resolveOnce(s, meta)
    } catch (e: RdRefused) {
        // Error 35: RD refuses this file. Remember it so Play best source and the list skip it.
        if (e.code == 35) RdFilter.markRefused(s)
        throw e
    }

    private suspend fun resolveOnce(s: StreamSource, meta: PlayMeta?): String = when {
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

    /** How many new torrents (adds) one caller may still send to RD. Background Noise gives each round 2. */
    class AddAllowance(var left: Int)

    /**
     * Tries the top sources (filtered and sorted like the source list) until one resolves. Used by Play and
     * Background Noise. Torrents already in your RD account go first (no add needed), then the rest in list
     * order. RD takes one new torrent per 25 s, so between adds this waits and [onStatus] says why.
     * [allowance] caps the adds (null: no cap beyond the 8 tries). Cancelling the caller (Back) stops the wait.
     * Files in [flagged] (SourceHealth key to score, files that stop far short, see BadSources) are tried last.
     */
    suspend fun best(
        list: List<StreamSource>,
        meta: PlayMeta? = null,
        allowance: AddAllowance? = null,
        onStatus: (String) -> Unit = {},
        flagged: Map<String, Int> = emptyMap(),
    ): Pair<StreamSource, String> {
        var last: Exception? = null
        // Same order as the source list: CAM and oversized files skipped unless nothing else is left.
        val episode = meta?.type == "tv" || list.any { it.episode != null }
        // RD refuses many release names (error 35). Those sort last; ones it refused before are skipped.
        val order = StreamInfo.playOrder(list, episode)
        val fresh = order.filterNot { RdFilter.wasRefused(it) }.ifEmpty { order }
        // Your IPTV provider's copies are direct links (no Real-Debrid, no add limit): they go first.
        fresh.firstOrNull { XtreamVod.isIptv(it) && it.url != null && SourceHealth.key(it) !in flagged }?.let { s ->
            onStatus("Playing from your IPTV provider.")
            return s to s.url!!
        }
        val have = if (fresh.any { it.infoHash != null }) RealDebrid.accountHashes() else emptySet()
        fun inAccount(s: StreamSource) = s.infoHash != null && s.infoHash.lowercase() in have
        fun needsAdd(s: StreamSource) = s.infoHash != null && s.url == null && !inAccount(s)
        var adds = allowance?.left ?: Int.MAX_VALUE
        val picked = SourceHealth.demote(fresh.filter { inAccount(it) } + fresh.filterNot { inAccount(it) }, flagged, SourceHealth::key)
            .filter { !needsAdd(it) || adds-- > 0 }
            .take(MAX_TRIES)
        for ((i, s) in picked.withIndex()) {
            val add = needsAdd(s)
            if (add && allowance != null) allowance.left--
            onStatus(
                if (add && RealDebrid.addWaitMs() > 0) "Trying source ${i + 1} of ${picked.size}. Real-Debrid allows one new source every 25 seconds."
                else "Trying source ${i + 1} of ${picked.size}.",
            )
            try {
                return s to resolve(s, meta)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e
                // Real-Debrid needs reconnecting, or is busy (too many requests): no point trying the other torrents.
                if (e is RdBusy) throw e
                if (e is IllegalStateException && e.message?.startsWith("Real-Debrid needs reconnecting") == true) throw e
            }
        }
        if (last is RdRefused && (last as RdRefused).code == 35) {
            throw IllegalStateException("Real-Debrid refused the top sources (error 35). Play again tries the next ones.", last)
        }
        throw last ?: IllegalStateException("No sources found. Add an addon in Settings.")
    }
}
