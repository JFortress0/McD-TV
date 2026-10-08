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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text

/**
 * A D-pad friendly tile. Focus = grows slightly + red border, like a broadcast graphic.
 * [tag] is an optional red label in the corner (e.g. "PLAY" or "PHASE 3").
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
        colors = CardDefaults.colors(
            containerColor = McdColors.Card,
            focusedContainerColor = McdColors.Card,
        ),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, McdColors.Red)),
        ),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            if (dimmed) McdColors.Card else Color(0xFF1A2029),
                            if (dimmed) McdColors.Navy else Color(0xFF0E1218),
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
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
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

/** Section header: thin red bar + uppercase headline. */
@Composable
fun RailHeader(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(22.dp)
                .background(McdColors.Red, RoundedCornerShape(2.dp)),
        )
        Text(
            text = text.uppercase(),
            style = broadcastStyle(21.sp).copy(letterSpacing = 1.sp),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
