package com.mcd.tv.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * McD TV intro. An original sports-broadcast style open (not a copy of any network's).
 *
 * Everything is driven by one clock, t (seconds since start), so the visuals stay locked
 * to the audio track res/raw/mcd_intro.ogg. Audio cue points:
 *   0.00  riser / whoosh
 *   0.85  first slam  (+ stabs at 1.05 and 1.25)
 *   1.60  chord hit   -> TV badge flies in
 *   2.00  chord hit   -> metallic sheen
 *   2.45  big hit     -> flash, rings, ticker bar
 *   2.75  voice: "This is Mick-Dee Tee Vee"
 *   6.40  end
 * Any remote button skips straight to the home screen.
 */
private const val INTRO_SECONDS = 6.4f

/** 1 at the moment [at], decaying toward 0 afterwards. 0 before [at]. */
private fun pulse(t: Float, at: Float, decay: Float): Float = if (t < at) 0f else exp(-(t - at) * decay)

/** 0 before [a], 1 after [b], linear between. */
private fun ramp(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

/** A bright diagonal glint that sweeps across the element during the hit windows. */
private fun Modifier.sheen(time: () -> Float): Modifier = this
    .graphicsLayer {
        val t = time()
        // Offscreen compositing is only needed while the glint is drawing.
        compositingStrategy = if (t in 1.95f..3.0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val t = time()
        for (start in floatArrayOf(2.0f, 2.45f)) {
            val p = ramp(t, start, start + 0.45f)
            if (p > 0f && p < 1f) {
                val x = -size.width * 0.4f + size.width * 1.8f * p
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + size.width * 0.25f, size.height),
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
    }

@Composable
fun IntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var t by remember { mutableFloatStateOf(0f) }
    var finished by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    // Audio: plays once, released when the intro leaves the screen (including on skip).
    DisposableEffect(Unit) {
        val mp: MediaPlayer? = runCatching { MediaPlayer.create(context, R.raw.mcd_intro) }.getOrNull()
        mp?.start()
        onDispose {
            runCatching { mp?.stop() }
            mp?.release()
        }
    }

    // Clock: frame-based, so it still works if system animations are disabled.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        val start = withFrameNanos { it }
        while (t < INTRO_SECONDS) {
            val now = withFrameNanos { it }
            t = (now - start) / 1_000_000_000f
        }
        finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any button (on release) skips. Returning true also swallows BACK here.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp) finish()
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        // ---- Background: navy gradient, red glow, speed streaks, shock rings ----
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.verticalGradient(listOf(McdColors.NavyLight, McdColors.Navy)))

            val glowA = (0.15f + 0.35f * ramp(t, 0f, 0.85f) + 0.4f * pulse(t, 2.45f, 2.5f)).coerceAtMost(0.9f)
            val glowR = size.minDimension * (0.25f + 0.45f * ramp(t, 0f, 0.85f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(McdColors.Red.copy(alpha = glowA), Color.Transparent),
                    center = center,
                    radius = glowR,
                ),
                radius = glowR,
                center = center,
            )

            // Streaks race in during the riser, then cruise.
            val warm = t.coerceAtMost(0.85f)
            val dist = 0.4f * t + 1.6f * warm * warm + if (t > 0.85f) (t - 0.85f) * 0.3f else 0f
            rotate(degrees = -25f, pivot = center) {
                val w = size.width
                val h = size.height
                for (i in 0 until 10) {
                    val frac = (dist * (0.7f + 0.1f * i) + i * 0.137f) % 1f
                    val x = frac * w * 1.8f - w * 0.4f
                    val y = h * (i / 10f) * 1.6f - h * 0.3f
                    val red = i % 3 == 0
                    drawRect(
                        color = if (red) McdColors.Red.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f),
                        topLeft = Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(w * 0.28f, 4f + (i % 3) * 5f),
                    )
                }
            }

            // Anamorphic lens flare: a hot horizontal streak on the two biggest hits.
            val flareA = maxOf(pulse(t, 0.85f, 5f), pulse(t, 2.45f, 4f))
            if (flareA > 0.01f) {
                val fh = size.height * 0.012f
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xFFFFE3E3).copy(alpha = flareA), Color.White.copy(alpha = flareA), Color(0xFFFFE3E3).copy(alpha = flareA), Color.Transparent),
                    ),
                    topLeft = Offset(0f, center.y - fh / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, fh),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = flareA * 0.8f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.18f,
                    ),
                    radius = size.minDimension * 0.18f,
                    center = center,
                )
            }

            for (k in 0 until 3) {
                val s = 2.45f + k * 0.12f
                val p = ramp(t, s, s + 0.9f)
                if (p > 0f && p < 1f) {
                    drawCircle(
                        color = Color.White.copy(alpha = (1f - p) * 0.5f),
                        radius = size.minDimension * (0.1f + 0.9f * easeOut(p)),
                        center = center,
                        style = Stroke(width = 8f * (1f - p) + 1f),
                    )
                }
            }
        }

        // ---- McD TV logo art: slams in on the first hit, shakes on each hit, slow push-in after ----
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.intro_logo),
            contentDescription = "McD TV",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = ramp(t, 0.80f, 0.90f)
                    val slam = 0.35f * pulse(t, 0.85f, 16f)
                    val stabs = 0.04f * (pulse(t, 1.05f, 20f) + pulse(t, 1.25f, 20f) + pulse(t, 1.6f, 18f) + pulse(t, 2.0f, 18f))
                    val hit = 0.05f * pulse(t, 2.45f, 10f)
                    val pushIn = 0.06f * ramp(t, 0.85f, 6.4f)
                    val sc = 1f + slam + stabs + hit + pushIn
                    scaleX = sc
                    scaleY = sc
                    val amp = (14f * pulse(t, 0.85f, 14f) + 9f * pulse(t, 1.05f, 14f) +
                        9f * pulse(t, 1.25f, 14f) + 18f * pulse(t, 2.45f, 12f)) * density
                    translationX = sin(t * 97f) * amp
                    translationY = cos(t * 83f) * amp * 0.6f
                }
                .sheen { t },
        )

        // ---- Bottom ticker bar slams in on the big hit ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp)
                .graphicsLayer {
                    translationX = -(1f - easeOut(ramp(t, 2.45f, 2.8f))) * size.width
                }
                .background(McdColors.Red),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "LIVE  •  MOVIES  •  SHOWS  •  SPORTS  •  YOUR STREAMS  •  YOUR RULES  •  LIVE  •  MOVIES  •  SHOWS",
                style = introStyle(24.sp).copy(letterSpacing = 3.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(start = 32.dp)
                    .graphicsLayer { translationX = -(t - 2.45f).coerceAtLeast(0f) * 90f * density },
            )
        }

        Text(
            text = "Press any button to skip",
            style = introStyle(14.sp, McdColors.Muted).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Normal),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 32.dp, bottom = 72.dp)
                .graphicsLayer { alpha = ramp(t, 1f, 1.5f) * (1f - ramp(t, 5.5f, 5.8f)) },
        )

        // ---- Flashes, fade in from black, fade out to black ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = maxOf(0.9f * pulse(t, 0.85f, 9f), 0.7f * pulse(t, 2.45f, 7f))
                }
                .background(Color.White),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maxOf(1f - ramp(t, 0f, 0.25f), ramp(t, 5.7f, 6.4f)) }
                .background(Color.Black),
        )
    }
}

/**
 * Broadcast-style chrome lettering: a stack of dark red layers gives a 3D extrusion,
 * and a banded metallic gradient on top reads as polished chrome.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun ChromeWordmark(text: String, modifier: Modifier = Modifier) {
    val chrome = Brush.verticalGradient(
        0.00f to Color(0xFFFFFFFF),
        0.42f to Color(0xFFD9DEE8),
        0.50f to Color(0xFF6E7587),
        0.56f to Color(0xFFB9C0CE),
        1.00f to Color(0xFFFFFFFF),
    )
    Box(modifier = modifier) {
        for (i in 10 downTo 1) {
            Text(
                text = text,
                style = introStyle(150.sp, if (i > 6) Color(0xFF3A050B) else McdColors.RedDark),
                modifier = Modifier.offset(x = (i * 0.9f).dp, y = (i * 0.9f).dp),
            )
        }
        Text(text = text, style = introStyle(150.sp).copy(brush = chrome))
    }
}
