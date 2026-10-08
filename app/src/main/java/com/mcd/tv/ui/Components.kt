package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text

/**
 * A D-pad friendly tile. Focus = grows slightly + white ring and a soft blue glow.
 * [tag] is an optional accent label in the corner (e.g. "PLAY" or "PHASE 3").
 */
@Composable
fun TileCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    dimmed: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(300.dp).height(168.dp),
        shape = CardDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = CardDefaults.colors(
            containerColor = McdColors.Card,
            focusedContainerColor = McdColors.NavyLight,
        ),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp)),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = McdColors.RedBright.copy(alpha = 0.45f), elevation = 10.dp),
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            if (dimmed) McdColors.Card else Color(0xFF1A2335),
                            if (dimmed) McdColors.Navy else Color(0xFF0D1424),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = broadcastStyle(20.sp, if (dimmed) McdColors.Muted else McdColors.White),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = McdColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tag != null) {
                Text(
                    text = tag,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(McdColors.Red, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Small tile for the "More" row on Home (Browse, Sports scores, Background Noise). */
@Composable
fun CompactTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.width(220.dp).height(84.dp),
        shape = CardDefaults.shape(shape = RoundedCornerShape(6.dp)),
        colors = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(1.dp, McdColors.Line), shape = RoundedCornerShape(6.dp)),
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(6.dp)),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = McdColors.RedBright.copy(alpha = 0.45f), elevation = 10.dp),
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = broadcastStyle(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Section header: bold white title-case text, no decoration. */
@Composable
fun RailHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = broadcastStyle(18.sp),
        modifier = modifier.padding(bottom = 8.dp),
    )
}
