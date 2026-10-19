package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * Spotify in Jarvis, phase 1: the pure logic (no Android). Spotify.kt does the Android work (sign-in, Web API calls,
 * App Remote, the token vault). Tests in SpotifyCoreTest.
 *
 * Supported routes only: Spotify closed its mobile streaming SDKs in 2022, so Jarvis never streams audio itself.
 * It signs in with Spotify's Android Authorization Library (Authorization Code with PKCE, no client secret), reads
 * playlists with the Web API and tells the official Spotify app on the same TV what to play: through the App Remote
 * library when the phone build of Spotify (com.spotify.music) is installed, else through Spotify Connect (the Web
 * API's player endpoints) aimed at the Spotify TV app on this TV.
 */

/** Client ID, redirect URI and scopes. The Client ID is configuration, never a secret, never committed. */
object SpotifyConfig {
    /** Must be added, exactly, to the Spotify developer app's Redirect URIs. Matches the AndroidManifest intent filter. */
    const val REDIRECT_URI = "jarvis-tv://spotify-callback"

    val SCOPES = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-read-playback-state",
        "user-modify-playback-state",
        "app-remote-control",
    )

    private val CLIENT_ID = Regex("[0-9a-f]{32}")

    /** A Client ID as pasted (spaces, capitals) made clean, or "" when it is not one (32 hex characters). */
    fun cleanClientId(raw: String?): String {
        val s = raw.orEmpty().trim().lowercase()
        return if (CLIENT_ID.matches(s)) s else ""
    }

    /** The Client ID saved on the Control page wins over one built into the APK (local build property). */
    fun clientId(saved: String, built: String): String = cleanClientId(saved).ifEmpty { cleanClientId(built) }

    /** Phase 1: the Spotify section shows for the Dad profile only. Other profiles see no change. */
    fun enabledFor(profile: String): Boolean = profile == "p1"
}

/** Spotify links and URIs. */
object SpotifyUri {
    /** Kinds Jarvis plays. A track or episode plays alone; the rest play as a context (whole playlist, album...). */
    val KINDS = setOf("playlist", "album", "artist", "track", "episode", "show")

    private val ID = Regex("[A-Za-z0-9]{22}")
    private val LOCALE = Regex("intl-[a-z]{2}(-[a-z]{2})?", RegexOption.IGNORE_CASE)

    data class Ref(val kind: String, val id: String) {
        val uri: String get() = "spotify:$kind:$id"
        /** True for a playlist, album, artist or show (Web API "context_uri"); false for one track or episode ("uris"). */
        val isContext: Boolean get() = kind != "track" && kind != "episode"
    }

    /**
     * Reads "spotify:playlist:<id>", the old "spotify:user:<name>:playlist:<id>" or a share link
     * "https://open.spotify.com/(intl-xx/)(embed/)playlist/<id>?si=...". Anything else (short spotify.link
     * links, liked songs, local files) is null.
     */
    fun parse(input: String?): Ref? {
        val s = input.orEmpty().trim()
        if (s.isEmpty() || s.length > 300) return null
        if (s.startsWith("spotify:", ignoreCase = true)) {
            val p = s.split(':')
            return when {
                p.size == 3 -> ref(p[1], p[2])
                p.size == 5 && p[1].equals("user", true) && p[3].equals("playlist", true) -> ref("playlist", p[4])
                else -> null
            }
        }
        val rest = when {
            s.startsWith("https://", true) -> s.substring(8)
            s.startsWith("http://", true) -> s.substring(7)
            s.startsWith("open.spotify.com/", true) -> s
            else -> return null
        }
        val host = rest.substringBefore('/')
        if (!host.equals("open.spotify.com", true)) return null
        val parts = rest.substringAfter('/', "").substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
            .dropWhile { LOCALE.matches(it) || it.equals("embed", true) }
        if (parts.size < 2) return null
        if (parts[0].equals("user", true) && parts.size >= 4 && parts[2].equals("playlist", true)) return ref("playlist", parts[3])
        return ref(parts[0], parts[1])
    }

    private fun ref(kind: String, id: String): Ref? {
        val k = kind.lowercase()
        return if (k in KINDS && ID.matches(id)) Ref(k, id) else null
    }
}

/** Relay commands for Spotify, from the web app or the Control page. */
object SpotifyCommand {
    val CONTROLS = setOf("pause", "resume", "next", "previous")

    sealed interface Action
    data class Play(val ref: SpotifyUri.Ref) : Action
    data class Control(val action: String) : Action
    /** The phone's playlist list (names and URIs only). [offset] pages through a long list. */
    data class Playlists(val offset: Int) : Action
    /** Not done. [message] is the plain answer the TV sends back. */
    data class Reject(val message: String) : Action

    /** Commands older than this (the TV was off) are dropped, so music does not start by surprise later. */
    const val STALE_MS = 10 * 60_000L

    /**
     * What a decrypted relay command asks this TV ([deviceId]) to do, or null when it is not a Spotify command, is
     * stale or is for another TV. Spotify commands must name their TV ("to", or the alias "tv"): a command without a
     * target is refused, never played. Nothing is ever broadcast.
     */
    fun route(cmd: JSONObject, deviceId: String, now: Long): Action? {
        val name = cmd.optString("cmd")
        if (name != "spotify_play" && name != "spotify_control" && name != "spotify_list") return null
        val target = cmd.optString("to").ifBlank { cmd.optString("tv") }
        if (target.isBlank()) return Reject("Pick a TV first.")
        if (deviceId.isBlank() || target != deviceId) return null
        val ts = cmd.optLong("ts", 0L)
        if (ts > 0L && now - ts > STALE_MS) return null
        return when (name) {
            "spotify_play" -> SpotifyUri.parse(cmd.optString("uri"))?.let { Play(it) } ?: Reject("That is not a Spotify link.")
            "spotify_control" -> cmd.optString("action").takeIf { it in CONTROLS }?.let { Control(it) } ?: Reject("Unknown control.")
            else -> Playlists(cmd.optInt("offset", 0).coerceIn(0, 1000))
        }
    }
}

/** Web API JSON to Jarvis types. Tolerant: missing fields and null items are skipped, never a crash. */
object SpotifyMap {
    data class Playlist(val id: String, val name: String, val uri: String, val owner: String, val tracks: Int, val image: String?)
    data class Page(val items: List<Playlist>, val next: String?, val total: Int)
    data class Device(val id: String, val name: String, val type: String, val active: Boolean, val restricted: Boolean)

    /** A page of playlists: GET /v1/me/playlists, or the "playlists" object of GET /v1/search. */
    fun playlists(json: JSONObject?): Page {
        val a = json?.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Playlist>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue // search can return null entries
            val ref = SpotifyUri.parse(o.optString("uri")) ?: continue
            if (ref.kind != "playlist") continue
            // The track count was "tracks.total"; newer responses call it "items.total". Read either.
            val count = (o.optJSONObject("tracks") ?: o.optJSONObject("items"))?.optInt("total", 0) ?: 0
            val img = o.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
            out.add(
                Playlist(
                    id = ref.id,
                    name = o.optString("name").trim().ifEmpty { "Untitled playlist" }.take(100),
                    uri = ref.uri,
                    owner = o.optJSONObject("owner")?.optString("display_name").orEmpty().take(60),
                    tracks = count,
                    image = img.takeIf { it.startsWith("https://") },
                ),
            )
        }
        val next = json?.optString("next").orEmpty().takeIf { it.startsWith("https://api.spotify.com/") }
        return Page(out, next, json?.optInt("total", out.size) ?: 0)
    }

    /** GET /v1/search?type=playlist wraps the page in "playlists". */
    fun searchPlaylists(json: JSONObject?): Page = playlists(json?.optJSONObject("playlists"))

    /** GET /v1/me/player/devices. Devices with no id (restricted) are kept but cannot be targeted. */
    fun devices(json: JSONObject?): List<Device> {
        val a = json?.optJSONArray("devices") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Device(
                id = if (o.isNull("id")) "" else o.optString("id"),
                name = o.optString("name").take(80),
                type = o.optString("type"),
                active = o.optBoolean("is_active"),
                restricted = o.optBoolean("is_restricted"),
            )
        }
    }

    /**
     * GET /v1/me/player (204 = nothing: pass null). Only items with a name count. With [onDevice], playback on any
     * other device (J's phone, the car) is not this TV's music and gives null.
     */
    fun player(json: JSONObject?, now: Long, onDevice: String? = null): MusicStatus.State? {
        if (json != null && onDevice != null && json.optJSONObject("device")?.optString("id") != onDevice) return null
        val item = json?.optJSONObject("item") ?: return null
        val name = item.optString("name").trim()
        if (name.isEmpty()) return null
        val artists = item.optJSONArray("artists")
        val artist = if (artists != null) {
            (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }.joinToString(", ")
        } else {
            item.optJSONObject("show")?.optString("name").orEmpty()
        }
        return MusicStatus.State(track = name, artist = artist, playing = json.optBoolean("is_playing"), uri = item.optString("uri"), ts = now)
    }

    /**
     * Playlists for the phone ("spotify_list" answer): names and URIs only, as [["name","uri",tracks],...], cut so the
     * JSON stays within [maxChars] (the relay message limit, before encryption). Returns the array and how many fit.
     */
    fun forPhone(items: List<Playlist>, maxChars: Int): Pair<JSONArray, Int> {
        val a = JSONArray()
        var used = 2
        var n = 0
        for (p in items) {
            val row = JSONArray().put(p.name.take(60)).put(p.uri).put(p.tracks)
            val len = row.toString().length + 1
            if (used + len > maxChars) break
            a.put(row)
            used += len
            n++
        }
        return a to n
    }
}

/** How Jarvis reaches the Spotify app on this TV. */
object SpotifyRoute {
    enum class Kind {
        /** App Remote library: the phone build of Spotify (com.spotify.music) is installed here. */
        APP_REMOTE,
        /** Spotify Connect via the Web API: the Spotify TV app (com.spotify.tv.android) is installed here. */
        CONNECT,
        /** No Spotify app on this TV. */
        NONE,
    }

    /** Package names. App Remote binds to the phone app only; the TV app is reached through Spotify Connect. */
    val PHONE_APPS = listOf("com.spotify.music")
    val TV_APPS = listOf("com.spotify.tv.android")

    fun choose(installed: Set<String>): Kind = when {
        PHONE_APPS.any { it in installed } -> Kind.APP_REMOTE
        TV_APPS.any { it in installed } -> Kind.CONNECT
        else -> Kind.NONE
    }

    /**
     * The Spotify Connect device that is this TV: the one picked before (by [savedName], ids can change), else the
     * only TV device, else one whose name contains a [hints] word (this TV's name or model). Null = ask J to pick.
     */
    fun pickDevice(devices: List<SpotifyMap.Device>, savedName: String, hints: List<String>): SpotifyMap.Device? {
        val usable = devices.filter { it.id.isNotBlank() && !it.restricted }
        if (savedName.isNotBlank()) usable.firstOrNull { it.name.equals(savedName, true) }?.let { return it }
        val tvs = usable.filter { it.type.equals("TV", true) }
        if (tvs.size == 1) return tvs[0]
        val words = hints.map { it.trim() }.filter { it.length >= 3 }
        return (tvs.ifEmpty { usable }).firstOrNull { d -> words.any { d.name.contains(it, true) } }
    }
}

/** Signed-in Spotify tokens. Only ever stored on this TV (SpotifyVault, Android Keystore). [toString] hides them. */
data class SpotifyTokens(val access: String, val refresh: String, val expiresAt: Long, val scope: String = "") {
    /** Refresh a minute before Spotify's expiry. */
    fun needsRefresh(now: Long): Boolean = refresh.isNotEmpty() && now >= expiresAt - 60_000L

    fun toJson(): String = JSONObject()
        .put("a", access).put("r", refresh).put("e", expiresAt).put("s", scope).toString()

    /** A refresh answer from accounts.spotify.com/api/token. Spotify may send a new refresh token; else the old one stays. */
    fun refreshed(resp: JSONObject, now: Long): SpotifyTokens? {
        val a = resp.optString("access_token")
        if (a.isEmpty()) return null
        return SpotifyTokens(
            access = a,
            refresh = resp.optString("refresh_token").ifEmpty { refresh },
            expiresAt = now + resp.optLong("expires_in", 3600L) * 1000L,
            scope = resp.optString("scope").ifEmpty { scope },
        )
    }

    override fun toString(): String =
        "SpotifyTokens(access=${if (access.isEmpty()) "none" else "<hidden>"}, refresh=${if (refresh.isEmpty()) "none" else "<hidden>"}, expiresAt=$expiresAt)"

    companion object {
        fun fromJson(s: String?): SpotifyTokens? = runCatching {
            val o = JSONObject(s ?: return null)
            SpotifyTokens(o.getString("a"), o.optString("r"), o.optLong("e"), o.optString("s")).takeIf { it.access.isNotEmpty() }
        }.getOrNull()
    }
}

/** Hides Spotify secrets in any text before it reaches a log, an error message, the screen or the relay. */
object SpotifyRedact {
    private val FIELD = Regex(
        "(access_token|refresh_token|code_verifier|client_secret|\\bcode)(\"?\\s*[:=]\\s*\"?)[^\"&\\s,\\}]+",
        RegexOption.IGNORE_CASE,
    )
    private val BEARER = Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE)

    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return BEARER.replace(FIELD.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "<hidden>" }) { m -> m.groupValues[1] + "<hidden>" }
    }
}

/**
 * What Spotify plays on this TV, for "tv_status" on the house topic ("music"). Set by Spotify.kt; HouseSync sends
 * it with the same rules as NowPlaying: on play, pause, stop or a new track only, at most once per 10 s.
 */
object MusicStatus {
    data class State(
        val source: String = "spotify",
        val track: String,
        val artist: String,
        val playing: Boolean,
        val uri: String = "",
        val ts: Long = 0L,
    ) {
        val key: String get() = "$source:$track:$artist:$playing"
    }

    @Volatile var current: State? = null
        private set

    private var listener: ((State?) -> Unit)? = null

    fun onChange(l: ((State?) -> Unit)?) { listener = l }

    fun set(s: State?) {
        val old = current
        current = s
        if (changed(old, s)) listener?.invoke(s)
    }

    fun clear() = set(null)

    /** The "music" object of "tv_status": no tokens, no account details, just what plays. */
    fun json(s: State?): JSONObject? = s?.let {
        JSONObject()
            .put("source", it.source)
            .put("track", it.track.take(120))
            .put("artist", it.artist.take(120))
            .put("playing", it.playing)
            .put("ts", it.ts)
    }

    fun changed(last: State?, next: State?): Boolean {
        if (last == null && next == null) return false
        if (last == null || next == null) return true
        return last.key != next.key
    }
}
