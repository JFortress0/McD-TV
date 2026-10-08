package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mcd.tv.data.Library
import com.mcd.tv.data.Ratings
import com.mcd.tv.data.RatingsSource
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.CancellationException

// ---------------- Async loading ----------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Runs [block] off the main thread when the screen opens (or a key changes). */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = Load.Loading // a key change (e.g. Retry) shows Loading again instead of the old result
        value = try {
            Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: e.toString())
        }
    }

@Composable
fun StatusText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = McdColors.Muted, fontSize = 16.sp, modifier = modifier.padding(vertical = 12.dp))
}

// ---------------- Cards ----------------

private val CardShape = RoundedCornerShape(6.dp)
private val cardShape @Composable get() = CardDefaults.shape(shape = CardShape)
private val cardBorder @Composable get() = CardDefaults.border(
    focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = CardShape),
)
private val cardColors @Composable get() = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight)
private val cardGlow @Composable get() = CardDefaults.glow(
    focusedGlow = Glow(elevationColor = McdColors.RedBright.copy(alpha = 0.45f), elevation = 10.dp),
)

private val ImdbYellow = Color(0xFFF5C518)
private val TmdbTeal = Color(0xFF01B4E4)

/** True if [date] ("yyyy-MM-dd") is between [days] days ago and today. */
private fun releasedWithinDays(date: String, days: Int): Boolean {
    if (date.length < 10) return false
    val d = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date.take(10)) }.getOrNull() ?: return false
    val age = System.currentTimeMillis() - d.time
    return age >= 0 && age <= days * 86_400_000L
}

/**
 * Portrait poster. Scores sit in a small dark pill along the bottom (TMDB always; IMDb and Rotten Tomatoes
 * when an MDBList key is set, loaded lazily per visible card). "IN CINEMA" on movies released in the last
 * 45 days, a check mark on watched movies. The title shows only while focused.
 * Focus: white ring, slight lift and a soft blue glow. [badges] = false hides all of that (e.g. collection tiles).
 */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 140.dp, badges: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val ratings by produceState<Ratings?>(if (badges) RatingsSource.cachedTmdb(t.type, t.id) else null, t.type, t.id, badges) {
        if (badges && value == null && RatingsSource.configured) value = RatingsSource.forTmdb(t.type, t.id)
    }
    val watched = remember(t.type, t.id, badges) { badges && Library.isWatched(t.type, t.id) }
    val inCinema = remember(t.type, t.releaseDate, badges) { badges && t.type == "movie" && releasedWithinDays(t.releaseDate, 45) }
    Card(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.width(width).height(width * 1.5f),
        shape = cardShape,
        colors = cardColors,
        border = cardBorder,
        glow = cardGlow,
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Tmdb.img(t.poster),
                contentDescription = t.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (t.poster == null) {
                Text(t.name, style = broadcastStyle(15.sp), modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (inCinema) {
                Text(
                    "IN CINEMA",
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(McdColors.Red, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(McdColors.Red),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            val r = ratings
            val imdb = if (badges) r?.imdb else null
            val rt = if (badges) r?.rtCritics else null
            val scores = buildAnnotatedString {
                var first = true
                fun sep() {
                    if (!first) withStyle(SpanStyle(color = Color.White.copy(alpha = 0.5f))) { append(" · ") }
                    first = false
                }
                if (imdb != null) {
                    sep()
                    withStyle(SpanStyle(color = ImdbYellow, fontWeight = FontWeight.Bold)) { append("IMDb") }
                    append(" %.1f".format(imdb))
                }
                if (rt != null) {
                    sep()
                    append("🍅 $rt%")
                }
                if (badges && imdb == null && t.rating > 0) {
                    sep()
                    withStyle(SpanStyle(color = TmdbTeal, fontWeight = FontWeight.Bold)) { append("TMDB") }
                    append(" %.1f".format(t.rating))
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .then(
                        if (focused) Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        else Modifier,
                    )
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp, top = if (focused) 18.dp else 0.dp),
            ) {
                if (focused) {
                    Text(
                        t.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (scores.isNotEmpty()) {
                    Text(
                        scores,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** Poster-sized "See all" tile at the end of a row: opens the full grid for that row. */
@Composable
fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 210.dp) {
    Card(
        onClick = onClick,
        modifier = modifier.width(100.dp).height(height),
        shape = cardShape,
        colors = cardColors,
        border = cardBorder,
        glow = cardGlow,
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("See all", style = broadcastStyle(15.sp))
                Text("›", color = McdColors.RedBright, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Landscape card with optional progress bar (Continue Watching). Title and subtitle sit under the image. */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(280.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused }.width(280.dp).height(158.dp),
            shape = cardShape,
            colors = cardColors,
            border = cardBorder,
            glow = cardGlow,
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress != null) {
                    Box(
                        Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomStart)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
                    )
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                            .height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(McdColors.Red))
                    }
                }
            }
        }
        // Title and subtitle only while focused (the space stays reserved so the row never jumps).
        Box(Modifier.padding(top = 8.dp).height(36.dp)) {
            if (focused) {
                Column {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A titled horizontal row of posters. */
@Composable
fun TitleRow(label: String, items: List<Title>, onOpen: (Title) -> Unit) = TitleRail(label, items, null, onOpen)

/** A titled horizontal row of posters; [onSeeAll] adds a "See all" tile at the end that opens the full grid. */
@Composable
fun TitleRail(label: String, items: List<Title>, onSeeAll: (() -> Unit)?, onOpen: (Title) -> Unit) {
    if (items.isEmpty()) return
    val unique = remember(items) { items.distinctBy { "${it.type}-${it.id}" } }
    Column(Modifier.padding(top = 20.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(unique, key = { "${it.type}-${it.id}" }) { t -> PosterCard(t, onClick = { onOpen(t) }) }
            if (onSeeAll != null) item(key = "see-all") { SeeAllCard(onSeeAll) }
        }
    }
}

/**
 * Pill button (about 40dp tall).
 * Primary: white with near-black text; focused adds a bright blue ring and a soft glow.
 * Secondary: translucent white with white text; focused turns solid white with black text.
 * Focus also scales it up slightly.
 */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val solid = primary || focused
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
            .drawBehind {
                if (focused) {
                    // Soft blue glow: a few rounded outlines fading outward (works on every Android version).
                    for (i in 1..4) {
                        val g = i * 2.5f
                        drawRoundRect(
                            color = McdColors.RedBright.copy(alpha = 0.10f * (5 - i) / 4f),
                            topLeft = androidx.compose.ui.geometry.Offset(-g, -g),
                            size = androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + g),
                        )
                    }
                }
            }
            .clip(shape)
            .background(if (solid) Color.White else Color.White.copy(alpha = 0.12f))
            .then(if (primary && focused) Modifier.border(2.dp, McdColors.RedBright, shape) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (solid) Color(0xFF05070D) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Round cast photo. Focusable and clickable when [onClick] is given (opens the person's page). */
@Composable
fun CastBubble(name: String, role: String, photo: String?, onClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(104.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val sc = if (focused) 1.06f else 1f; scaleX = sc; scaleY = sc }
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier)
            .padding(4.dp),
    ) {
        AsyncImage(
            model = Tmdb.img(photo, "w185"),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(CircleShape).background(McdColors.Card)
                .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 12.sp, color = if (focused) Color.White else Color.White.copy(alpha = 0.9f), fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, fontSize = 11.sp, color = McdColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top navigation: plain text tabs (current one white with a blue underline) and a profile/settings circle. */
enum class NavTab(val label: String) { Home("Home"), Search("Search"), Browse("Browse"), Library("My List"), Genres("Genres"), Sports("Sports"), Live("Live TV"), Services("Services"), Noise("Background Noise"), Settings("Settings") }

/** Tabs shown in the bar, in order. Genres, Sports, Services and Background Noise live under Browse. */
private val BarTabs = listOf(NavTab.Home, NavTab.Search, NavTab.Browse, NavTab.Live, NavTab.Library)

/** The bar tab to highlight for a page: pages reached from Browse highlight Browse. */
private fun NavTab.barTab(): NavTab = when (this) {
    NavTab.Genres, NavTab.Sports, NavTab.Services, NavTab.Noise -> NavTab.Browse
    else -> this
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected || focused) Color.White else McdColors.Muted,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        Box(
            Modifier.padding(top = 4.dp).width(if (selected) 20.dp else 0.dp).height(2.dp)
                .clip(RoundedCornerShape(1.dp)).background(McdColors.Red),
        )
    }
}

@Composable
private fun ProfileCircle(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val sc = if (focused) 1.08f else 1f; scaleX = sc; scaleY = sc }
            .size(38.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(McdColors.RedBright, McdColors.RedDark)))
            .border(
                2.dp,
                when {
                    focused -> Color.White
                    selected -> McdColors.RedBright.copy(alpha = 0.9f)
                    else -> Color.Transparent
                },
                CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        // Simple person glyph: head and shoulders.
        Canvas(Modifier.fillMaxSize()) {
            val c = Color.White.copy(alpha = 0.92f)
            drawCircle(c, radius = size.minDimension * 0.17f, center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.38f))
            drawArc(
                c, startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.60f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.42f),
            )
        }
    }
}

/** Transparent top bar (it floats over hero backdrops): wordmark, text tabs, profile circle. */
@Composable
fun TopNav(current: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    val currentFocus = remember { FocusRequester() }
    if (autoFocus) {
        // Give the remote somewhere to start: focus the current tab once the page is laid out.
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { currentFocus.requestFocus() } }
    }
    val shown = current.barTab()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        McdLogo(scale = 0.7f)
        Spacer(Modifier.width(24.dp))
        BarTabs.forEach { tab ->
            NavItem(tab.label, tab == shown, { onSelect(tab) }, if (tab == shown) Modifier.focusRequester(currentFocus) else Modifier)
        }
        Spacer(Modifier.weight(1f))
        ProfileCircle(current == NavTab.Settings, { onSelect(NavTab.Settings) }, if (current == NavTab.Settings) Modifier.focusRequester(currentFocus) else Modifier)
    }
}
