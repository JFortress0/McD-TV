package com.mcd.tv.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Spotify in Jarvis, phase 1 (pure logic and the reasons behind it: SpotifyCore.kt).
 *
 *  - Sign-in: Spotify's Android Authorization Library. A TOKEN request is turned by the library into Authorization
 *    Code with PKCE (it makes the verifier, swaps the code and returns an access and a refresh token). No client
 *    secret anywhere. The browser (or the phone Spotify app) sends J back through SpotifyConfig.REDIRECT_URI.
 *  - Tokens: SpotifyVault only (Android Keystore). Never in Prefs, HouseSync, the relay, logs or the screen.
 *  - Library: Web API (/me/playlists, /search).
 *  - Playback: the official Spotify app on this TV plays; Jarvis stays on screen. App Remote when the phone build of
 *    Spotify is installed, else Spotify Connect (Web API player calls aimed at this TV's Spotify device).
 */
object Spotify {
    private const val API = "https://api.spotify.com/v1"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    /** Now playing check while the Spotify screen is open on the Connect route. Slow on purpose. */
    const val POLL_MS = 15_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()
    @Volatile private var tokens: SpotifyTokens? = null
    @Volatile private var activity: WeakReference<Activity>? = null
    @Volatile private var remote: SpotifyAppRemote? = null
    /** The Connect device last used (this TV's Spotify app). */
    @Volatile private var deviceId: String = ""

    /** Something J must do, and the button that does it. */
    enum class Fix(val button: String) {
        GET_APP("Get Spotify"),
        OPEN_APP("Open Spotify"),
        CONNECT("Connect Spotify"),
        PICK_DEVICE("Pick this TV"),
    }

    var connected by mutableStateOf(false)
        private set
    var route by mutableStateOf(SpotifyRoute.Kind.NONE)
        private set
    /** One plain sentence for the screen ("" = nothing to say). */
    var message by mutableStateOf("")
        private set
    var fix by mutableStateOf<Fix?>(null)
        private set
    var playlists by mutableStateOf<List<SpotifyMap.Playlist>>(emptyList())
        private set
    var results by mutableStateOf<List<SpotifyMap.Playlist>>(emptyList())
        private set
    private var playlistsNext: String? = null
    val hasMore: Boolean get() = playlistsNext != null
    /** Spotify Connect devices, shown when J must pick which one is this TV. */
    var devices by mutableStateOf<List<SpotifyMap.Device>>(emptyList())
        private set
    var now by mutableStateOf<MusicStatus.State?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val ready get() = ::app.isInitialized

    fun init(ctx: Context) {
        app = ctx.applicationContext
        tokens = SpotifyVault.load(app)
        connected = tokens != null
        refreshRoute()
    }

    /** The activity App Remote may show its one-time "Allow Jarvis" screen over. */
    fun attach(a: Activity) { activity = WeakReference(a) }

    fun clientId(): String = SpotifyConfig.clientId(Prefs.spotifyClientId, com.mcd.tv.BuildConfig.SPOTIFY_CLIENT_ID)

    fun refreshRoute() {
        if (!ready) return
        route = SpotifyRoute.choose((SpotifyRoute.PHONE_APPS + SpotifyRoute.TV_APPS).filter { installed(it) }.toSet())
    }

    private fun installed(pkg: String): Boolean = runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun say(text: String, f: Fix? = null) {
        message = SpotifyRedact.text(text)
        fix = f
    }

    fun clearMessage() = say("")

    // ------------------------------------------------------------------ sign-in

    /** The Spotify sign-in screen, or null with [message] saying why not (no Client ID, no browser). */
    fun signInIntent(a: Activity): Intent? {
        refreshRoute()
        val id = clientId()
        if (id.isEmpty()) { say("Add your Spotify Client ID on the Control page first."); return null }
        if (route != SpotifyRoute.Kind.APP_REMOTE && !hasBrowser()) {
            say("Spotify sign-in needs a web browser on this TV. Install a browser from the app store, then try again.")
            return null
        }
        val req = AuthorizationRequest.Builder(id, AuthorizationResponse.Type.TOKEN, SpotifyConfig.REDIRECT_URI)
            .setScopes(SpotifyConfig.SCOPES.toTypedArray())
            .build()
        return AuthorizationClient.createLoginActivityIntent(a, req)
    }

    private fun hasBrowser(): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.spotify.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        return i.resolveActivity(app.packageManager) != null
    }

    /** The sign-in screen's result. The response is never logged: its toString holds the token. */
    fun onSignInResult(resultCode: Int, data: Intent?) {
        val r = AuthorizationClient.getResponse(resultCode, data)
        when (r.type) {
            AuthorizationResponse.Type.TOKEN -> {
                val access = r.accessToken.orEmpty()
                if (access.isEmpty()) { say("Spotify sign-in did not finish. Try again."); return }
                val t = SpotifyTokens(
                    access = access,
                    refresh = r.refreshToken.orEmpty(),
                    expiresAt = System.currentTimeMillis() + r.expiresIn.coerceAtLeast(60) * 1000L,
                    scope = SpotifyConfig.SCOPES.joinToString(" "),
                )
                tokens = t
                SpotifyVault.save(app, t)
                connected = true
                say("Spotify is connected.")
                loadPlaylists()
            }
            AuthorizationResponse.Type.CANCELLED -> say("Sign-in was cancelled.")
            AuthorizationResponse.Type.ERROR -> say("Spotify sign-in failed: ${r.error.orEmpty()}")
            else -> say("Spotify sign-in did not finish. Try again.")
        }
    }

    /** Forgets the tokens on this TV. Spotify itself stays signed in on the TV. */
    fun signOut() {
        if (ready) SpotifyVault.clear(app)
        tokens = null
        connected = false
        playlists = emptyList()
        results = emptyList()
        playlistsNext = null
        remote?.let { r -> runCatching { SpotifyAppRemote.disconnect(r) } }
        remote = null
        showNow(null)
        say("Spotify is disconnected on this TV.")
    }

    // ------------------------------------------------------------------ explicit buttons

    /** Opens the Spotify app on this TV. Only ever from a button J pressed. */
    fun openSpotifyApp(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val pkg = (SpotifyRoute.TV_APPS + SpotifyRoute.PHONE_APPS).firstOrNull { installed(it) } ?: return false
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Opens the Spotify TV app's page in this TV's app store. Only ever from a button J pressed. */
    fun openStore(ctx: Context): Boolean {
        val pkg = SpotifyRoute.TV_APPS.first()
        for (u in listOf("amzn://apps/android?p=$pkg", "market://details?id=$pkg")) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null && runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        say("Open the app store on this TV and install Spotify.")
        return false
    }

    /** J picked which Spotify Connect device is this TV. */
    fun pickDevice(d: SpotifyMap.Device) {
        Prefs.spotifyDevice = d.name
        deviceId = d.id
        devices = emptyList()
        say("Jarvis will play on ${d.name}.")
    }

    // ------------------------------------------------------------------ library

    fun loadPlaylists(more: Boolean = false) = scope.launch {
        if (!connected) return@launch
        runBusy {
            val url = if (more) playlistsNext ?: return@runBusy else "$API/me/playlists?limit=50"
            val page = SpotifyMap.playlists(JSONObject(api("GET", url)))
            playlists = if (more) playlists + page.items else page.items
            playlistsNext = page.next
            if (playlists.isEmpty()) say("No playlists in your Spotify library yet.")
        }
    }

    fun search(q: String) = scope.launch {
        val text = q.trim()
        if (text.isEmpty()) { results = emptyList(); return@launch }
        if (!connected) { say("Connect Spotify first.", Fix.CONNECT); return@launch }
        runBusy {
            results = SpotifyMap.searchPlaylists(JSONObject(api("GET", "$API/search?type=playlist&limit=20&q=${Http.enc(text.take(100))}"))).items
            if (results.isEmpty()) say("No playlists found for \"${text.take(40)}\".")
        }
    }

    /** First page of playlists for the phone, from [offset] (names and URIs only, "spotify_list"). */
    suspend fun playlistsFor(offset: Int): SpotifyMap.Page =
        SpotifyMap.playlists(JSONObject(api("GET", "$API/me/playlists?limit=50&offset=$offset")))

    // ------------------------------------------------------------------ playback

    /** From the screen: play [ref] on this TV. */
    fun play(ref: SpotifyUri.Ref) = scope.launch { runBusy { say(playNow(ref)) } }

    /** From the screen: pause, resume, next or previous. */
    fun control(action: String) = scope.launch { runBusy { controlNow(action).takeIf { it.isNotEmpty() }?.let { say(it) } } }

    /**
     * Plays [ref] through the Spotify app on this TV. Returns the plain answer ("Playing on the TV.") or throws.
     * Used by the screen and by the relay command "spotify_play".
     */
    suspend fun playNow(ref: SpotifyUri.Ref): String {
        refreshRoute()
        when (route) {
            SpotifyRoute.Kind.NONE -> {
                say("Spotify is not on this TV. Install it, sign in, then come back.", Fix.GET_APP)
                return message
            }
            SpotifyRoute.Kind.APP_REMOTE -> {
                remoteCall { it.playerApi.play(ref.uri) }
            }
            SpotifyRoute.Kind.CONNECT -> {
                val d = connectDevice() ?: return message
                val body = if (ref.isContext) JSONObject().put("context_uri", ref.uri) else JSONObject().put("uris", JSONArray().put(ref.uri))
                api("PUT", "$API/me/player/play?device_id=${Http.enc(d.id)}", body.toString())
            }
        }
        checkSoon()
        return "Playing on ${HouseSync.deviceName()}."
    }

    /** Pause, resume, next or previous. Returns the plain answer or throws. */
    suspend fun controlNow(action: String): String {
        refreshRoute()
        when (route) {
            SpotifyRoute.Kind.NONE -> {
                say("Spotify is not on this TV. Install it, sign in, then come back.", Fix.GET_APP)
                return message
            }
            SpotifyRoute.Kind.APP_REMOTE -> remoteCall {
                when (action) {
                    "pause" -> it.playerApi.pause()
                    "resume" -> it.playerApi.resume()
                    "next" -> it.playerApi.skipNext()
                    else -> it.playerApi.skipPrevious()
                }
            }
            SpotifyRoute.Kind.CONNECT -> {
                val dev = deviceId.ifEmpty { connectDevice()?.id ?: return message }
                val q = "?device_id=${Http.enc(dev)}"
                when (action) {
                    "pause" -> api("PUT", "$API/me/player/pause$q", "")
                    "resume" -> api("PUT", "$API/me/player/play$q", "")
                    "next" -> api("POST", "$API/me/player/next$q", "")
                    else -> api("POST", "$API/me/player/previous$q", "")
                }
            }
        }
        checkSoon()
        return ""
    }

    /** This TV's Spotify Connect device, or null with [message] and [fix] set. */
    private suspend fun connectDevice(): SpotifyMap.Device? {
        if (!connected) { say("Connect Spotify first.", Fix.CONNECT); return null }
        val list = SpotifyMap.devices(JSONObject(api("GET", "$API/me/player/devices")))
        val hints = listOf(Prefs.tvLabel, android.os.Build.MODEL.orEmpty(), HouseSync.platform())
        val d = SpotifyRoute.pickDevice(list, Prefs.spotifyDevice, hints)
        if (d != null) { deviceId = d.id; devices = emptyList(); return d }
        val usable = list.filter { it.id.isNotBlank() && !it.restricted }
        if (usable.size > 1) {
            devices = usable
            say("Spotify sees more than one device. Pick this TV below.", Fix.PICK_DEVICE)
        } else {
            say("Spotify on this TV is not ready. Open Spotify on this TV and sign in, then come back to Jarvis.", Fix.OPEN_APP)
        }
        return null
    }

    /** Reads what plays now (Connect route), once, a moment after a command. */
    private fun checkSoon() {
        if (route != SpotifyRoute.Kind.CONNECT) return
        scope.launch { delay(1_500); runCatching { refreshNow() } }
    }

    /** Connect route: what Spotify plays on this TV (GET /me/player). Called by the screen every [POLL_MS] at most. */
    suspend fun refreshNow() {
        if (route != SpotifyRoute.Kind.CONNECT || !connected) return
        val body = api("GET", "$API/me/player")
        val json = if (body.isBlank()) null else JSONObject(body)
        showNow(SpotifyMap.player(json, System.currentTimeMillis(), onDevice = deviceId.ifEmpty { null }))
    }

    private fun showNow(s: MusicStatus.State?) {
        now = s
        MusicStatus.set(s)
    }

    // ------------------------------------------------------------------ App Remote

    private suspend fun <T> remoteCall(block: (SpotifyAppRemote) -> CallResult<T>) {
        val r = connectRemote()
        withContext(Dispatchers.IO) {
            val res = block(r).await(10, TimeUnit.SECONDS)
            if (!res.isSuccessful) throw res.error ?: IllegalStateException("Spotify did not answer.")
        }
    }

    private suspend fun connectRemote(): SpotifyAppRemote {
        remote?.takeIf { it.isConnected }?.let { return it }
        val id = clientId()
        if (id.isEmpty()) throw IllegalStateException("Add your Spotify Client ID on the Control page first.")
        val params = ConnectionParams.Builder(id).setRedirectUri(SpotifyConfig.REDIRECT_URI).showAuthView(true).build()
        val ctx: Context = activity?.get() ?: app
        return withTimeout(30_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    SpotifyAppRemote.connect(ctx, params, object : Connector.ConnectionListener {
                        override fun onConnected(r: SpotifyAppRemote) {
                            remote = r
                            r.playerApi.subscribeToPlayerState().setEventCallback { s -> showNow(stateOf(s)) }
                            if (cont.isActive) cont.resume(r)
                        }

                        override fun onFailure(t: Throwable) {
                            remote = null
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    })
                }
            }
        }
    }

    private fun stateOf(s: PlayerState?): MusicStatus.State? {
        val t = s?.track ?: return null
        val name = t.name.orEmpty().trim()
        if (name.isEmpty()) return null
        return MusicStatus.State(track = name, artist = t.artist?.name.orEmpty(), playing = !s.isPaused, uri = t.uri.orEmpty(), ts = System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ Web API

    private suspend fun api(method: String, url: String, body: String? = null): String {
        val t = validTokens() ?: throw IllegalStateException("Connect Spotify first.")
        return try {
            send(method, url, body, t)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            val again = tokenLock.withLock { tokens?.let { refreshLocked(it) } } ?: throw IllegalStateException("Connect Spotify again.")
            send(method, url, body, again)
        }
    }

    private suspend fun send(method: String, url: String, body: String?, t: SpotifyTokens): String {
        val h = mapOf("Authorization" to "Bearer ${t.access}")
        return when (method) {
            "GET" -> Http.get(url, h, timeoutMs = 15_000)
            "PUT" -> Http.putJson(url, body.orEmpty(), h)
            else -> Http.postJson(url, body.orEmpty(), h, timeoutMs = 15_000)
        }
    }

    private suspend fun validTokens(): SpotifyTokens? = tokenLock.withLock {
        val t = tokens ?: return@withLock null
        val now = System.currentTimeMillis()
        when {
            t.needsRefresh(now) -> refreshLocked(t)
            t.refresh.isEmpty() && now >= t.expiresAt -> { expireLocked(); null }
            else -> t
        }
    }

    /** New access token with the refresh token (PKCE: client_id, no secret). Spotify may rotate the refresh token. */
    private suspend fun refreshLocked(t: SpotifyTokens): SpotifyTokens? {
        if (t.refresh.isEmpty()) { expireLocked(); return null }
        val resp = try {
            Http.postForm(TOKEN_URL, mapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh, "client_id" to clientId()))
        } catch (e: HttpException) {
            if (e.code == 400 || e.code == 401) { expireLocked(); return null }
            throw e
        }
        val n = t.refreshed(JSONObject(resp), System.currentTimeMillis()) ?: run { expireLocked(); return null }
        tokens = n
        SpotifyVault.save(app, n)
        return n
    }

    private fun expireLocked() {
        SpotifyVault.clear(app)
        tokens = null
        connected = false
        say("Spotify signed Jarvis out. Connect Spotify again.", Fix.CONNECT)
    }

    // ------------------------------------------------------------------ errors

    private suspend fun runBusy(block: suspend () -> Unit) {
        busy = true
        try {
            block()
        } catch (e: Throwable) {
            explain(e)
        } finally {
            busy = false
        }
    }

    /** One plain sentence (and maybe a button) for any failure. Secrets are always hidden. */
    fun explain(e: Throwable): String {
        when {
            e is CouldNotFindSpotifyApp -> say("Spotify is not on this TV. Install it, sign in, then come back.", Fix.GET_APP)
            e is NotLoggedInException -> say("Spotify on this TV is not signed in. Open Spotify, sign in, then come back.", Fix.OPEN_APP)
            e is UserNotAuthorizedException -> say("Spotify needs your OK for Jarvis. Press Connect Spotify.", Fix.CONNECT)
            e is HttpException -> {
                val m = e.message.orEmpty()
                when {
                    e.code == 403 && m.contains("PREMIUM", true) -> say("Spotify Premium is needed to pick what plays.")
                    e.code == 403 -> say("Spotify refused. Check that this Spotify account is added to your developer app.")
                    e.code == 404 -> say("Spotify on this TV is not ready. Open Spotify on this TV, then come back to Jarvis.", Fix.OPEN_APP)
                    e.code == 429 -> say("Spotify is busy. Try again in a minute.")
                    else -> say("Spotify did not answer (error ${e.code}). Try again.")
                }
            }
            e is java.io.IOException -> say("No connection to Spotify. Check the internet and try again.")
            e is kotlinx.coroutines.TimeoutCancellationException -> say("Spotify did not answer. Open Spotify on this TV once, then try again.", Fix.OPEN_APP)
            else -> say(e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong with Spotify. Try again.")
        }
        return message
    }
}
