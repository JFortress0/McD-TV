package com.mcd.tv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mcd.tv.data.SeekAccumulator

/**
 * PlayerView tuned for a TV remote:
 *  - Controls hidden: LEFT / RIGHT add up 10 s per press (30 s per repeat once a key is held a while) and
 *    seek once, 600 ms after the last press. [seekPreview] shows the target while adding up.
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

    /** Gets the "target time" text while LEFT / RIGHT presses add up, and null when the seek is done. */
    var seekPreview: ((String?) -> Unit)? = null

    private val seeker = SeekAccumulator()

    private val commitSeek = Runnable { finishSeek() }

    /** Seeks to the added-up target (if any) and shows the control bar at the new position. */
    private fun finishSeek() {
        removeCallbacks(commitSeek)
        val p = player
        val target = seeker.commit(p?.duration ?: -1L)
        seekPreview?.invoke(null)
        if (p != null && target != null) {
            p.seekTo(target)
            showController()
        }
    }

    /** Drops a seek that was adding up (BACK, or the view going away). */
    private fun cancelSeek() {
        removeCallbacks(commitSeek)
        if (seeker.pending) {
            seeker.cancel()
            seekPreview?.invoke(null)
        }
    }

    private fun addSeek(p: Player, forward: Boolean, repeatCount: Int) {
        if (!p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val dur = p.duration // C.TIME_UNSET (negative) when unknown
        seeker.press(forward, repeatCount, p.currentPosition, dur)
        seekPreview?.invoke(seeker.label(dur))
        removeCallbacks(commitSeek)
        postDelayed(commitSeek, SEEK_SETTLE_MS)
    }

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
        cancelSeek()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyInterceptor?.invoke(event, isControllerFullyVisible) == true) return true
        val p = player ?: return super.dispatchKeyEvent(event)
        val isSeekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

        // While presses add up, LEFT / RIGHT keep adding even if the control bar is on its way in.
        if (isSeekKey && (seeker.pending || !isControllerFullyVisible)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                addSeek(p, event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event.repeatCount)
            }
            return true
        }
        if (seeker.pending) {
            // BACK drops the seek that was adding up; any other key seeks now, then does its usual job.
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) cancelSeek()
                return true
            }
            finishSeek()
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) hideController()
            return true
        }

        return super.dispatchKeyEvent(event)
    }
}

/** Wait after the last LEFT / RIGHT press before seeking. */
private const val SEEK_SETTLE_MS = 600L
