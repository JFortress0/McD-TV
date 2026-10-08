package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Details
import com.mcd.tv.data.Episode
import com.mcd.tv.data.Library
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Tmdb

/** Title page: backdrop, poster, actions, seasons/episodes (TV), cast, similar. */
@Composable
fun DetailScreen(nav: Nav, type: String, id: Int) {
    val load by rememberLoad(type, id) { Tmdb.details(type, id) }
    Box(Modifier.fillMaxSize().background(McdColors.Navy)) {
        when (val l = load) {
            is Load.Loading -> StatusText("Loading…", Modifier.padding(48.dp))
            is Load.Err -> StatusText(l.message, Modifier.padding(48.dp))
            is Load.Ok -> DetailBody(nav, l.value)
        }
    }
}

@Composable
private fun DetailBody(nav: Nav, d: Details) {
    val t = d.title
    var fav by remember { mutableStateOf(Library.isFavorite(t)) }
    var listed by remember { mutableStateOf(Library.inWatchlist(t)) }
    var noise by remember { mutableStateOf(Library.inNoise(t)) }
    var note by remember { mutableStateOf("") }
    val playFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // TV: resume the last episode watched, else S1E1.
    val last = remember { Library.history().firstOrNull { it.meta.historyKey == "tv:${t.id}" } }
    var season by remember { mutableIntStateOf(last?.meta?.season?.takeIf { it > 0 } ?: d.seasons.firstOrNull()?.number ?: 1) }

    fun meta(s: Int = 0, e: Int = 0) = PlayMeta(t.type, t.id, t.name, t.poster, t.backdrop, s, e)

    fun play(s: Int, e: Int, auto: Boolean) {
        val imdb = d.imdbId
        if (imdb == null) { note = "No IMDb id for this title, so addons cannot find sources."; return }
        nav.push(Screen.Sources(meta(s, e), imdb, auto))
    }

    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { playFocus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(470.dp)) {
                AsyncImage(Tmdb.img(t.backdrop, "w1280"), t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(McdColors.Navy, McdColors.Navy.copy(alpha = 0.75f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, McdColors.Navy))))
                Row(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
                    AsyncImage(
                        Tmdb.img(t.poster, "w342"), t.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(190.dp).height(285.dp).clip(RoundedCornerShape(8.dp)).background(McdColors.Card),
                    )
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.width(720.dp)) {
                        Text(t.name.uppercase(), style = broadcastStyle(40.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.tagline.isNotBlank()) Text(d.tagline, color = McdColors.Muted, fontSize = 15.sp)
                        Text(
                            listOfNotNull(
                                if (t.rating > 0) "★ %.1f".format(t.rating) else null,
                                t.year.ifBlank { null },
                                if (d.runtimeMin > 0) "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m" else null,
                                d.genres.take(3).joinToString(" / ").ifBlank { null },
                            ).joinToString("   •   "),
                            color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Text(t.overview, color = Color.White, fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val resumeLabel = if (t.type == "tv" && last != null) "▶  Resume S${last.meta.season}E${last.meta.episode}" else "▶  Play"
                            ActionButton(resumeLabel, {
                                if (t.type == "movie") play(0, 0, true)
                                else play(last?.meta?.season ?: season, last?.meta?.episode ?: 1, true)
                            }, Modifier.focusRequester(playFocus), primary = true)
                            if (t.type == "movie") ActionButton("Choose Source", { play(0, 0, false) })
                            ActionButton(if (fav) "♥ Favorite" else "♡ Favorite", { fav = Library.toggleFavorite(t) })
                            ActionButton(if (listed) "✓ Watchlist" else "+ Watchlist", { listed = Library.toggleWatchlist(t) })
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (t.type == "movie") ActionButton("Mark as Watched", { Library.markWatched(meta()); note = "Marked as watched" })
                            if (t.type == "tv") ActionButton(if (noise) "✓ In Background Noise" else "+ Background Noise", { noise = Library.toggleNoise(t) })
                            ActionButton("Not for me", { Library.hide(t); note = "Hidden from home rows"; nav.back() })
                        }
                        if (d.providers.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("ALSO ON", style = broadcastStyle(13.sp, McdColors.Muted))
                                d.providers.take(4).forEach { svc ->
                                    ActionButton("Open ${svc.name}", { if (!openApp(context, svc.packages)) note = "${svc.name} app is not installed on this TV" })
                                }
                            }
                        }
                        if (note.isNotBlank()) Text(note, color = McdColors.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        if (t.type == "tv" && d.seasons.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    RailHeader("Episodes", Modifier.padding(start = 48.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.seasons, key = { it.number }) { s -> ActionButton(s.name, { season = s.number }, primary = s.number == season) }
                    }
                }
            }
            item { EpisodeRow(t.id, season, onPlay = { e -> play(e.season, e.number, false) }) }
        }

        if (d.cast.isNotEmpty()) item {
            Column(Modifier.padding(top = 18.dp)) {
                RailHeader("Cast", Modifier.padding(start = 48.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.cast) { c -> CastBubble(c.name, c.character, c.photo) }
                }
            }
        }
        item { TitleRow(if (t.type == "tv") "Similar Shows" else "Similar Movies", d.similar) { s -> nav.push(Screen.Detail(s.type, s.id)) } }
    }
}

@Composable
private fun EpisodeRow(tvId: Int, season: Int, onPlay: (Episode) -> Unit) {
    val eps by rememberLoad(tvId, season) { Tmdb.season(tvId, season) }
    when (val l = eps) {
        is Load.Loading -> StatusText("Loading episodes…", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(l.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(l.value, key = { it.number }) { e ->
                WideCard(
                    title = "${e.number}. ${e.name}",
                    subtitle = e.airDate,
                    image = Tmdb.img(e.still, "w500"),
                    onClick = { onPlay(e) },
                )
            }
        }
    }
}

/** Opens another app (Netflix, Hulu…) on this TV. Returns false if none of the packages is installed. */
fun openApp(context: android.content.Context, packages: List<String>): Boolean {
    val pm = context.packageManager
    for (p in packages) {
        val intent = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: continue
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
    return false
}
