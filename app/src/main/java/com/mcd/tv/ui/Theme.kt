package com.mcd.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.mcd.tv.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis palette: an original holographic heads-up display. Near-black glass, cyan light, amber alerts.
 * Property names are kept from the first theme so every screen picks up the new look:
 * Red / RedBright / RedDark map to the cyan accents, Navy / NavyLight to the dark glass.
 * The intro keeps its own red constants.
 */
object McdColors {
    val Navy = Color(0xFF02060A)       // page background (darkest)
    val NavyLight = Color(0xFF0A2230)  // raised surfaces, focused rows
    val Card = Color(0xD9061722)       // panels and cards at rest (#061722 at 85%)
    val Line = Color(0xFF0E6A7A)       // dim cyan hairlines and outlines
    val Red = Color(0xFF00E5FF)        // primary accent cyan (name kept for compatibility)
    val RedBright = Color(0xFF7DF9FF)  // bright accent: focus rings, glows
    val RedDark = Color(0xFF0B5F6E)    // deep accent
    val Cyan = Color(0xFF00E5FF)
    val White = Color(0xFFEAFBFF)      // HUD white (slightly cyan)
    val Muted = Color(0xFF8FB8C4)      // secondary text

    /** Accent aliases for new code. */
    val Accent = Red
    val AccentBright = RedBright
    val Raised = NavyLight
    val Dim = Line

    /** Text on cyan fills. */
    val Ink = Color(0xFF02060A)

    /** Alerts and live badges. */
    val Amber = Color(0xFFFFB300)
    val Coral = Color(0xFFFF4D6D)

    /** Live badges: warning amber (the name is kept from the old red badge). */
    val LiveRed = Amber

    /** The intro animation keeps the original red broadcast look. */
    val IntroRed = Color(0xFFE11D2E)
    val IntroRedBright = Color(0xFFFF3B4A)
    val IntroRedDark = Color(0xFF8F0F1A)
    val IntroNavy = Color(0xFF07090D)
    val IntroNavyLight = Color(0xFF10141B)
}

/** Base gradient for every page: a faint cyan-lit top fading into near-black. */
val ScreenBackground = Brush.verticalGradient(
    0f to Color(0xFF031018),
    0.55f to Color(0xFF02080D),
    1f to McdColors.Navy,
)

/**
 * The full page background: [ScreenBackground], a very faint cyan grid (a line every 40dp at about 4%)
 * and a soft vignette toward the edges. Use instead of `.background(ScreenBackground)`.
 */
fun Modifier.hudBackground(): Modifier = this
    .background(ScreenBackground)
    .drawBehind {
        val step = 40.dp.toPx()
        val line = McdColors.Accent.copy(alpha = 0.04f)
        val stroke = 1f
        var x = step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
            y += step
        }
        val radius = maxOf(size.width, size.height) * 0.75f
        if (radius > 0f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = radius,
                ),
            )
        }
    }

/** Orbitron: wide techno display face for headings, the wordmark, tab labels and big numbers (SIL OFL). */
val HudDisplay = FontFamily(
    Font(R.font.hud_display_bold, FontWeight.Bold),
    Font(R.font.hud_display_medium, FontWeight.Medium),
)

/** Rajdhani: condensed tech sans for body text, cards and buttons (SIL OFL). */
val HudText = FontFamily(
    Font(R.font.hud_text_medium, FontWeight.Medium),
    Font(R.font.hud_text_semibold, FontWeight.SemiBold),
    Font(R.font.hud_text_bold, FontWeight.Bold),
)

/** Old name kept for compatibility: headings now use the HUD display face. */
val HeadingFont = HudDisplay

/**
 * Headlines, section titles, card titles: Orbitron Bold. Orbitron is wide, so sizes are scaled down
 * (big titles top out at 30sp) and small sizes get extra letter spacing.
 */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White): TextStyle {
    val v = if (size.isSp) size.value else 16f
    val px = if (v >= 20f) minOf(v * 0.85f, 30f) else v * 0.92f
    return TextStyle(
        fontFamily = HudDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = px.sp,
        color = color,
        letterSpacing = if (px < 20f) 1.2.sp else 0.5.sp,
    )
}

/** Orbitron at the exact size given (no cap): big numbers such as codes and scores. */
fun hudDisplayStyle(size: TextUnit, color: Color = McdColors.White, spacing: TextUnit = 1.5.sp) = TextStyle(
    fontFamily = HudDisplay,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    color = color,
    letterSpacing = spacing,
)

/** Small uppercase HUD label: Orbitron Medium, wide tracking. */
fun hudLabelStyle(size: TextUnit = 10.sp, color: Color = McdColors.Accent) = TextStyle(
    fontFamily = HudDisplay,
    fontWeight = FontWeight.Medium,
    fontSize = size,
    color = color,
    letterSpacing = 1.5.sp,
)

/** The intro keeps its heavy italic sports-broadcast lettering. */
fun introStyle(size: TextUnit, color: Color = McdColors.White) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = size,
    color = color,
)

/** Every Material text style in Rajdhani, so plain Text() calls pick up the HUD body face. */
private fun hudTypography(): Typography {
    val b = Typography()
    fun TextStyle.hud() = copy(fontFamily = HudText)
    return Typography(
        displayLarge = b.displayLarge.hud(),
        displayMedium = b.displayMedium.hud(),
        displaySmall = b.displaySmall.hud(),
        headlineLarge = b.headlineLarge.hud(),
        headlineMedium = b.headlineMedium.hud(),
        headlineSmall = b.headlineSmall.hud(),
        titleLarge = b.titleLarge.hud(),
        titleMedium = b.titleMedium.hud(),
        titleSmall = b.titleSmall.hud(),
        bodyLarge = b.bodyLarge.hud(),
        bodyMedium = b.bodyMedium.hud(),
        bodySmall = b.bodySmall.hud(),
        labelLarge = b.labelLarge.hud(),
        labelMedium = b.labelMedium.hud(),
        labelSmall = b.labelSmall.hud(),
    )
}

@Composable
fun McdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McdColors.Accent,
            onPrimary = McdColors.Ink,
            background = McdColors.Navy,
            onBackground = McdColors.White,
            surface = McdColors.Card,
            onSurface = McdColors.White,
        ),
        typography = hudTypography(),
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = HudText))) {
            content()
        }
    }
}

/**
 * HUD ring glyph: a thin ring with 12 short ticks and a small inner dot (original mark, used beside
 * the wordmark and in the launcher art).
 */
@Composable
fun HudRing(diameter: Dp, modifier: Modifier = Modifier, color: Color = McdColors.Accent) {
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val sw = (r * 0.09f).coerceAtLeast(1f)
        // Soft glow behind the ring.
        drawCircle(color.copy(alpha = 0.18f), radius = r * 0.72f, center = c, style = Stroke(sw * 3.5f))
        drawCircle(color, radius = r * 0.72f, center = c, style = Stroke(sw))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = r * (if (i % 3 == 0) 0.80f else 0.84f)
            val outer = r * 0.98f
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(color, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), sw)
        }
        drawCircle(color.copy(alpha = 0.25f), radius = r * 0.26f, center = c)
        drawCircle(McdColors.AccentBright, radius = r * 0.13f, center = c)
    }
}

/** The "JARVIS" wordmark for headers: ring glyph plus Orbitron lettering with a cyan glow. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val style = TextStyle(
        fontFamily = HudDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = (26 * scale).sp,
        letterSpacing = (3 * scale).sp,
        color = McdColors.White,
        shadow = Shadow(color = McdColors.Accent.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = 18f * scale),
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
        HudRing((30 * scale).dp)
        Text("JARVIS", style = style, maxLines = 1)
    }
}

/** Small "TV" badge in the intro's broadcast style (kept for the intro). */
@Composable
fun TvBadge(scale: Float = 1f, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(McdColors.IntroRed, RoundedCornerShape((6 * scale).dp))
            .padding(horizontal = (10 * scale).dp, vertical = (2 * scale).dp),
    ) {
        Text(text = "TV", style = introStyle((30 * scale).sp))
    }
}
