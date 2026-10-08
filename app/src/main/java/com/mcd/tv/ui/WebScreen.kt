package com.mcd.tv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.mcd.tv.data.Prefs
import kotlinx.coroutines.delay

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** Makes the biggest video or video frame on the page fill the screen (works on most sites). */
private const val FILL_VIDEO_JS = """
(function(){
  var best=null, area=0;
  document.querySelectorAll('video, iframe').forEach(function(e){
    var r=e.getBoundingClientRect(); var a=r.width*r.height; if(a>area){area=a;best=e;}
  });
  if(!best) return 'none';
  if(best.dataset.mcdFull==='1'){ best.style.cssText=best.dataset.mcdOld||''; best.dataset.mcdFull='0'; return 'off'; }
  best.dataset.mcdOld=best.style.cssText; best.dataset.mcdFull='1';
  best.style.cssText='position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;border:0!important;';
  return 'on';
})()
"""

/** Play or pause the main video on the page. */
private const val PLAY_PAUSE_JS = """
(function(){ var v=document.querySelector('video'); if(!v) return 'none'; if(v.paused){v.play();return 'play';} v.pause(); return 'pause'; })()
"""

/**
 * TV web browser with a remote-controlled pointer.
 *  Arrows: move the pointer (hold to speed up); at a screen edge the page scrolls.
 *  OK: click.   Rewind / Fast-forward: page up / page down.   Play/Pause: play or pause the video.
 *  Menu (≡): toolbar with Back, Forward, Reload, Start page, Zoom, Fill screen with video, Desktop/Mobile.
 *  BACK: previous page; on the first page, leaves the browser.
 * Pop-up windows and cross-site redirects that happen without a click are blocked.
 * Each site reopens on the last page you were on.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(startUrl: String, onPlayVideo: (url: String, headers: Map<String, String>, title: String) -> Unit = { _, _, _ -> }) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focus = remember { FocusRequester() }
    val barFocus = remember { FocusRequester() }
    var cursor by remember { mutableStateOf(Offset(640f, 360f)) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var title by remember { mutableStateOf(startUrl) }
    var speed by remember { mutableStateOf(22f) }
    var showBar by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf("") }
    var desktop by remember { mutableStateOf(true) }
    val startHost = remember { Uri.parse(startUrl).host?.removePrefix("www.") ?: "" }
    val lastKey = "web_last_$startHost"
    // Video streams this page loads (HLS/DASH/MP4), newest first, with the headers they were requested with.
    val found = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, Map<String, String>>>() }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentPage = remember { java.util.concurrent.atomic.AtomicReference(startUrl) }
    val currentUa = remember { java.util.concurrent.atomic.AtomicReference(DESKTOP_UA) } // readable off the UI thread

    val chrome = remember {
        object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val decor = activity?.window?.decorView as? FrameLayout ?: return
                decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenView = view
            }

            override fun onHideCustomView() {
                val decor = activity?.window?.decorView as? FrameLayout
                fullscreenView?.let { decor?.removeView(it) }
                fullscreenView = null
            }
        }
    }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false) // window.open loads here, never a new window
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = DESKTOP_UA
            isFocusable = false // the remote drives the pointer, not WebView focus
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Video players often live in a frame from another site: always let frames load.
                    if (!request.isForMainFrame) return false
                    val host = request.url.host?.removePrefix("www.") ?: return true
                    val sameSite = host == startHost || host.endsWith(".$startHost")
                    // Block cross-site redirects that happen without a click (typical ad hijacks).
                    return !sameSite && !request.hasGesture()
                }

                /** Watches (never blocks) what the page loads, to spot the video stream it plays. */
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url.toString()
                    val path = (request.url.path ?: "").lowercase()
                    val isStream = path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                        (path.endsWith(".mp4") && !u.contains("/ads/", ignoreCase = true))
                    if (isStream) {
                        val h0 = request.requestHeaders.filterKeys { k -> k.equals("Referer", true) || k.equals("Origin", true) || k.equals("User-Agent", true) }
                            .mapKeys { (k, _) -> when { k.equals("Referer", true) -> "Referer"; k.equals("Origin", true) -> "Origin"; else -> "User-Agent" } }
                        // Stream hosts often check where the request came from; send what the page would.
                        val h = h0.toMutableMap().apply {
                            putIfAbsent("Referer", currentPage.get())
                            currentUa.get().takeIf { it.isNotBlank() }?.let { putIfAbsent("User-Agent", it) }
                        }
                        mainHandler.post {
                            found.removeAll { it.first == u }
                            found.add(0, u to h)
                            while (found.size > 5) found.removeAt(found.lastIndex)
                        }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null) currentPage.set(url)
                    mainHandler.post { found.clear() }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    title = view.title ?: url ?: ""
                    val u = url ?: return
                    val host = Uri.parse(u).host?.removePrefix("www.") ?: return
                    if (host == startHost || host.endsWith(".$startHost")) Prefs.putJson(lastKey, u)
                }
            }
            webChromeClient = chrome
            loadUrl(Prefs.json(lastKey).ifBlank { startUrl })
        }
    }

    DisposableEffect(web) {
        onDispose {
            fullscreenView?.let { (activity?.window?.decorView as? FrameLayout)?.removeView(it) }
            web.stopLoading()
            web.destroy()
        }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { delay(7000); showHelp = false }
    LaunchedEffect(toast) { if (toast.isNotBlank()) { delay(2500); toast = "" } }
    LaunchedEffect(showBar) {
        withFrameNanos { }
        runCatching { if (showBar) barFocus.requestFocus() else focus.requestFocus() }
    }

    fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0)
        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    fun js(code: String, say: (String) -> String) = web.evaluateJavascript(code) { r -> toast = say(r.trim('"')) }

    BackHandler(enabled = showBar || fullscreenView != null || web.canGoBack()) {
        when {
            showBar -> showBar = false
            fullscreenView != null -> chrome.onHideCustomView()
            else -> web.goBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) { showBar = !showBar; return@onPreviewKeyEvent true }
                if (showBar) return@onPreviewKeyEvent false // toolbar has focus: let it handle the keys
                if (e.type == KeyEventType.KeyUp) { speed = 22f; return@onPreviewKeyEvent false }
                val w = web.width.toFloat()
                val h = web.height.toFloat()
                val step = speed
                speed = (speed * 1.18f).coerceAtMost(90f) // holding a direction accelerates
                when (e.key) {
                    Key.DirectionLeft -> { if (cursor.x <= 0f) web.scrollBy(-250, 0) else cursor = cursor.copy(x = (cursor.x - step).coerceAtLeast(0f)); true }
                    Key.DirectionRight -> { if (cursor.x >= w - 1) web.scrollBy(250, 0) else cursor = cursor.copy(x = (cursor.x + step).coerceAtMost(w - 1)); true }
                    Key.DirectionUp -> { if (cursor.y <= 0f) web.scrollBy(0, -350) else cursor = cursor.copy(y = (cursor.y - step).coerceAtLeast(0f)); true }
                    Key.DirectionDown -> { if (cursor.y >= h - 1) web.scrollBy(0, 350) else cursor = cursor.copy(y = (cursor.y + step).coerceAtMost(h - 1)); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { tap(); true }
                    Key.MediaFastForward, Key.PageDown -> { web.pageDown(false); true }
                    Key.MediaRewind, Key.PageUp -> { web.pageUp(false); true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        val v = found.firstOrNull()
                        if (v != null) onPlayVideo(v.first, v.second, title)
                        else js(PLAY_PAUSE_JS) { if (it == "none") "No video found yet. Start the video on the page first." else if (it == "play") "Playing" else "Paused" }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())

        // Pointer: big arrow with a dark outline, readable on any page.
        Canvas(Modifier.fillMaxSize()) {
            val p = Path().apply {
                moveTo(cursor.x, cursor.y)
                lineTo(cursor.x, cursor.y + 34f)
                lineTo(cursor.x + 9f, cursor.y + 26f)
                lineTo(cursor.x + 16f, cursor.y + 40f)
                lineTo(cursor.x + 22f, cursor.y + 37f)
                lineTo(cursor.x + 15f, cursor.y + 23f)
                lineTo(cursor.x + 26f, cursor.y + 23f)
                close()
            }
            drawPath(p, McdColors.White)
            drawPath(p, Color.Black, style = Stroke(width = 3f))
            drawCircle(McdColors.Red, radius = 4f, center = cursor)
        }

        // Title strip.
        Text(
            title, color = McdColors.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )

        // First-open help.
        if (showHelp && !showBar) {
            Text(
                "Arrows: move  •  OK: click  •  ⏪ ⏩: page up/down  •  ⏯: watch the page's video in the Jarvis player  •  ≡ Menu: toolbar  •  Back: previous page",
                color = McdColors.White, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.8f), HudShape).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        if (found.isNotEmpty() && !showBar) {
            Text(
                "▶  Video found: press ⏯ Play/Pause to watch it in the Jarvis player (or ≡ Menu)",
                color = McdColors.Ink, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
                    .background(McdColors.Red, HudShape).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        if (toast.isNotBlank()) {
            Text(
                toast, color = McdColors.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), HudShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // Toolbar (Menu button).
        if (showBar) {
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color(0xEE07090D)).padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (found.isNotEmpty()) {
                        ActionButton("▶ Play video in Jarvis player", {
                            showBar = false
                            found.firstOrNull()?.let { onPlayVideo(it.first, it.second, title) }
                        }, Modifier.focusRequester(barFocus), primary = true)
                    }
                    ActionButton("◀ Back", { if (web.canGoBack()) web.goBack(); showBar = false }, if (found.isEmpty()) Modifier.focusRequester(barFocus) else Modifier)
                    ActionButton("Forward ▶", { if (web.canGoForward()) web.goForward(); showBar = false })
                    ActionButton("⟳ Reload", { web.reload(); showBar = false })
                    ActionButton("⌂ Start page", { web.loadUrl(startUrl); showBar = false })
                    ActionButton("Zoom −", { web.zoomOut() })
                    ActionButton("Zoom +", { web.zoomIn() })
                    ActionButton("⛶ Fill screen with video", {
                        showBar = false
                        js(FILL_VIDEO_JS) { when (it) { "on" -> "Video fills the screen. Use this button again to undo."; "off" -> "Back to normal"; else -> "No video found on this page" } }
                    }, primary = true)
                    ActionButton(if (desktop) "Mobile site" else "Desktop site", {
                        desktop = !desktop
                        web.settings.userAgentString = if (desktop) DESKTOP_UA else null
                        currentUa.set(if (desktop) DESKTOP_UA else "")
                        web.reload(); showBar = false
                    })
                }
                Text("Menu or Back closes this bar.", color = McdColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
