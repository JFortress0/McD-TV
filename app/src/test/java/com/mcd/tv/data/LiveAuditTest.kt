package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The audit of PR #7 (October 2026), kept as a test: what the old Live TV rail showed for the stand-in playlist
 * (tools/livehome/fixture), and why its network grouping did not bring the counts down. Prints the breakdown.
 */
class LiveAuditTest {
    private fun fixture(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/livehome/fixture/playlist.m3u.gz")
            if (f.isFile) return GZIPInputStream(f.inputStream()).bufferedReader().readText()
            dir = dir.parentFile
        }
        throw AssertionError("fixture not found")
    }

    @Test
    fun pr7RailCountsChannelsNotNetworks() {
        val idx = LiveOrganizer.build(M3u.parse(fixture()))
        val sports = idx.sections.first { it.name == LiveOrganizer.SPORTS }
        val ent = idx.sections.first { it.name == LiveOrganizer.ENTERTAINMENT }
        // The rail entry shows LiveSection.count, the channel count. Sports has no network grouping at all.
        assertEquals(1790, sports.count)
        assertEquals(0, sports.networks.size)
        // Entertainment's pane groups by network, but its rail entry still shows 2,076, and the pane lists 682 rows.
        assertEquals(2076, ent.count)
        assertEquals(682, ent.networks.size)

        // What fills them, by what each channel really is (LiveHome.identify).
        fun kinds(items: IntArray): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (i in items) {
                val ch = idx.channels[i]
                val c = LiveOrganizer.country(ch.group, ch.name)
                val id = LiveHome.identify(ch.name, ch.group, c, LiveOrganizer.section(ch.group, ch.name, c))
                val k = when {
                    id.key.startsWith("intl:") -> "international"
                    id.key.startsWith("ev:") -> "numbered event channels"
                    id.key.startsWith("fast:") -> "free ad-supported channels"
                    id.net != null -> "catalog networks (" + (if (ch.name.contains(':') || ch.name.contains('(') || ch.name.contains('[')) "mirror copies)" else "own feeds)")
                    else -> "other"
                }
                out[k] = (out[k] ?: 0) + 1
            }
            return out
        }
        println("AUDIT Sports ${sports.count}: ${kinds(sports.items)}")
        println("AUDIT Entertainment ${ent.count}: ${kinds(ent.items)}")
        // Entertainment rows that are one network under two names: the old key kept provider tags at the end.
        val keys = ent.networks.map { it.key }
        val tagged = keys.count { Regex("\\((HULU|PHILO)\\)$|\\[PHILO\\]$|HULU$|PHILO$").containsMatchIn(it) }
        val single = ent.networks.count { it.feeds.size == 1 }
        println("AUDIT Entertainment rows: ${keys.size}; rows with one feed: $single; rows that are a provider-tagged copy: $tagged")
        for (s in idx.sections) println("AUDIT section ${s.name}: ${s.count}")
    }
}
