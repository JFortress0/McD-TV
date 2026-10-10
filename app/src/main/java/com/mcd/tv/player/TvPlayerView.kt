package com.mcd.tv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * PlayerView tuned for a TV remote:
 *  - Controls hidden: LEFT / RIGHT seek 10 s instantly (no need to open the control bar).
 *  - Controls showing: BACK hides them first; a second BACK leaves the player.
 * Everything else (OK to show controls, media keys, CC and settings buttons) is stock Media3.
 * [keyInterceptor] (optional) sees every key first, with whether the controls are fully showing;
 * returning true consumes it (Live TV channel up/down).
 *
 * Focus guard: when the control bar fades out, the button that had focus is hidden and Android moves focus
 * to the Compose screen behind the player, so the remote stopped working until you left the player.
 * Whenever focus leaves this view (or is lost), the player takes it back.
 */
@OptIn(UnstableApi::class)
class TvPlayerView(context: Context) : PlayerView(context) {

    var keyInterceptor: ((KeyEvent, Boolean) -> Boolean)? = null

    private val refocus = Runnable { if (isAttachedToWindow && !hasFocus()) requestFocus() }

    private val focusGuard = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus == null || !contains(newFocus)) {
            removeCallbacks(refocus)
            post(refocus)
        }
    }

    /** True when [v] is this view or inside it (the control bar's buttons). */
    private fun contains(v: View): Boolean {
        var cur: Any? = v
        while (cur is View) {
            if (cur === this) return true
            cur = cur.parent
        }
        return false
    }

    /** Called when the control bar hides: keep remote keys coming here. */
    fun keepFocus() {
        removeCallbacks(refocus)
        post(refocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusGuard)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusGuard)
        removeCallbacks(refocus)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event, isControllerFullyVisible) == true) return true
        val p = player ?: return super.dispatchKeyEvent(event)

        if (!isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        p.seekBack()
                        showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        p.seekForward()
                        showController()
                    }
                    return true
                }
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) hideController()
            return true
        }

        return super.dispatchKeyEvent(event)
    }
}
