package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/** IPTV on-demand sources: parsing the provider's library, episode lookup, links and labels. */
class XtreamVodTest {

    private val fixture: JSONObject by lazy {
        val f = listOf(
            File("src/test/resources/xtream-vod-fixture.json"),
            File("app/src/test/resources/xtream-vod-fixture.json"),
        ).first { it.isFile }
        JSONObject(f.readText())
    }

    private fun list(key: String) = fixture.getJSONArray(key).toString()
    private val acct = XtreamVod.Account("http://iptv.example:8080", "u1", "p1")

    private fun movies() = XtreamVod.readList(StringReader(list("get_vod_streams")), "stream_id")
    private fun series() = XtreamVod.readList(StringReader(list("get_series")), "series_id")

    @Test
    fun tmdbIdIsStringOrNumber() {
        assertEquals(398978, XtreamVod.tmdbOf("398978"))
        assertEquals(398978, XtreamVod.tmdbOf(398978))
        assertEquals(398978, XtreamVod.tmdbOf(" 398978 "))
        assertNull(XtreamVod.tmdbOf(""))
        assertNull(XtreamVod.tmdbOf("0"))
        assertNull(XtreamVod.tmdbOf(null))
        assertNull(XtreamVod.tmdbOf("abc"))
    }

    @Test
    fun movieIndexGroupsCopiesByTmdb() {
        val m = movies()
        assertEquals(4, m[398978]!!.size)
        assertEquals(listOf("900001", "900002", "900003", "900004"), m[398978]!!.map { it.id })
        assertEquals("EN - The Irishman (2019)", m[398978]!![0].name)
        assertEquals("mkv", m[1185806]!![0].ext)
        // The Odyssey (2026, tmdb 1368337) is not in this library; titles without an id are dropped.
        assertNull(m[1368337])
        assertFalse(m.values.flatten().any { it.name.contains("Without Id") })
    }

    @Test
    fun seriesIndexKeepsEveryCopy() {
        val s = series()
        assertEquals(listOf("4716", "3147"), s[57532]!!.map { it.id })
    }

    @Test
    fun streamingReaderSkipsNestedValuesAndEscapes() {
        val json = """[{"stream_id":1,"name":"EN - A \"B\" é","tmdb":"5","category_ids":[1,{"x":"]}"}],
            "container_extension":"mp4","info":{"name":"nested"}}, 7, {"stream_id":"2","tmdb":0}]"""
        val m = XtreamVod.readList(StringReader(json), "stream_id")
        assertEquals(1, m.size)
        assertEquals("EN - A \"B\" é", m[5]!![0].name)
        assertEquals("1", m[5]!![0].id)
    }

    @Test
    fun errorReplyGivesEmptyIndex() {
        assertTrue(XtreamVod.readList(StringReader("""{"user_info":{"auth":0}}"""), "stream_id").isEmpty())
        assertTrue(XtreamVod.readList(StringReader(""), "stream_id").isEmpty())
    }

    @Test
    fun indexFileRoundTrips() {
        val idx = XtreamVod.Index(movies(), series())
        val sb = StringBuilder()
        XtreamVod.encode(idx, "k1", 1234L, sb)
        assertFalse(sb.contains("p1"))
        val back = XtreamVod.decode(sb.lineSequence())!!
        assertEquals("k1", back.accountKey)
        assertEquals(1234L, back.savedAt)
        assertEquals(idx.movies[398978], back.index.movies[398978])
        assertEquals(idx.series[57532], back.index.series[57532])
        assertNull(XtreamVod.decode(sequenceOf("garbage")))
    }

    @Test
    fun seriesInfoEpisodes() {
        val eps = XtreamVod.parseSeriesInfo(fixture.getJSONObject("get_series_info_4716").toString())
        assertEquals(4, eps.size)
        val e1 = XtreamVod.findEpisode(eps, 1, 1)!!
        assertEquals("274994", e1.id)
        assertEquals(718, e1.height)
        assertEquals("mp4", e1.ext)
        assertEquals("274996", XtreamVod.findEpisode(eps, 1, 3)!!.id)
        // Season 8 starts at episode 0.
        assertEquals("1246661", XtreamVod.findEpisode(eps, 8, 0)!!.id)
        assertNull(XtreamVod.findEpisode(eps, 2, 1))
    }

    @Test
    fun episodeFoundByTitleWhenNumberIsMissing() {
        val eps = listOf(XtreamVod.Episode("77", 3, -1, "EN - Show - S03E05", "mkv", null))
        assertEquals("77", XtreamVod.findEpisode(eps, 3, 5)!!.id)
        assertNull(XtreamVod.findEpisode(eps, 3, 6))
    }

    @Test
    fun accountFromPlaylistLink() {
        val a = XtreamVod.Account.of("http://iptv.example:8080/get.php?username=u%401&password=p1&type=m3u_plus&output=ts")!!
        assertEquals("http://iptv.example:8080", a.base)
        assertEquals("u@1", a.user)
        assertEquals("http://iptv.example:8080/player_api.php?username=u%401&password=p1&action=get_vod_streams", a.api("get_vod_streams"))
        assertNull(XtreamVod.Account.of("http://iptv.example/playlist.m3u"))
        assertNull(XtreamVod.Account.of("http://iptv.example/get.php?username=u1"))
        assertFalse(a.toString().contains("p1"))
        assertFalse(a.key.contains("p1"))
    }

    @Test
    fun linksFollowTheXtreamPattern() {
        val item = movies()[398978]!![0]
        assertEquals("http://iptv.example:8080/movie/u1/p1/900001.mp4", acct.movieUrl(item))
        val ep = XtreamVod.Episode("274994", 1, 1, "EN - PAW Patrol - S01E01", "mp4", 718)
        assertEquals("http://iptv.example:8080/series/u1/p1/274994.mp4", acct.episodeUrl(ep))
    }

    @Test
    fun labelsComeFromPrefixHeightAndType() {
        assertEquals(listOf("EN", "MP4"), XtreamVod.tags("EN - The Irishman (2019)", "mp4", null))
        assertEquals(listOf("4K", "NF", "MKV"), XtreamVod.tags("4K-NF - The Irishman (2019)", "mkv", null))
        assertEquals(listOf("EN", "720p", "MP4"), XtreamVod.tags("EN - PAW Patrol - S01E01", "mp4", 718))
        assertEquals(listOf("SD"), XtreamVod.tags("Some Title", "ts", 480))
        assertEquals("The Irishman (2019)", XtreamVod.displayTitle("TOP - The Irishman (2019)"))
        assertEquals("Star Wars - A New Hope", XtreamVod.displayTitle("Star Wars - A New Hope"))
    }

    @Test
    fun movieSourcesAreDirectLinksWithoutRealDebrid() {
        val srcs = XtreamVod.movieSources(acct, movies()[398978]!!, "IPTVSmartersPro")
        assertEquals(4, srcs.size)
        srcs.forEach {
            assertEquals(XtreamVod.ADDON, it.addon)
            assertNull(it.infoHash)
            assertNotNull(it.url)
            assertTrue(XtreamVod.isIptv(it))
            assertEquals("IPTVSmartersPro", it.headers["User-Agent"])
        }
        assertEquals("IPTV 4K NF MKV", srcs[3].name)
        assertEquals("4K", srcs[3].quality)
        assertEquals("The Irishman (2019)", srcs[0].title)
    }

    @Test
    fun episodeSourceCarriesSeasonAndEpisode() {
        val ep = XtreamVod.Episode("274994", 1, 1, "EN - PAW Patrol - S01E01", "mp4", 718)
        val s = XtreamVod.episodeSource(acct, ep, null)
        assertEquals(1, s.season)
        assertEquals(1, s.episode)
        assertEquals("720p", s.quality)
        assertTrue(s.headers.isEmpty())
    }

    @Test
    fun iptvSourcesRankFirstAndAreNotCam() {
        val rd = StreamSource("Torrentio", "RD+ 2160p", "The.Irishman.2019.2160p.REMUX.mkv", null, "abc", 0, "4K", "60 GB", 60.0, 10, true)
        val iptv = XtreamVod.movieSources(acct, movies()[398978]!!, null)
        val order = StreamInfo.playOrder(listOf(rd) + iptv, episode = false)
        assertEquals(5, order.size)
        assertTrue(order.take(4).all { XtreamVod.isIptv(it) })
        assertEquals("abc", order.last().infoHash)
        assertTrue(StreamInfo.ranked(iptv).none { it.info.cam })
    }
}
