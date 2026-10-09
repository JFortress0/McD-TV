package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {

    @Test
    fun parsesAttributesGroupLogoAndName() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn.us" tvg-name="US| ESPN HD" tvg-logo="http://logo/espn.png" group-title="US| SPORTS",US| ESPN HD
            http://host/live/u/p/1.ts
        """.trimIndent()
        val list = M3u.parse(text)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("US| ESPN HD", c.name)
        assertEquals("US| SPORTS", c.group)
        assertEquals("http://logo/espn.png", c.logo)
        assertEquals("http://host/live/u/p/1.ts", c.url)
        assertEquals("espn.us", c.tvgId)
        assertEquals("US| ESPN HD", c.tvgName)
    }

    @Test
    fun headerGuideUrlUsesTheFirstOne() {
        val guides = ArrayList<String>()
        M3u.parseLines(sequenceOf("#EXTM3U url-tvg=\" http://a/guide.xml.gz , http://b/guide.xml\""), onGuideUrl = { guides += it })
        assertEquals(listOf("http://a/guide.xml.gz"), guides)
        guides.clear()
        M3u.parseLines(sequenceOf("#EXTM3U x-tvg-url=\"http://c/epg.xml\""), onGuideUrl = { guides += it })
        assertEquals(listOf("http://c/epg.xml"), guides)
        guides.clear()
        M3u.parseLines(sequenceOf("#EXTM3U"), onGuideUrl = { guides += it })
        assertTrue(guides.isEmpty())
    }

    @Test
    fun missingOrBlankAttributesFallBack() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="" group-title="",CNN
            http://host/2.ts
            #EXTINF:-1 tvg-id="  " tvg-name="Fallback Name",
            http://host/3.ts
            #EXTINF:-1,
            http://host/4.ts
        """.trimIndent()
        val list = M3u.parse(text)
        assertEquals(3, list.size)
        assertEquals("CNN", list[0].name)
        assertEquals("Other", list[0].group)
        assertNull(list[0].logo)
        assertNull(list[1].tvgId)
        assertEquals("Fallback Name", list[1].name)
        assertEquals("Channel", list[2].name)
    }

    @Test
    fun titleIsEverythingAfterTheFirstCommaOutsideQuotes() {
        // Commas inside attribute values and inside the title itself (common in event channel names).
        val text = """
            #EXTINF:-1 tvg-id="nfl3" group-title="Sports, Live",NFL 03: Packers @ Bears, 1:00 PM
            http://host/nfl3.ts
        """.trimIndent()
        val c = M3u.parse(text).single()
        assertEquals("NFL 03: Packers @ Bears, 1:00 PM", c.name)
        assertEquals("Sports, Live", c.group)
    }

    @Test
    fun extinfWithoutCommaDoesNotUseTheRawLineAsName() {
        val text = """
            #EXTINF:-1 tvg-name="UK: Sky Sports Main Event FHD" group-title="UK| SPORTS"
            http://host/sky.ts
            #EXTINF:-1 group-title="UK| SPORTS"
            http://host/nameless.ts
        """.trimIndent()
        val list = M3u.parse(text)
        assertEquals("UK: Sky Sports Main Event FHD", list[0].name)
        assertEquals("Channel", list[1].name)
    }

    @Test
    fun malformedLinesAreSkipped() {
        val text = listOf(
            "#EXTM3U",
            "http://host/orphan.ts", // URL with no #EXTINF before it
            "#EXTINF:-1,Dropped (no URL before the next entry)",
            "#EXTINF:-1 group-title=\"US| NEWS\",ᴿᴬᵂ CNN\r", // Windows line ending
            "#EXTVLCOPT:http-user-agent=VLC",
            "#EXTGRP:News",
            "",
            "   http://host/cnn.ts   ",
            "# just a comment",
            "garbage line after the entry",
        ).joinToString("\n")
        val list = M3u.parse(text)
        assertEquals(1, list.size)
        assertEquals("ᴿᴬᵂ CNN", list[0].name)
        assertEquals("US| NEWS", list[0].group) // #EXTGRP does not override group-title
        assertEquals("http://host/cnn.ts", list[0].url)
    }

    @Test
    fun vodEntriesAreSkipped() {
        val text = """
            #EXTINF:-1 group-title="VOD",Some Movie (2024)
            http://host/movie/u/p/9.mp4
            #EXTINF:-1 group-title="VOD",Some Show S01E01
            http://host/series/u/p/10.mkv
            #EXTINF:-1 group-title="24/7",24/7: Brooklyn Nine Nine
            http://host/live/u/p/11.ts
        """.trimIndent()
        val list = M3u.parse(text)
        assertEquals(listOf("24/7: Brooklyn Nine Nine"), list.map { it.name })
    }

    @Test
    fun maxCapStopsParsing() {
        var pulled = 0
        val lines = generateSequence(0) { it + 1 }.flatMap { i ->
            sequenceOf("#EXTINF:-1,Channel $i", "http://host/$i.ts")
        }.onEach { pulled++ }
        val list = M3u.parseLines(lines, max = 3)
        assertEquals(listOf("Channel 0", "Channel 1", "Channel 2"), list.map { it.name })
        // Stops reading right after the cap (an infinite playlist must not hang).
        assertEquals(6, pulled)
    }
}
