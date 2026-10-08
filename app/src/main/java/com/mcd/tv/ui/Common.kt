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

/** Portrait poster with rating badge. Focus: white ring, slight lift and a soft blue glow. */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 140.dp) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).height(width * 1.5f),
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
            if (t.rating > 0) {
                Text(
                    text = "★ %.1f".format(t.rating),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
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
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            color = if (focused) Color.White else McdColors.Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = McdColors.Muted.copy(alpha = if (focused) 1f else 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A titled horizontal row of posters. */
@Composable
fun TitleRow(label: String, items: List<Title>, onOpen: (Title) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 20.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { "${it.type}-${it.id}" }) { t -> PosterCard(t, onClick = { onOpen(t) }) }
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
enum class NavTab(val label: String) { Home("Home"), Search("Search"), Library("My List"), Genres("Genres"), Sports("Sports"), Live("Live TV"), Services("Services"), Noise("Background Noise"), Settings("Settings") }

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
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        McdLogo(scale = 0.7f)
        Spacer(Modifier.width(24.dp))
        NavTab.entries.filter { it != NavTab.Settings }.forEach { tab ->
            NavItem(tab.label, tab == current, { onSelect(tab) }, if (tab == current) Modifier.focusRequester(currentFocus) else Modifier)
        }
        Spacer(Modifier.weight(1f))
        ProfileCircle(current == NavTab.Settings, { onSelect(NavTab.Settings) }, if (current == NavTab.Settings) Modifier.focusRequester(currentFocus) else Modifier)
    }
}
