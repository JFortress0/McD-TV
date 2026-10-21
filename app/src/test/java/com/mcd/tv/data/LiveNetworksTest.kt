package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entertainment by network: one entry per network, best feed first, pinned networks on top. And Live Games leagues. */
class LiveNetworksTest {

    private var n = 0
    private fun ch(name: String, group: String = "US| ENTERTAINMENT") = Channel(name, null, group, "http://x/live/${n++}.ts")

    @Test
    fun networkKeyJoinsFeedsOfOneNetwork() {
        val amc = listOf("AMC", "AMC HD", "AMC West", "AMC East", "AMC (2)", "AMC (W)", "AMC Channel", "AMC +1", "AMC+1", "AMC E")
        amc.forEach { assertEquals(it, "AMC", LiveOrganizer.networkKey(LiveOrganizer.cleanName(it))) }
        assertEquals("USA", LiveOrganizer.networkKey("USA Network"))
        assertEquals("TRAVEL", LiveOrganizer.networkKey("Travel Channel"))
        assertEquals("FOOD", LiveOrganizer.networkKey("Food Network"))
        assertEquals("BRAVO", LiveOrganizer.networkKey("Bravo TV"))
    }

    @Test
    fun networkKeyKeepsDifferentNetworksApart() {
        assertNotEquals(LiveOrganizer.networkKey("FX"), LiveOrganizer.networkKey("FXX"))
        assertNotEquals(LiveOrganizer.networkKey("AMC"), LiveOrganizer.networkKey("AMC+"))
        assertNotEquals(LiveOrganizer.networkKey("Discovery"), LiveOrganizer.networkKey("Discovery Science"))
        assertNotEquals(LiveOrganizer.networkKey("E!"), LiveOrganizer.networkKey("E"))
        assertEquals("TV LAND", LiveOrganizer.networkKey("TV Land"))
        assertEquals("NAT GEO WILD", LiveOrganizer.networkKey("Nat Geo Wild"))
    }

    @Test
    fun feedScoreRanksQualityAndPenalizesBackupsWestAndTimeshift() {
        val us = "US"
        val fhd = LiveOrganizer.feedScore("US| AMC FHD", "US| ENTERTAINMENT", us)
        val hd = LiveOrganizer.feedScore("US| AMC HD", "US| ENTERTAINMENT", us)
        val plain = LiveOrganizer.feedScore("AMC", "ENTERTAINMENT", LiveOrganizer.UNKNOWN_COUNTRY)
        val sd = LiveOrganizer.feedScore("US| AMC SD", "US| ENTERTAINMENT", us)
        assertTrue(LiveOrganizer.feedScore("US| AMC 4K", "", us) > fhd)
        assertTrue(fhd > hd)
        assertTrue(hd > plain)
        assertTrue(plain > sd - 10)
        assertTrue(LiveOrganizer.feedScore("US| AMC HD", "", us) > LiveOrganizer.feedScore("US| AMC West HD", "", us))
        assertTrue(LiveOrganizer.feedScore("US| AMC HD", "", us) > LiveOrganizer.feedScore("US| AMC HD (backup)", "", us))
        assertTrue(LiveOrganizer.feedScore("AMC", "", us) > LiveOrganizer.feedScore("AMC +1", "", us))
        // A RAW 60 fps feed beats a plain HD one of the same height.
        assertTrue(LiveOrganizer.feedScore("AT&T: AMC HD RAW 60fps", "", us) > hd)
    }

    @Test
    fun entertainmentHasOneEntryPerNetworkWithBestFeedFirst() {
        n = 0
        val list = listOf(
            ch("AMC West"), ch("US| AMC HD"), ch("US| AMC FHD"), ch("AMC (backup)"),
            ch("US| Bravo HD"), ch("Bravo West"),
            ch("FX"), ch("FXX"),
            ch("Pluto Comedy", "ENTERTAINMENT"),
        )
        val idx = LiveOrganizer.build(list)
        val ent = idx.sections.first { it.name == LiveOrganizer.ENTERTAINMENT }
        assertEquals(9, ent.count)
        assertEquals(listOf("AMC", "BRAVO", "FX", "FXX", "PLUTO COMEDY"), ent.networks.map { it.key })
        val amc = ent.networks.first()
        assertEquals("US| AMC FHD", idx.channels[amc.primary].name)
        assertEquals("AMC (backup)", idx.channels[amc.feeds.last()].name)
        assertEquals(3, amc.alternates)
        // Networks replace the provider-group chips.
        assertTrue(ent.subgroups.isEmpty())
        // Other sections are not grouped.
        assertTrue(idx.sections.filter { it.name != LiveOrganizer.ENTERTAINMENT }.all { it.networks.isEmpty() })
    }

    @Test
    fun usNetworksComeBeforeUnmarkedOnesThenAtoZ() {
        n = 0
        val list = listOf(ch("Zoo TV", "ENTERTAINMENT"), ch("US| TLC"), ch("Ace Shows", "ENTERTAINMENT"), ch("US| A&E"))
        val idx = LiveOrganizer.build(list)
        val ent = idx.sections.first { it.name == LiveOrganizer.ENTERTAINMENT }
        assertEquals(listOf("A&E", "TLC", "ACE SHOWS", "ZOO"), ent.networks.map { it.key })
        assertEquals(listOf(true, true, false, false), ent.networks.map { it.us })
    }

    @Test
    fun personalizePinsFavoritesThenRecentsAndShowsTheirFeedFirst() {
        n = 0
        val list = listOf(ch("US| AMC FHD"), ch("AMC West"), ch("US| Bravo"), ch("US| FX"), ch("US| TLC"))
        val idx = LiveOrganizer.build(list)
        val nets = idx.sections.first { it.name == LiveOrganizer.ENTERTAINMENT }.networks
        val amcWest = list[1].url
        val (ordered, pinned) = LiveOrganizer.personalize(nets, idx.channels, favorites = listOf(list[4].url, amcWest), recents = listOf(list[3].url, list[4].url))
        assertEquals(3, pinned)
        assertEquals(listOf("TLC", "AMC", "FX", "BRAVO"), ordered.map { it.key })
        // The favorite West feed leads AMC; the FHD feed is still there, second.
        assertEquals(amcWest, idx.channels[ordered[1].primary].url)
        assertEquals("US| AMC FHD", idx.channels[ordered[1].feeds[1]].name)
        // Nothing saved: unchanged.
        assertEquals(nets to 0, LiveOrganizer.personalize(nets, idx.channels, emptyList(), emptyList()))
    }

    @Test
    fun letterOfAndFeedLabel() {
        assertEquals("A", LiveOrganizer.letterOf("amc"))
        assertEquals("#", LiveOrganizer.letterOf("123 GO!"))
        assertEquals("H", LiveOrganizer.letterOf("¡Hola!"))
        assertEquals("#", LiveOrganizer.letterOf("Ñu TV"))
        assertEquals("AMC FHD", LiveOrganizer.feedLabel("US| AMC FHD"))
        assertEquals("AMC West", LiveOrganizer.feedLabel("AT&T: AMC West"))
    }

    @Test
    fun liveGamesHidesWnbaAndSoccerUntilAProfileChangesIt() {
        assertEquals(setOf(League.WNBA, League.MLS, League.EPL), League.hiddenFrom(null))
        assertEquals(emptySet<League>(), League.hiddenFrom(emptyList()))
        assertEquals(setOf(League.MLS), League.hiddenFrom(listOf("MLS", "XFL")))
    }
}
