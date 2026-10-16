package com.mcd.tv.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdFilterTest {
    private fun src(title: String, hash: String? = "abc", url: String? = null, name: String = "Torrentio\n1080p") =
        StreamSource(addon = "T", name = name, title = title, url = url, infoHash = hash, fileIdx = null,
            quality = "1080p", sizeText = "", sizeGb = 4.0, seeders = null, cached = false)

    @After fun reset() = RdFilter.clear()

    @Test
    fun exactCaseTagsOnly() {
        assertTrue(RdFilter.nameBlocked("The.Odyssey.2026.1080p.WEB-DL.DDP5.1"))
        assertTrue(RdFilter.nameBlocked("Show.S01E01.1080p.WEB.H264-GRP"))
        assertTrue(RdFilter.nameBlocked("Show.S01E01.HDTV.x264-GRP"))
        assertFalse(RdFilter.nameBlocked("Show.S01E01.1080p.WEB.h264-GRACE"))
        assertFalse(RdFilter.nameBlocked("Movie.2026.1080p.web-dl"))
        assertFalse(RdFilter.nameBlocked("Movie.2026.1080p.WEBRip.x265"))
        assertFalse(RdFilter.nameBlocked("Movie.2026.2160p.BluRay.REMUX"))
    }

    @Test
    fun onlyRdSources() {
        assertTrue(RdFilter.likelyBlocked(src("Movie.1080p.WEB-DL")))
        // A plain web link that never touches RD keeps its place.
        assertFalse(RdFilter.likelyBlocked(src("Movie.1080p.WEB-DL", hash = null, url = "http://a/1", name = "Other")))
    }

    @Test
    fun refusedIsRememberedThenExpires() {
        val s = src("Movie.2026.1080p.WEBRip.x265", hash = "ff")
        assertFalse(RdFilter.wasRefused(s, now = 1_000))
        RdFilter.markRefused(s, now = 1_000)
        assertTrue(RdFilter.wasRefused(s, now = 2_000))
        assertTrue(RdFilter.likelyBlocked(s, now = 2_000))
        assertFalse(RdFilter.wasRefused(s, now = 1_000 + RdFilter.KEEP_MS + 1))
    }

    @Test
    fun blockedSortsBelowClean() {
        val list = listOf(
            src("The.Odyssey.2026.1080p.WEB-DL.DDP5.1\n💾 4.9 GB", hash = "a1"),
            src("The.Odyssey.2026.1080p.WEBRip.x265\n💾 1.6 GB", hash = "b2"),
        )
        val order = StreamInfo.playOrder(list, episode = false)
        assertEquals("b2", order.first().infoHash)
        assertTrue(StreamInfo.parse(list[0]).rdBlocked)
        assertFalse(StreamInfo.parse(list[1]).rdBlocked)
    }

    @Test
    fun refusalCarriesCode() {
        val e = RdErrors.refusal(451, """{"error":"infringing_file","error_code":35}""")
        assertEquals(35, e.code)
        assertTrue(e is IllegalStateException)
    }
}
