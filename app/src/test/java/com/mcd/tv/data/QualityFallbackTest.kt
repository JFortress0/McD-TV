package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step down from 4K: struggle detection (same thresholds as the web app), sibling channel and 1080p source picks. */
class QualityFallbackTest {

    // ---------------------------------------------------------------- StallWatch

    /** A watch that started at 0 and has been playing since 1 s. */
    private fun playing(): StallWatch = StallWatch(0).also { it.onReady(1_000) }

    private fun stall(w: StallWatch, at: Long, lengthMs: Long) {
        w.onBuffering(at)
        w.onReady(at + lengthMs)
    }

    @Test
    fun threeShortStallsInAMinuteFire() {
        val w = playing()
        stall(w, 20_000, 1_000)
        assertFalse(w.check(21_000))
        stall(w, 40_000, 1_000)
        assertFalse(w.check(41_000))
        w.onBuffering(60_000)
        assertTrue(w.check(60_000))
        assertEquals("stalls", w.reason)
    }

    @Test
    fun threeStallsSpreadOverMoreThanAMinuteDoNotFire() {
        val w = playing()
        stall(w, 11_000, 1_000)
        stall(w, 50_000, 1_000)
        stall(w, 80_000, 1_000) // the first one is 69 s old
        assertFalse(w.check(81_000))
    }

    @Test
    fun oneStallOfEightSecondsFires() {
        val w = playing()
        w.onBuffering(20_000)
        assertFalse(w.check(27_999))
        assertTrue(w.check(28_000))
        assertEquals("long stall", w.reason)
    }

    @Test
    fun twelveSecondsStalledInAMinuteFire() {
        val w = playing()
        stall(w, 15_000, 6_000)
        assertFalse(w.check(21_000))
        w.onBuffering(30_000)
        assertFalse(w.check(35_000)) // 6 + 5 = 11 s
        assertTrue(w.check(36_000)) // 6 + 6 = 12 s (and the second stall is still under 8 s)
        assertEquals("stalled time", w.reason)
    }

    @Test
    fun stalledTimeOutsideTheWindowDoesNotCount() {
        val w = playing()
        stall(w, 15_000, 7_000) // ends at 22 s
        w.onBuffering(80_000)
        assertFalse(w.check(85_000)) // window starts at 25 s: only 5 s count
    }

    @Test
    fun firstTenSecondsAreIgnored() {
        val w = StallWatch(0)
        w.onReady(500)
        w.onBuffering(5_000)
        assertFalse(w.check(14_000)) // a 9 s startup stall is not a stall
        w.onReady(14_000)
        stall(w, 9_999, 0)
        assertFalse(w.check(15_000))
    }

    @Test
    fun bufferingBeforePlaybackStartedIsNotAStall() {
        val w = StallWatch(0)
        w.onBuffering(12_000)
        assertFalse(w.check(30_000))
    }

    @Test
    fun bufferingAfterASeekIsNotAStall() {
        val w = playing()
        w.onSeek()
        w.onBuffering(20_000)
        assertFalse(w.check(30_000))
        w.onReady(30_000)
        w.onBuffering(40_000) // a real stall again
        assertTrue(w.check(48_000))
    }

    @Test
    fun restartIgnoresTheNextTenSeconds() {
        val w = playing()
        w.restart(30_000)
        w.onReady(31_000)
        w.onBuffering(35_000)
        assertFalse(w.check(44_000))
    }

    @Test
    fun firesOnlyOnce() {
        val w = playing()
        w.onBuffering(20_000)
        assertTrue(w.check(28_000))
        assertFalse(w.check(40_000))
        assertTrue(w.fired)
    }

    @Test
    fun lowBandwidthOnFourSamplesFiveSecondsApartFires() {
        val w = playing()
        val bitrate = 20_000_000
        w.onBandwidth(12_000, 15_000_000L, bitrate)
        w.onBandwidth(14_000, 15_000_000L, bitrate) // under 5 s after the last: ignored
        w.onBandwidth(17_000, 15_000_000L, bitrate)
        w.onBandwidth(22_000, 15_000_000L, bitrate)
        assertFalse(w.check(22_000))
        w.onBandwidth(27_000, 15_000_000L, bitrate)
        assertTrue(w.check(27_000))
        assertEquals("bandwidth", w.reason)
    }

    @Test
    fun aGoodSampleResetsTheBandwidthCount() {
        val w = playing()
        val bitrate = 20_000_000
        w.onBandwidth(12_000, 15_000_000L, bitrate)
        w.onBandwidth(17_000, 15_000_000L, bitrate)
        w.onBandwidth(22_000, 15_000_000L, bitrate)
        w.onBandwidth(27_000, 25_000_000L, bitrate)
        w.onBandwidth(32_000, 15_000_000L, bitrate)
        assertFalse(w.check(32_000))
    }

    @Test
    fun unknownBitrateNeverCountsAsLow() {
        val w = playing()
        for (t in 0 until 10) w.onBandwidth(12_000L + t * 5_000L, 1_000L, -1)
        assertFalse(w.check(70_000))
    }

    @Test
    fun bandwidthDuringStartupIsIgnored() {
        val w = playing()
        val bitrate = 20_000_000
        w.onBandwidth(1_000, 1L, bitrate)
        w.onBandwidth(6_000, 1L, bitrate)
        w.onBandwidth(11_000, 1L, bitrate)
        w.onBandwidth(16_000, 1L, bitrate)
        assertFalse(w.check(16_000)) // only 11 s and 16 s counted
    }

    // ---------------------------------------------------------------- 4K and the track cap

    @Test
    fun fourKByHeightOrWholeWordName() {
        assertTrue(QualityFallback.is4k(2160, "ESPN"))
        assertTrue(QualityFallback.is4k(1600, ""))
        assertFalse(QualityFallback.is4k(1080, "ESPN FHD"))
        assertTrue(QualityFallback.is4k(0, "US| ESPN 4K"))
        assertTrue(QualityFallback.is4k(0, "Sky Sports UHD"))
        assertTrue(QualityFallback.is4k(0, "NBC (2160p)"))
        assertTrue(QualityFallback.is4k(0, "Movie.2160.WEB"))
        assertFalse(QualityFallback.is4k(0, "UHDTV Network"))
        assertFalse(QualityFallback.is4k(0, "Channel 24K"))
        assertFalse(QualityFallback.is4k(0, "Fox 21600"))
    }

    @Test
    fun capOnlyWithALowerTrack() {
        assertTrue(QualityFallback.canCapTo1080(listOf(2160, 1080, 720)))
        assertFalse(QualityFallback.canCapTo1080(listOf(2160)))
        assertFalse(QualityFallback.canCapTo1080(listOf(2160, 1440)))
        assertFalse(QualityFallback.canCapTo1080(emptyList()))
    }

    @Test
    fun qualityModeCleansUnknownValues() {
        assertEquals("auto", QualityMode.clean(null))
        assertEquals("auto", QualityMode.clean("max"))
        assertEquals("4k", QualityMode.clean("4k"))
        assertEquals("1080", QualityMode.clean("1080"))
        assertEquals("Max 1080p", QualityMode.label("1080"))
    }

    // ---------------------------------------------------------------- sibling channel

    private fun ch(name: String, url: String = "http://p/" + name.hashCode()) = Channel(name, null, "US", url)

    @Test
    fun siblingPrefersFhdThenHdThenNoLabel() {
        val uhd = ch("US| ESPN 4K")
        val list = listOf(uhd, ch("US| ESPN"), ch("US| ESPN HD"), ch("US| ESPN FHD"), ch("US| ESPN 2 FHD"))
        assertEquals("US| ESPN FHD", QualityFallback.pickSibling(uhd, list)?.name)
        assertEquals("US| ESPN HD", QualityFallback.pickSibling(uhd, list.filterNot { it.name.endsWith("ESPN FHD") })?.name)
        assertEquals("US| ESPN", QualityFallback.pickSibling(uhd, listOf(uhd, ch("US| ESPN"), ch("ESPN 720p")))?.name)
    }

    @Test
    fun siblingIsNeverAnother4kOrSd() {
        val uhd = ch("ESPN 4K")
        val list = listOf(uhd, ch("ESPN UHD"), ch("ESPN 2160p"), ch("ESPN SD"))
        assertNull(QualityFallback.pickSibling(uhd, list))
    }

    @Test
    fun siblingKeepsWestApartFromEast() {
        val uhd = ch("HBO 4K")
        val list = listOf(uhd, ch("HBO West FHD"), ch("HBO (W) HD"), ch("HBO East HD"))
        assertEquals("HBO East HD", QualityFallback.pickSibling(uhd, list)?.name)
        val west = ch("HBO West UHD")
        assertEquals("HBO West FHD", QualityFallback.pickSibling(west, list + west)?.name)
    }

    @Test
    fun siblingPrefersTheMainFeedOverABackup() {
        val uhd = ch("FOX 4K")
        val list = listOf(uhd, ch("FOX HD (backup)"), ch("FOX HD"))
        assertEquals("FOX HD", QualityFallback.pickSibling(uhd, list)?.name)
    }

    @Test
    fun siblingMatchesAmpersandNames() {
        val uhd = ch("A&E 4K")
        assertEquals("A & E FHD", QualityFallback.pickSibling(uhd, listOf(uhd, ch("A & E FHD"), ch("AMC FHD")))?.name)
    }

    @Test
    fun siblingIsNeverTheSameStream() {
        val uhd = ch("ESPN 4K", url = "http://p/1")
        assertNull(QualityFallback.pickSibling(uhd, listOf(uhd, ch("ESPN FHD", url = "http://p/1"))))
    }

    @Test
    fun differentChannelsAreNotSiblings() {
        val uhd = ch("ESPN 4K")
        assertNull(QualityFallback.pickSibling(uhd, listOf(uhd, ch("ESPN2 FHD"), ch("ESPNews HD"))))
    }

    // ---------------------------------------------------------------- on-demand 1080p source

    private fun src(name: String, url: String) = StreamSource("Addon", name, "Movie", url, null, null, "", "", 0.0, null, true)

    @Test
    fun picks1080SourceAfterTheCurrentOneFirst() {
        val list = listOf(
            src("Movie 1080p WEB-DL", "http://s/a"),
            src("Movie 2160p REMUX", "http://s/b"),
            src("Movie 720p", "http://s/c"),
            src("Movie 1080p BluRay", "http://s/d"),
        )
        assertEquals(3, QualityFallback.pick1080Source(list, 1))
        assertEquals(0, QualityFallback.pick1080Source(list.take(3), 1))
    }

    @Test
    fun no1080SourceGivesNull() {
        val list = listOf(src("Movie 2160p", "http://s/a"), src("Movie 720p", "http://s/b"), src("Movie 1080p", "http://s/a"))
        assertNull(QualityFallback.pick1080Source(list, 0)) // the 1080p entry is the same file
    }

    @Test
    fun skipsCam1080Sources() {
        val list = listOf(src("Movie 2160p", "http://s/a"), src("Movie 1080p HDCAM", "http://s/b"))
        assertNull(QualityFallback.pick1080Source(list, 0))
    }
}
