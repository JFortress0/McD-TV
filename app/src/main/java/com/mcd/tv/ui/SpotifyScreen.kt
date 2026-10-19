package com.mcd.tv.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Spotify
import com.mcd.tv.data.SpotifyConfig
import com.mcd.tv.data.SpotifyMap
import com.mcd.tv.data.SpotifyRoute
import com.mcd.tv.data.SpotifyUri
import kotlinx.coroutines.delay

/**
 * Spotify (phase 1, Dad profile). Connect once, pick a playlist, and the Spotify app on this TV plays it in the
 * background while Jarvis stays on screen. Pause, resume, next and previous work here and from the phone.
 */
@Composable
fun SpotifyScreen(nav: Nav) {
    val ctx = LocalContext.current
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Spotify.onSignInResult(it.resultCode, it.data)
    }
    // Back from the app store or the Spotify app: look again for Spotify on this TV.
    @Suppress("DEPRECATION") // same LocalLifecycleOwner as UpdateScreen (always on the classpath with this BOM)
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) Spotify.refreshRoute() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        Spotify.clearMessage()
        Spotify.refreshRoute()
        if (Spotify.connected && Spotify.playlists.isEmpty()) Spotify.loadPlaylists()
    }
    // Spotify Connect has no live events: read what plays every 15 s, only while this screen is open.
    LaunchedEffect(Spotify.route, Spotify.connected) {
        while (Spotify.route == SpotifyRoute.Kind.CONNECT && Spotify.connected) {
            runCatching { Spotify.refreshNow() }
            delay(Spotify.POLL_MS)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }

    fun connect() {
        val a = ctx as? Activity ?: return
        Spotify.signInIntent(a)?.let { signIn.launch(it) }
    }

    TabPage(nav, NavTab.Browse) {
        if (!SpotifyConfig.enabledFor(Prefs.activeProfile)) {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                StatusText("Spotify is on the ${Prefs.profileName("p1")} profile for now.")
            }
        } else LazyColumn(
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                HudPanel(Modifier.fillMaxWidth()) {
                    RailHeader("Spotify")
                    Text(statusLine(), color = McdColors.White, fontSize = 16.sp)
                    if (Spotify.message.isNotEmpty()) Text(Spotify.message, color = McdColors.AccentBright, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (Spotify.fix) {
                            Spotify.Fix.GET_APP -> ActionButton(Spotify.Fix.GET_APP.button, { Spotify.openStore(ctx) }, primary = true)
                            Spotify.Fix.OPEN_APP -> ActionButton(Spotify.Fix.OPEN_APP.button, {
                                if (!Spotify.openSpotifyApp(ctx)) Spotify.openStore(ctx)
                            }, primary = true)
                            Spotify.Fix.CONNECT -> ActionButton(Spotify.Fix.CONNECT.button, { connect() }, primary = true)
                            else -> {}
                        }
                        if (!Spotify.connected && Spotify.fix != Spotify.Fix.CONNECT) ActionButton("Connect Spotify", { connect() }, primary = Spotify.fix == null)
                        if (Spotify.connected) {
                            ActionButton("Refresh playlists", { Spotify.loadPlaylists() })
                            ActionButton("Disconnect", { Spotify.signOut() })
                        }
                    }
                }
            }
            item(key = "now") { NowPlayingPanel() }
            if (Spotify.devices.isNotEmpty()) {
                item(key = "devices-h") { RailHeader("Which device is this TV?") }
                items(Spotify.devices, key = { "dev-" + it.id }) { d ->
                    MusicRow(d.name, d.type + if (d.active) "  ·  playing now" else "") { Spotify.pickDevice(d) }
                }
            }
            if (Spotify.connected) {
                item(key = "search") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it.take(100) },
                            singleLine = true,
                            textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
                            cursorBrush = SolidColor(McdColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
                            keyboardActions = KeyboardActions(onSearch = { searchOrPlay(query) }),
                            modifier = Modifier.width(420.dp).background(McdColors.Card, HudShape)
                                .border(1.dp, McdColors.Accent.copy(alpha = 0.7f), HudShape).padding(12.dp),
                        )
                        ActionButton("Search playlists", { searchOrPlay(query) })
                    }
                }
                if (Spotify.results.isNotEmpty()) {
                    item(key = "results-h") { RailHeader("Search results") }
                    items(Spotify.results, key = { "r-" + it.id }) { p -> PlaylistRow(p) }
                }
                item(key = "mine-h") { RailHeader("Your playlists") }
                if (Spotify.playlists.isEmpty()) {
                    item(key = "mine-empty") { StatusText(if (Spotify.busy) "Loading playlists…" else "No playlists yet.") }
                }
                items(Spotify.playlists, key = { "p-" + it.id }) { p -> PlaylistRow(p) }
                if (Spotify.hasMore) item(key = "more") { ActionButton("More playlists", { Spotify.loadPlaylists(more = true) }) }
            }
        }
    }
}

/** A Spotify link or URI typed or pasted plays at once; anything else searches playlists. */
private fun searchOrPlay(q: String) {
    val ref = SpotifyUri.parse(q)
    if (ref != null) Spotify.play(ref) else Spotify.search(q)
}

private fun statusLine(): String {
    val clientId = Spotify.clientId().isNotEmpty()
    val app = when (Spotify.route) {
        SpotifyRoute.Kind.APP_REMOTE -> "Spotify app found on this TV."
        SpotifyRoute.Kind.CONNECT -> "Spotify TV app found on this TV."
        SpotifyRoute.Kind.NONE -> "Spotify is not on this TV yet."
    }
    return when {
        !clientId -> "Add your Spotify Client ID on the Control page first. $app"
        Spotify.connected -> "Connected. $app Pick a playlist and it plays here while Jarvis stays open."
        else -> "Not connected. $app Press Connect Spotify and sign in once."
    }
}

@Composable
private fun NowPlayingPanel() {
    val n = Spotify.now
    HudPanel(Modifier.fillMaxWidth()) {
        RailHeader("Now playing")
        Text(
            if (n == null) "Nothing playing on this TV." else (if (n.playing) "Playing: " else "Paused: ") + n.track + (if (n.artist.isNotEmpty()) " by " + n.artist else ""),
            color = McdColors.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Previous", { Spotify.control("previous") })
            if (n?.playing == true) ActionButton("Pause", { Spotify.control("pause") }, primary = true)
            else ActionButton("Resume", { Spotify.control("resume") }, primary = true)
            ActionButton("Next", { Spotify.control("next") })
        }
    }
}

@Composable
private fun PlaylistRow(p: SpotifyMap.Playlist) {
    val sub = listOfNotNull(
        p.owner.takeIf { it.isNotEmpty() }?.let { "by $it" },
        p.tracks.takeIf { it > 0 }?.let { "$it songs" },
    ).joinToString("  ·  ")
    MusicRow(p.name, sub) { SpotifyUri.parse(p.uri)?.let { Spotify.play(it) } }
}

@Composable
private fun MusicRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}
