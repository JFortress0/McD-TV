package com.mcd.tv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Source-row parsing (StreamInfo.parse). Only parse is tested: ranked()/arrange() read Prefs,
 * which needs an Android Context.
 */
class StreamInfoTest {

    private lateinit var savedLocale: Locale

    @Before fun pinLocale() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    private fun src(
        title: String,
        name: String = "Torrentio\n4k",
        quality: String = "",
        sizeGb: Double = 0.0,
        sizeText: String = "",
        hash: String? = null,
        fileIdx: Int? = null,
        url: String? = null,
        cached: Boolean = false,
        addon: String = "Torrentio",
    ) = StreamSource(addon, name, title, url, hash, fileIdx, quality, sizeText, sizeGb, null, cached)

    private fun parse(title: String, name: String = "Torrentio", quality: String = "") =
        StreamInfo.parse(src(title, name = name, quality = quality))

    @Test
    fun remux4kWithDolbyVisionAndAtmos() {
        val i = parse("Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10+.TrueHD.Atmos.7.1-FGT")
        assertEquals("4K", i.resolution)
        assertEquals("REMUX", i.source)
        assertEquals(5, i.stars)
        assertEquals(listOf("DV", "HDR10+"), i.hdr)
        assertEquals("Atmos", i.audio)
        assertFalse(i.cam)
        assertEquals(4, i.resolutionRank)
    }

    @Test
    fun webDl1080pWithDdp51() {
        val i = parse("The.Bear.S03E01.1080p.WEB-DL.DDP5.1.H.264-NTb")
        assertEquals("1080p", i.resolution)
        assertEquals("WEB-DL", i.source)
        assertEquals(4, i.stars)
        assertEquals("DD+ 5.1", i.audio)
        assertEquals(emptyList<String>(), i.hdr)
    }

    @Test
    fun resolutionAndSourceTiers() {
        assertEquals("4K", parse("Movie 4K HDR").resolution)
        assertEquals(listOf("HDR"), parse("Movie 4K HDR").hdr)
        assertEquals("1080p", parse("Movie.1080i.HDTV").resolution)
        assertEquals(3, parse("Show.S01E02.720p.WEBRip.x264").stars)
        assertEquals("WEBRip", parse("Show.S01E02.720p.WEBRip.x264").source) // WEBRip, not WEB-DL
        assertEquals(2, parse("Show.S01E02.480p.HDTV").stars)
        assertEquals("SD", parse("Old.Movie.DVDRip.XviD").resolution)
        assertEquals(5, parse("Movie.2160p.BluRay.x265").stars)
        assertEquals(2, parse("Movie.720p").stars)
        assertEquals(1, parse("Movie").stars)
        assertEquals("", parse("Movie").resolution)
    }

    @Test
    fun resolutionFallsBackToAddonQuality() {
        assertEquals("4K", parse("Some Movie", quality = "4K").resolution)
        assertEquals("SD", parse("Some Movie", quality = "480p").resolution)
        assertEquals("", parse("Some Movie", quality = "unknown").resolution)
        // The text wins over the addon's label.
        assertEquals("720p", parse("Some Movie 720p", quality = "4K").resolution)
    }

    @Test
    fun camAndScreenerReleasesAreFlagged() {
        for (t in listOf("New.Movie.2026.HDCAM.x264", "New Movie 2026 TS", "New.Movie.TELESYNC", "New.Movie.DVDSCR", "New.Movie.HDTC")) {
            val i = parse(t)
            assertTrue(t, i.cam)
            assertEquals(t, 1, i.stars)
        }
        assertEquals("CAM", parse("New.Movie.2026.HDCAM.x264").source)
        // Words that merely contain the letters are not CAM.
        for (t in listOf("Its.Always.Sunny.S01E01.1080p.WEB-DL", "Camelot.1967.1080p.BluRay", "Scream.2022.2160p.WEB-DL")) {
            assertFalse(t, parse(t).cam)
        }
    }

    @Test
    fun audioChannelsIgnoreFileSizes() {
        assertEquals("", parse("Movie 1080p WEB-DL 5.1 GB").audio)
        assertEquals("5.1", parse("Movie 1080p WEB-DL AAC 5.1").audio)
        assertEquals("DTS 7.1", parse("Movie.1080p.BluRay.DTS-HD.MA.7.1").audio)
        assertEquals("5.1", parse("Movie.2024.1080p.WEB-DL.AAC.5.1-GRP").audio)
        assertEquals("", parse("Movie 1080p WEB-DL 15.1 GB").audio)
        assertEquals("", parse("Movie.v1.5.1.1080p").audio)
    }

    @Test
    fun sizeLabels() {
        assertEquals("2.5 GB", StreamInfo.parse(src("x", sizeGb = 2.5)).sizeLabel)
        assertEquals("512 MB", StreamInfo.parse(src("x", sizeGb = 0.5)).sizeLabel)
        assertEquals("3.2 GiB", StreamInfo.parse(src("x", sizeText = "3.2 GiB")).sizeLabel)
    }

    @Test
    fun instantAndDownloadFlags() {
        val cached = StreamInfo.parse(src("x", hash = "ABC", cached = true))
        assertTrue(cached.instant)
        assertFalse(cached.download)
        assertTrue(StreamInfo.parse(src("x", hash = "ABC")).download)
        assertTrue(StreamInfo.parse(src("x", name = "RD download", url = "http://rd/x")).download)
        assertFalse(StreamInfo.parse(src("x", name = "Direct", url = "http://cdn/x.mp4")).download)
    }

    @Test
    fun dedupeKeys() {
        // Same torrent file: hash case does not matter, the file index does.
        val a = StreamInfo.parse(src("Movie.2024.1080p", hash = "ABCDEF", fileIdx = 1, addon = "Torrentio"))
        val b = StreamInfo.parse(src("Movie 2024 1080p (other addon)", hash = "abcdef", fileIdx = 1, addon = "Comet"))
        val c = StreamInfo.parse(src("Movie.2024.1080p", hash = "abcdef", fileIdx = 2))
        assertEquals("h:abcdef:1", a.dedupeKey)
        assertEquals(a.dedupeKey, b.dedupeKey)
        assertNotEquals(a.dedupeKey, c.dedupeKey)

        // No hash: same file name (first line, letters and digits only) and size.
        val d = StreamInfo.parse(src("Movie.2024.1080p.WEB-DL.mkv\n👤 12 💾 4.3 GB", sizeGb = 4.3, url = "http://a/1"))
        val e = StreamInfo.parse(src("movie 2024 1080p web dl mkv", sizeGb = 4.3, url = "http://b/2"))
        val f = StreamInfo.parse(src("Movie.2024.1080p.WEB-DL.mkv", sizeGb = 8.0, url = "http://c/3"))
        assertEquals(d.dedupeKey, e.dedupeKey)
        assertNotEquals(d.dedupeKey, f.dedupeKey)

        // Nothing to go on: the URL keeps sources apart.
        assertNotEquals(
            StreamInfo.parse(src("Movie", url = "http://a/1")).dedupeKey,
            StreamInfo.parse(src("Movie", url = "http://a/2")).dedupeKey,
        )
    }
}
