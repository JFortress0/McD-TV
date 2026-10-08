package com.mcd.tv.player

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * PlayerView tuned for a TV remote:
 *  - Controls hidden: LEFT / RIGHT seek 10 s instantly (no need to open the control bar).
 *  - Controls showing: BACK hides them first; a second BACK leaves the player.
 * Everything else (OK to show controls, media keys, CC and settings buttons) is stock Media3.
 */
@OptIn(UnstableApi::class)
class TvPlayerView(context: Context) : PlayerView(context) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
