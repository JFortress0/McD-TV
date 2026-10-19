package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The TV side of the replay in tools/webtest/interchange.mjs. That run plays The Irishman on the web app with a
 * real video: file A dies at 34:50, the web app saves the spot and flags A (and C, whose length is 34:50), then
 * resumes on B. It writes what it stored for the house to tools/webtest/interchange-fixture.json. Here the TV
 * takes those exact records: it must resume within 5 seconds of the web's spot, on a healthy file.
 */
class InterchangeFixtureTest {
    private val fx: JSONObject = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "tools/webtest/interchange-fixture.json").exists()) dir = dir.parentFile
        JSONObject(File(dir ?: error("interchange-fixture.json not found"), "tools/webtest/interchange-fixture.json").readText())
    }
    private val runtimeMs = fx.getJSONObject("title").getInt("runtimeMin") * 60_000L
    private val cutAtMs = fx.getLong("cutAtMs")
    private val webRec = fx.getJSONObject("resume")
    private val flags = SourceHealth.decode(JSONObject().put("f", fx.getJSONArray("flags")).toString())

    private data class Src(val source: StreamSource, val group: String, val lengthMs: Long)

    /** The fixture's sources as the TV's addon gives them: by torrent hash, or as a Real-Debrid link with the hash in it. */
    private fun sources(asLinks: Boolean): List<Src> {
        val a = fx.getJSONArray("sources")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val hash = o.getString("infoHash")
            val s = if (asLinks) StreamSource("QA Addon", o.getString("name"), o.getString("title"), "https://addon.test/resolve/realdebrid/KEY/$hash/null/0/x.mp4", null, null, "", "", 0.0, null, true)
            else StreamSource("QA Addon", o.getString("name"), o.getString("title"), null, hash, o.getInt("fileIdx"), "", "", 0.0, null, true)
            Src(s, Regex("GRP(\\w)").find(o.getString("title"))!!.groupValues[1], o.getLong("rdLengthS") * 1000)
        }
    }

    /** What the TV does with the house's records: merge the web's spot into its own, pick a file, pick the start. */
    private fun tvResume(local: ProgressMerge.Rec?, list: List<Src>): Pair<Src, Long> {
        val remote = ProgressMerge.Rec(webRec.getInt("season"), webRec.getInt("episode"), webRec.getLong("pos"), webRec.getLong("dur"), 2_000_000L)
        val saved = ProgressMerge.merge(local, remote)
        val scores = flags.associate { it.key to it.n }
        // Play best source order, flagged files last; then the player's own check of each file's length.
        val pick = SourceHealth.demote(list, scores) { SourceHealth.key(it.source) }
            .first { !SourceHealth.shortSource(it.lengthMs, SourceHealth.expectedMs(0L, runtimeMs, saved.durationMs)) }
        val start = PlaybackRescue.resumeTarget(saved.positionMs, SourceHealth.expectedMs(pick.lengthMs, runtimeMs, saved.durationMs))
        return pick to start
    }

    @Test
    fun webRecordsAreTheTitlesNotTheCutOffFiles() {
        assertTrue(kotlin.math.abs(webRec.getLong("pos") - cutAtMs) <= 5_000)
        assertEquals(runtimeMs, webRec.getLong("dur")) // saved with 3:29:00, not the file's 34:50
        assertTrue(!ContinueRules.finished(webRec.getLong("pos").toFloat() / webRec.getLong("dur")))
        assertEquals(setOf("A", "C"), sources(false).filter { SourceHealth.key(it.source) in flags.map { f -> f.key } }.map { it.group }.toSet())
    }

    @Test
    fun tvResumesTheWebSpotWithinFiveSecondsOnAHealthyFile() {
        // The TV last played this yesterday at 20:00; the web's later save wins.
        val tvYesterday = ProgressMerge.Rec(0, 0, 20 * 60_000L, runtimeMs, 1_000L)
        for (asLinks in listOf(false, true)) {
            val (pick, start) = tvResume(tvYesterday, sources(asLinks))
            assertEquals("B", pick.group)
            assertTrue("start $start vs web $cutAtMs", kotlin.math.abs(start - webRec.getLong("pos")) <= 5_000)
            assertTrue(kotlin.math.abs(start - cutAtMs) <= 5_000)
        }
    }

    @Test
    fun positionDoesNotDependOnWhichFileIsPicked() {
        // Every order the addons could return, with and without the house's flags: the file changes, the spot never does.
        val all = sources(false)
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val starts = HashSet<Long>()
        for (o in orders) {
            val list = o.map { all[it] }
            val (pick, start) = tvResume(null, list)
            assertEquals("B", pick.group)
            starts += start
            // A file picked by hand, flagged or not, starts at the same spot too.
            for (s in list) starts += PlaybackRescue.resumeTarget(webRec.getLong("pos"), SourceHealth.expectedMs(s.lengthMs, runtimeMs, webRec.getLong("dur")))
        }
        assertEquals(setOf(webRec.getLong("pos")), starts)
    }

    @Test
    fun aFreshTvWithoutTheFlagsFindsTheBreakItselfAndKeepsTheSpot() {
        // No house flags yet (another house member's first try): A ranks first and plays, then ends at 34:50.
        val a = sources(false).first { it.group == "A" }
        val stop = cutAtMs
        assertTrue(SourceHealth.incomplete(stop, SourceHealth.expectedMs(a.lengthMs, runtimeMs)))
        // What it saves: the stop with the title's length. Then it reports A, and the next pick is healthy at the same spot.
        assertEquals(runtimeMs, SourceHealth.progressDurationMs(a.lengthMs, runtimeMs))
        val reported = SourceHealth.report(emptyList(), SourceHealth.key(a.source), stop, 1L).associate { it.key to it.n }
        val next = SourceHealth.demote(sources(false), reported) { SourceHealth.key(it.source) }
            .first { it.group != "A" && !SourceHealth.shortSource(it.lengthMs, runtimeMs) }
        assertNotEquals("A", next.group)
        assertEquals(stop, PlaybackRescue.resumeTarget(stop, SourceHealth.expectedMs(next.lengthMs, runtimeMs, runtimeMs)))
    }
}
