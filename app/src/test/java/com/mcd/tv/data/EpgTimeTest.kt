package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * XMLTV times and now/next lookup. The XML parsing itself uses android.util.Xml (Android only),
 * so it is covered by the emulator QA run, not here.
 */
class EpgTimeTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun parsesXmltvTimes() {
        assertEquals(ms("2026-10-08T18:30:00Z"), Epg.parseTime("20261008183000 +0000"))
        assertEquals(ms("2026-10-08T23:30:00Z"), Epg.parseTime("20261008183000 -0500"))
        assertEquals(ms("2026-10-08T13:00:00Z"), Epg.parseTime("20261008183000 +0530"))
        assertEquals(ms("2026-10-08T18:30:00Z"), Epg.parseTime("20261008183000"))
        assertEquals(ms("2026-10-08T18:30:00Z"), Epg.parseTime("202610081830")) // no seconds
        assertEquals(ms("2024-02-29T00:00:00Z"), Epg.parseTime("20240229000000 +0000")) // leap day
        assertEquals(ms("1999-12-31T23:59:59Z"), Epg.parseTime("19991231235959 +0000"))
    }

    @Test
    fun rejectsBadTimes() {
        assertNull(Epg.parseTime(null))
        assertNull(Epg.parseTime(""))
        assertNull(Epg.parseTime("2026100818"))
        assertNull(Epg.parseTime("2026-10-08 18:30"))
        assertNull(Epg.parseTime("20261308183000 +0000")) // month 13
        assertNull(Epg.parseTime("20261000183000 +0000")) // day 0
    }

    @Test
    fun nowAndNext() {
        val a = Programme("Morning News", 1_000, 2_000, "")
        val b = Programme("NFL Football: Packers at Bears", 2_000, 3_000, "")
        val c = Programme("Postgame", 3_000, 4_000, "")
        val guide = mapOf("k" to listOf(a, b, c))
        assertEquals(a to b, Epg.nowNext("k", guide, now = 1_500))
        assertEquals(b to c, Epg.nowNext("k", guide, now = 2_000)) // a ended exactly now
        assertEquals(null to a, Epg.nowNext("k", guide, now = 500))
        assertEquals(c to null, Epg.nowNext("k", guide, now = 3_999))
        assertEquals(null to null, Epg.nowNext("k", guide, now = 9_000))
        assertEquals(null to null, Epg.nowNext("missing", guide, now = 1_500))
    }

    @Test
    fun guideKeyIsTheStreamUrl() {
        assertEquals("http://host/1.ts", Epg.keyOf(Channel("ESPN", null, "US| SPORTS", "http://host/1.ts", "espn.us")))
    }
}
