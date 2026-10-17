package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLinkTest {
    private val device = "vat3xhk8pq2mzr7wd4nb9c"
    private val relayId = "k7mq2wzr9xdp4nbvh3ct8e"
    private val relayKey = "Qm9vX2hhc2hfa2V5X2Zvcl90ZXN0c19vbmx5XzEyMzQ" // 43 chars, base64url
    private val ts = 1_792_180_800_000L // Oct 16 2026

    private fun msg(link: String = "$relayId.$relayKey", from: String = device, name: String = "Fire TV", t: Long = ts) =
        JSONObject().put("type", "tv_link").put("from", from).put("name", name).put("link", link).put("ts", t)

    @Test
    fun buildsAndParsesRoundTrip() {
        val m = TvLink.message(device, "Fire TV", relayId, relayKey, ts)
        assertEquals("tv_link", m.getString("type"))
        assertEquals("$relayId.$relayKey", m.getString("link"))
        val info = TvLink.parse(JSONObject(m.toString()))
        assertNotNull(info)
        assertEquals(TvLink.Info(device, "Fire TV", relayId, relayKey, ts), info)
    }

    @Test
    fun rejectsBadId() {
        assertNull(TvLink.parse(msg(link = "K7MQ2WZR9XDP4NBVH3CT8E.$relayKey"))) // upper case
        assertNull(TvLink.parse(msg(link = "k7mq01.$relayKey"))) // 0 and 1 are not in the alphabet, too short
        assertNull(TvLink.parse(msg(link = ".$relayKey")))
        assertNull(TvLink.parse(msg(from = "")))
    }

    @Test
    fun rejectsBadKey() {
        assertNull(TvLink.parse(msg(link = relayId)))
        assertNull(TvLink.parse(msg(link = "$relayId.short")))
        assertNull(TvLink.parse(msg(link = "$relayId.${relayKey.dropLast(1)}+")))
        assertNull(TvLink.parse(msg(link = "$relayId.$relayKey$relayKey")))
    }

    @Test
    fun rejectsOtherTypesAndBadTime() {
        assertNull(TvLink.parse(msg().put("type", "tv_who")))
        assertNull(TvLink.parse(msg(t = 0L)))
        assertNull(TvLink.parse(JSONObject().put("type", "tv_link").put("from", device).put("link", "$relayId.$relayKey")))
    }

    @Test
    fun trimsLongName() {
        val long = "  Living Room Fire TV Stick 4K Max (2nd gen) upstairs  "
        val info = TvLink.parse(msg(name = long))!!
        assertTrue(info.name.length <= 40)
        assertEquals("Living Room Fire TV Stick 4K Max (2nd ge", info.name)
        assertEquals(40, TvLink.message(device, long, relayId, relayKey, ts).getString("name").length)
        assertEquals("TV", TvLink.parse(msg(name = "   "))!!.name)
    }

    @Test
    fun replyTimer() {
        assertTrue(TvLink.replyDue(ts, 0L))
        assertFalse(TvLink.replyDue(ts + 5_000L, ts))
        assertFalse(TvLink.replyDue(ts + 19_999L, ts))
        assertTrue(TvLink.replyDue(ts + 20_000L, ts))
    }

    @Test
    fun announceTimer() {
        val sig = TvLink.signature(relayId)
        val hour = 60 * 60_000L
        assertTrue(TvLink.announceDue(sig, "", ts, 0L)) // never announced
        assertFalse(TvLink.announceDue(sig, sig, ts + 2 * hour, ts))
        assertFalse(TvLink.announceDue(sig, sig, ts + 11 * hour, ts))
        assertTrue(TvLink.announceDue(sig, sig, ts + 11 * hour + 1, ts))
        assertTrue(TvLink.announceDue(TvLink.signature("m3pq8wzr2xdk4nbvh7ct9f"), sig, ts + 60_000L, ts)) // new link
    }

    @Test
    fun signatureIsShortAndStable() {
        val sig = TvLink.signature(relayId)
        assertEquals(12, sig.length)
        assertTrue(sig.matches(Regex("[0-9a-f]{12}")))
        assertEquals(sig, TvLink.signature(relayId))
        assertNotEquals(sig, TvLink.signature("m3pq8wzr2xdk4nbvh7ct9f"))
        assertFalse(sig.contains(relayId))
    }
}
