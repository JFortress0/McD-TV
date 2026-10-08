package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
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

private val cardBorder @Composable get() = CardDefaults.border(focusedBorder = Border(border = BorderStroke(3.dp, McdColors.Red)))
private val cardColors @Composable get() = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.Card)

/** Portrait poster with rating badge, like HuberTV's rows. */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 140.dp) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).height(width * 1.5f),
        colors = cardColors,
        border = cardBorder,
        scale = CardDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Tmdb.img(t.poster),
                contentDescription = t.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (t.poster == null) {
                Text(t.name, style = broadcastStyle(16.sp), modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (t.rating > 0) {
                Text(
                    text = "★ %.1f".format(t.rating),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Landscape card with optional progress bar (Continue Watching). */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(280.dp).height(158.dp),
        colors = cardColors,
        border = cardBorder,
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(title, style = broadcastStyle(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(McdColors.Red))
                    }
                }
            }
        }
    }
}

/** A titled horizontal row of posters. */
@Composable
fun TitleRow(label: String, items: List<Title>, onOpen: (Title) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
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
 * Pill button. Primary: red gradient with a soft red glow. Secondary: dark with an outline
 * that lights up cyan when focused. Focus also scales it up slightly.
 */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val glowColor = if (primary) McdColors.Red else McdColors.Cyan
    val showGlow = primary || focused
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }
            .drawBehind {
                if (showGlow) {
                    // Soft glow: stacked rounded outlines fading outward (works on every Android version).
                    for (i in 1..7) {
                        val g = i * 2.5f
                        drawRoundRect(
                            color = glowColor.copy(alpha = (if (focused) 0.11f else 0.06f) * (8 - i) / 7f),
                            topLeft = androidx.compose.ui.geometry.Offset(-g, -g),
                            size = androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2 + g),
                        )
                    }
                }
            }
            .clip(shape)
            .background(
                if (primary) Brush.verticalGradient(listOf(if (focused) McdColors.RedBright else Color(0xFFF0303F), McdColors.RedDark))
                else Brush.verticalGradient(listOf(if (focused) Color(0xFF1C2430) else Color(0xFF12171E), Color(0xFF0C1015))),
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    primary && focused -> Color.White
                    primary -> McdColors.RedBright.copy(alpha = 0.7f)
                    focused -> McdColors.Cyan
                    else -> McdColors.Line
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
            .graphicsLayer { val sc = if (focused) 1.1f else 1f; scaleX = sc; scaleY = sc }
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier)
            .padding(4.dp),
    ) {
        AsyncImage(
            model = Tmdb.img(photo, "w185"),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(CircleShape).background(McdColors.Card)
                .border(if (focused) 3.dp else 0.dp, if (focused) McdColors.Red else Color.Transparent, CircleShape),
        )
        Text(name, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, fontSize = 11.sp, color = McdColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top navigation: plain text tabs (current one underlined in red) and a profile/settings circle. */
enum class NavTab(val label: String) { Home("Home"), Search("Search"), Library("My List"), Genres("Genres"), Sports("Sports"), Live("Live TV"), Services("Services"), Noise("Background Noise"), Settings("Settings") }

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected || focused) Color.White else Color(0xFFC3C9D2),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Box(Modifier.padding(top = 4.dp).width(if (selected) 18.dp else 0.dp).height(2.dp).background(McdColors.Red))
    }
}

@Composable
private fun ProfileCircle(selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .border(2.dp, if (focused) McdColors.Cyan else if (selected) McdColors.Red else Color(0xFFC3C9D2), CircleShape)
            .clickable(onClick = onClick),
    ) {
        // Simple person glyph: head and shoulders.
        Canvas(Modifier.fillMaxSize()) {
            val c = Color(0xFFE5E7EB)
            drawCircle(c, radius = size.minDimension * 0.17f, center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.38f))
            drawArc(
                c, startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.60f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.42f),
            )
        }
    }
}

@Composable
fun TopNav(current: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        McdLogo(scale = 0.75f)
        Spacer(Modifier.width(20.dp))
        NavTab.entries.filter { it != NavTab.Settings }.forEach { tab -> NavItem(tab.label, tab == current) { onSelect(tab) } }
        Spacer(Modifier.weight(1f))
        ProfileCircle(current == NavTab.Settings) { onSelect(NavTab.Settings) }
    }
}
