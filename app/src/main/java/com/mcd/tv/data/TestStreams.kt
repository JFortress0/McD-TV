package com.mcd.tv.data

/** A playable item shown as a card on the home screen. */
data class StreamItem(
    val title: String,
    val subtitle: String,
    val url: String,
)

/**
 * Public test streams used to prove the player works. These are standard
 * developer test feeds, not content sources. Real sources get added by the user.
 */
object TestStreams {
    val all = listOf(
        StreamItem(
            title = "Player Test: Full Features",
            subtitle = "HLS • subtitles • multiple audio tracks",
            url = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        ),
        StreamItem(
            title = "Big Buck Bunny",
            subtitle = "HLS • adaptive quality",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        ),
        StreamItem(
            title = "DASH Test",
            subtitle = "MPEG-DASH • adaptive quality",
            url = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
        ),
    )
}
