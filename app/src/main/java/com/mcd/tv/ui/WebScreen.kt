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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text

/**
 * A simple TV web browser with a remote-controlled mouse pointer.
 *  - Arrows move the pointer (hold to speed up). At the screen edge, the page scrolls.
 *  - OK clicks. BACK goes back a page, or leaves the browser on the first page.
 *  - Fullscreen video works. Pop-up windows and pages that redirect you to another
 *    site without a click are blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(startUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val focus = remember { FocusRequester() }
    var cursor by remember { mutableStateOf(Offset(400f, 300f)) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var title by remember { mutableStateOf(startUrl) }
    var speed by remember { mutableStateOf(18f) }
    val startHost = remember { Uri.parse(startUrl).host?.removePrefix("www.") ?: "" }

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
            isFocusable = false // the remote drives the pointer, not WebView focus
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host?.removePrefix("www.") ?: return true
                    val sameSite = host == startHost || host.endsWith(".$startHost")
                    // Block cross-site redirects that happen without a click (typical ad hijacks).
                    return !sameSite && !request.hasGesture()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    title = view.title ?: url ?: ""
                }
            }
            webChromeClient = chrome
            loadUrl(startUrl)
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

    fun tap() {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursor.x, cursor.y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cursor.x, cursor.y, 0)
        web.dispatchTouchEvent(down)
        web.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    BackHandler(enabled = fullscreenView != null || web.canGoBack()) {
        if (fullscreenView != null) chrome.onHideCustomView() else web.goBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp) { speed = 18f; return@onPreviewKeyEvent false }
                val w = web.width.toFloat()
                val h = web.height.toFloat()
                val step = speed
                speed = (speed * 1.15f).coerceAtMost(70f) // holding a direction accelerates
                when (e.key) {
                    Key.DirectionLeft -> { if (cursor.x <= 0f) web.scrollBy(-200, 0) else cursor = cursor.copy(x = (cursor.x - step).coerceAtLeast(0f)); true }
                    Key.DirectionRight -> { if (cursor.x >= w - 1) web.scrollBy(200, 0) else cursor = cursor.copy(x = (cursor.x + step).coerceAtMost(w - 1)); true }
                    Key.DirectionUp -> { if (cursor.y <= 0f) web.scrollBy(0, -300) else cursor = cursor.copy(y = (cursor.y - step).coerceAtLeast(0f)); true }
                    Key.DirectionDown -> { if (cursor.y >= h - 1) web.scrollBy(0, 300) else cursor = cursor.copy(y = (cursor.y + step).coerceAtMost(h - 1)); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { tap(); true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        // Pointer: white ring with a red dot.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 16f, center = cursor, style = Stroke(width = 6f))
            drawCircle(Color.White, radius = 14f, center = cursor, style = Stroke(width = 3f))
            drawCircle(McdColors.Red, radius = 5f, center = cursor)
        }
        Text(
            title,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(4.dp),
        )
    }
}
