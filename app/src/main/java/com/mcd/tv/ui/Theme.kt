package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** McD TV broadcast palette: deep navy, signal red, white. */
object McdColors {
    val Navy = Color(0xFF060B1A)
    val NavyLight = Color(0xFF141C3A)
    val Card = Color(0xFF1A2244)
    val Red = Color(0xFFD61828)
    val RedDark = Color(0xFF8E0F1B)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9AA3C0)
}

val ScreenBackground = Brush.verticalGradient(listOf(McdColors.NavyLight, McdColors.Navy))

/** Heavy italic sans gives the "sports broadcast" look without bundling a font file. */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = size,
    color = color,
)

@Composable
fun McdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McdColors.Red,
            onPrimary = McdColors.White,
            background = McdColors.Navy,
            onBackground = McdColors.White,
            surface = McdColors.Card,
            onSurface = McdColors.White,
        ),
        content = content,
    )
}

/** The "McD [TV]" wordmark. scale lets the intro draw it huge and the home bar small. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
    ) {
        Text(text = "McD", style = broadcastStyle((44 * scale).sp))
        TvBadge(scale)
    }
}

@Composable
fun TvBadge(scale: Float = 1f, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(McdColors.Red, RoundedCornerShape((6 * scale).dp))
            .padding(horizontal = (10 * scale).dp, vertical = (2 * scale).dp),
    ) {
        Text(text = "TV", style = broadcastStyle((30 * scale).sp))
    }
}
