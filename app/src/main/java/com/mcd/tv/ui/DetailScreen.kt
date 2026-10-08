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
import androidx.compose.foundation.layout.heightIn
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
fun DetailScreen(nav: Nav, type: String, id: Int, openSources: Boolean = false) {
    var retry by remember { mutableIntStateOf(0) }
    val load by rememberLoad(type, id, retry) { Tmdb.details(type, id) }
    Box(Modifier.fillMaxSize().background(McdColors.Navy)) {
        when (val l = load) {
            is Load.Loading -> StatusText("Loading…", Modifier.padding(48.dp))
            is Load.Err -> Column(Modifier.padding(48.dp)) {
                StatusText(l.message)
                ActionButton("Retry", { retry++ }, primary = true)
            }
            is Load.Ok -> {
                val d = l.value
                // From a service catalog: replace this page with the source list right away.
                LaunchedEffect(d.title.id) {
                    val imdb = d.imdbId
                    if (openSources && imdb != null) {
                        nav.replace(Screen.Sources(PlayMeta(d.title.type, d.title.id, d.title.name, d.title.poster, d.title.backdrop), imdb, autoPlay = false))
                    }
                }
                DetailBody(nav, d)
            }
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
            Box(Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
                AsyncImage(
                    Tmdb.img(t.backdrop, "w1280"), t.name, contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter, modifier = Modifier.matchParentSize(),
                )
                // Full-bleed backdrop melting into the page on the left and at the bottom (Max-style).
                Box(
                    Modifier.matchParentSize().background(
                        Brush.horizontalGradient(
                            0f to McdColors.Navy,
                            0.35f to McdColors.Navy.copy(alpha = 0.88f),
                            0.65f to McdColors.Navy.copy(alpha = 0.35f),
                            1f to Color.Transparent,
                        ),
                    ),
                )
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to McdColors.Navy.copy(alpha = 0.45f),
                            0.20f to Color.Transparent,
                            0.65f to Color.Transparent,
                            0.88f to McdColors.Navy.copy(alpha = 0.85f),
                            1f to McdColors.Navy,
                        ),
                    ),
                )
                Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        Tmdb.img(t.poster, "w342"), t.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(160.dp).height(240.dp).clip(RoundedCornerShape(6.dp)).background(McdColors.Card),
                    )
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = broadcastStyle(30.sp).copy(lineHeight = 34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (d.tagline.isNotBlank()) Text(d.tagline, color = McdColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                t.year.ifBlank { null },
                                if (d.runtimeMin > 0) "${d.runtimeMin / 60}h ${d.runtimeMin % 60}m" else null,
                                d.genres.take(3).joinToString(" / ").ifBlank { null },
                            ).joinToString("   •   "),
                            color = McdColors.Muted, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        RatingsRow(t.rating, d.imdbId)
                        Text(t.overview, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
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
                            d.trailerKey?.let { key ->
                                ActionButton("▶ Trailer", { if (!openYouTube(context, key)) note = "No YouTube app on this TV" })
                            }
                            if (t.type == "movie") ActionButton("Mark as Watched", { Library.markWatched(meta()); note = "Marked as watched" })
                            if (t.type == "tv") ActionButton(if (noise) "✓ In Background Noise" else "+ Background Noise", { noise = Library.toggleNoise(t) })
                            ActionButton("Not for me", { Library.hide(t); note = "Hidden from home rows"; nav.back() })
                        }
                        if (d.providers.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            // LazyRow: long provider names scroll instead of running off the screen.
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                item { Text("ALSO ON", style = broadcastStyle(12.sp, McdColors.Muted).copy(letterSpacing = 1.2.sp)) }
                                items(d.providers.take(4)) { svc ->
                                    ActionButton("Open ${svc.name}", { if (!openApp(context, svc.packages)) note = "${svc.name} app is not installed on this TV" })
                                }
                            }
                        }
                        if (note.isNotBlank()) Text(note, color = McdColors.RedBright, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
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
                    items(d.cast) { c -> CastBubble(c.name, c.character, c.photo) { if (c.id > 0) nav.push(Screen.Person(c.id)) } }
                }
            }
        }
        item { TitleRow(if (t.type == "tv") "Similar Shows" else "Similar Movies", d.similar) { s -> nav.push(Screen.Detail(s.type, s.id)) } }
        val collectionId = d.collectionId
        if (t.type == "movie" && collectionId != null) item { CollectionRow(nav, collectionId, d.collectionName) }
    }
}

@Composable
private fun EpisodeRow(tvId: Int, season: Int, onPlay: (Episode) -> Unit) {
    val eps by rememberLoad(tvId, season) { Tmdb.season(tvId, season) }
    val watched = remember(tvId, season) { Library.seasonProgress(tvId, season) }
    when (val l = eps) {
        is Load.Loading -> StatusText("Loading episodes…", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(l.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(l.value, key = { it.number }) { e ->
                val p = watched[e.number]
                val done = p != null && p >= 0.9f
                WideCard(
                    title = "${e.number}. ${e.name}",
                    subtitle = if (done) "✓ Watched   ${e.airDate}" else e.airDate,
                    image = Tmdb.img(e.still, "w500"),
                    onClick = { onPlay(e) },
                    progress = if (p != null && !done && p > 0.01f) p else null,
                )
            }
        }
    }
}

/** Movie franchise row (TMDB collection), every entry, oldest first. */
@Composable
private fun CollectionRow(nav: Nav, collectionId: Int, fallbackName: String?) {
    val c by rememberLoad(collectionId) { Tmdb.collection(collectionId) }
    val col = (c as? Load.Ok<com.mcd.tv.data.TitleCollection>)?.value ?: return
    if (col.parts.size < 2) return
    TitleRow(col.name.ifBlank { fallbackName ?: "Collection" }, col.parts) { s -> nav.push(Screen.Detail(s.type, s.id)) }
}

/**
 * Plays a YouTube video: the TV YouTube app first (Fire TV, then Android TV), then any app
 * that opens the link. Returns false when nothing on this TV can open it.
 */
fun openYouTube(context: android.content.Context, key: String): Boolean {
    val web = android.net.Uri.parse("https://www.youtube.com/watch?v=$key")
    val app = android.net.Uri.parse("vnd.youtube:$key")
    fun tryStart(uri: android.net.Uri, pkg: String?): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    for (pkg in listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")) {
        if (tryStart(web, pkg) || tryStart(app, pkg)) return true
    }
    return tryStart(web, null)
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

/** TMDB rating plus IMDb and Rotten Tomatoes critics and audience scores (when MDBList is set up). */
@Composable
private fun RatingsRow(tmdb: Double, imdbId: String?) {
    val r by rememberLoad(imdbId) { if (imdbId != null) com.mcd.tv.data.RatingsSource.forImdb(imdbId) else com.mcd.tv.data.Ratings() }
    val ratings = (r as? Load.Ok<com.mcd.tv.data.Ratings>)?.value ?: com.mcd.tv.data.Ratings()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (tmdb > 0) ScoreChip("TMDB", "★ %.1f".format(tmdb), Color(0xFF0D253F))
        ratings.imdb?.let { ScoreChip("IMDb", "%.1f".format(it), Color(0xFF8A6D00)) }
        ratings.rtCritics?.let { ScoreChip("🍅 Critics", "$it%", if (it >= 60) Color(0xFFB3261E) else Color(0xFF3D6B1F)) }
        ratings.rtAudience?.let { ScoreChip("🍿 Audience", "$it%", if (it >= 60) Color(0xFFB3261E) else Color(0xFF3D6B1F)) }
        ratings.metacritic?.let { ScoreChip("Metacritic", "$it", Color(0xFF2E3A46)) }
    }
}

@Composable
private fun ScoreChip(label: String, value: String, color: Color) {
    Row(
        Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
