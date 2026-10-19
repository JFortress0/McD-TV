package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cut-off sources and watching on several devices. The web app has the same rules (docs/app/index.html, SH),
 * checked with the same cases by tools/webtest/qa.mjs ("source health" step). Keep both in step.
 */
class SourceHealthTest {
    private val min = 60_000L
    /** The Irishman: 209 minutes. The broken file stops at 34:50. */
    private val runtime = 209 * min
    private val death = (34 * 60 + 50) * 1000L
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)

    private fun src(title: String, url: String? = null, hash: String? = null, fileIdx: Int? = null) =
        StreamSource("Torrentio", "Torrentio 1080p", title, url, hash, fileIdx, "", "", 0.0, null, true)

    // ------------------------------------------------------------- keys

    @Test
    fun sameFileHasTheSameKeyOnEveryDevice() {
        // One device gets the torrent hash, another the addon's Real-Debrid link with the hash in its path.
        val asHash = src("The Irishman 1080p", hash = hashA.uppercase(), fileIdx = 0)
        val asLink = src("The Irishman 1080p", url = "https://torrentio.strem.fun/realdebrid/SECRETTOKEN/$hashA/null/0/The.Irishman.mkv")
        assertEquals("h:$hashA", SourceHealth.key(asHash))
        assertEquals(SourceHealth.key(asHash), SourceHealth.key(asLink))
        assertNotEquals(SourceHealth.key(asHash), SourceHealth.key(src("x", hash = hashB)))
    }

    @Test
    fun plainLinksAreHashedSoNoLoginIsStored() {
        val k = SourceHealth.key(src("x", url = "http://iptv.example/movie/user/pass/123.mkv"))
        assertTrue(k.startsWith("u:"))
        assertEquals(18, k.length)
        assertFalse(k.contains("pass"))
        // The web app gets this exact key from the same link (sha-256, first 16 hex).
        assertEquals("u:895c204c8f5b450e", SourceHealth.key(src("x", url = "http://example.com/a.mkv")))
    }

    @Test
    fun hashOnlyFromTheLinkPath() {
        assertEquals(hashA, SourceHealth.hashInLink("https://x.io/rd/$hashA/1/"))
        assertEquals(null, SourceHealth.hashInLink("https://x.io/play?h=$hashA"))
        assertEquals(null, SourceHealth.hashInLink("not a link"))
    }

    @Test
    fun titleKeyIsPerEpisode() {
        assertEquals("movie:398978", SourceHealth.titleKey("movie", 398978))
        assertEquals("tv:1399:1:2", SourceHealth.titleKey("tv", 1399, 1, 2))
    }

    // ------------------------------------------------------------- incomplete

    @Test
    fun stopFarShortOfTheRuntimeIsIncomplete() {
        assertTrue(SourceHealth.incomplete(death, SourceHealth.expectedMs(death, runtime)))
        // The file says it is full length but dies at 34:50 (a stall that never recovers).
        assertTrue(SourceHealth.incomplete(death, SourceHealth.expectedMs(runtime, 0L)))
        assertTrue(SourceHealth.shortSource(death, runtime))
    }

    @Test
    fun normalEndsAreNotFlagged() {
        assertFalse(SourceHealth.incomplete(runtime - 4 * min, runtime)) // credits cut
        assertFalse(SourceHealth.incomplete(195 * min, runtime)) // a shorter cut of the film
        assertFalse(SourceHealth.incomplete(22 * min, 30 * min)) // an episode with a loose runtime
        assertFalse(SourceHealth.incomplete(death, 0L)) // length unknown: never guess
    }

    @Test
    fun savedLengthIsTheTitlesNotTheCutOffSources() {
        // The cut-off file reports 34:50 as its length. Saving that would read as "watched" everywhere.
        assertEquals(runtime, SourceHealth.progressDurationMs(death, runtime))
        // A normal source keeps its own length.
        assertEquals(runtime - min, SourceHealth.progressDurationMs(runtime - min, runtime))
        // No runtime yet, but another device saved the real length.
        assertEquals(runtime, SourceHealth.progressDurationMs(death, 0L, runtime))
        assertEquals(death, SourceHealth.progressDurationMs(death, 0L, 0L))
    }

    // ------------------------------------------------------------- flags

    @Test
    fun flagsMergeOnePerFileNewestFirst() {
        val now = 100 * 86_400_000L
        val a = listOf(SourceHealth.Flag("h:a", death, now - 10), SourceHealth.Flag("h:old", 1, now - SourceHealth.KEEP_MS - 1))
        val b = listOf(SourceHealth.Flag("h:a", death + 1, now - 5), SourceHealth.Flag("h:b", 5, now - 20))
        val m = SourceHealth.merge(a, b, now)
        assertEquals(listOf("h:a", "h:b"), m.map { it.key })
        assertEquals(death + 1, m[0].stoppedMs)
        val many = (1..20).map { SourceHealth.Flag("h:$it", 0, now - it) }
        assertEquals(SourceHealth.MAX_FLAGS, SourceHealth.merge(many, emptyList(), now).size)
    }

    @Test
    fun flagJsonMatchesTheWebApp() {
        // Written by the web app (SH.encode): the TV reads it, and the TV's own JSON reads back the same.
        val web = """{"f":[{"k":"h:$hashA","s":$death,"at":1760000000000}],"at":1760000000000}"""
        val flags = SourceHealth.decode(web)
        assertEquals(listOf(SourceHealth.Flag("h:$hashA", death, 1760000000000L)), flags)
        assertEquals(flags, SourceHealth.decode(SourceHealth.encode(flags, 1L)))
        assertEquals(emptyList<SourceHealth.Flag>(), SourceHealth.decode("not json"))
    }

    @Test
    fun flaggedFilesGoLast() {
        val list = listOf(src("A", hash = hashA), src("B", hash = hashB), src("C", url = "http://c"))
        val out = SourceHealth.demote(list, setOf("h:$hashA"), SourceHealth::key)
        assertEquals(listOf("B", "C", "A"), out.map { it.title })
        assertEquals(list, SourceHealth.demote(list, emptySet(), SourceHealth::key))
    }

    // ------------------------------------------------------------- watching on several devices

    /** One saved position as every device stores it: pos and dur in ms. */
    private data class Saved(val pos: Long, val dur: Long) {
        val progress get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    /**
     * A device stops playback: what it saves and whether it flags the file. [forGood]: the source ended or
     * stalled for good there (a pause or Back is not).
     */
    private fun stop(atMs: Long, sourceDurMs: Long, flags: MutableList<SourceHealth.Flag>, source: StreamSource, forGood: Boolean): Saved {
        val expected = SourceHealth.expectedMs(sourceDurMs, runtime)
        if (forGood && SourceHealth.incomplete(atMs, expected)) {
            flags += SourceHealth.Flag(SourceHealth.key(source), atMs, 1L)
        }
        return Saved(atMs, SourceHealth.progressDurationMs(sourceDurMs, runtime))
    }

    @Test
    fun webStopsAtTheBreakTvResumesThereOnAnotherSource() {
        val broken = src("The Irishman 1080p", url = "https://torrentio.strem.fun/realdebrid/TOKEN/$hashA/null/0/x.mkv")
        val good = src("The Irishman 720p", hash = hashB)
        val shared = mutableListOf<SourceHealth.Flag>()

        // Web app: the cut-off file ends at 34:50 (its own length).
        val saved = stop(death, death, shared, broken, forGood = true)
        assertEquals(death, saved.pos)
        assertEquals(runtime, saved.dur)
        assertFalse(ContinueRules.finished(saved.progress)) // still in Continue Watching, not "watched"
        assertTrue(ContinueRules.keep("movie", 0, 0, saved.pos, saved.progress, 1L, 2L))

        // Cloud: flag JSON goes through as the web wrote it.
        val onTv = SourceHealth.decode(SourceHealth.encode(shared, 2L))
        val flagged = onTv.map { it.key }.toSet()

        // TV: the same torrent comes as a hash here. It ranks first, but the flag puts it last.
        val tvList = listOf(src("The Irishman 1080p", hash = hashA), good)
        val order = SourceHealth.demote(tvList, flagged, SourceHealth::key)
        assertEquals(good, order.first())

        // The position is the title's: the good source starts at 34:50.
        val start = PlaybackRescue.resumeTarget(saved.pos, SourceHealth.expectedMs(runtime, runtime, saved.dur))
        assertEquals(death, start)
    }

    @Test
    fun tvStallsAtTheBreakWebSkipsTheFile() {
        // TV: the file says 3:29:00 but stops for good at 34:50. TV flags it and saves 34:50 of 3:29:00.
        val broken = src("x", hash = hashA)
        val shared = mutableListOf<SourceHealth.Flag>()
        val saved = stop(death, runtime, shared, broken, forGood = true)
        assertEquals(Saved(death, runtime), saved)
        assertEquals(listOf("h:$hashA"), shared.map { it.key })
        // Web: the same torrent as a Real-Debrid link is the same file: listed last.
        val webBroken = src("x", url = "https://addon.example/rd/$hashA/1/x.mkv")
        val order = SourceHealth.demote(listOf(webBroken, src("y", hash = hashB)), shared.map { it.key }.toSet(), SourceHealth::key)
        assertEquals("y", order.first().title)
    }

    @Test
    fun goodSourceEverywhereNeedsNoFlag() {
        val shared = mutableListOf<SourceHealth.Flag>()
        // Web pauses at 1:10:00 on a full-length file; TV resumes there with whatever source it picks.
        val saved = stop(70 * min, runtime, shared, src("x", hash = hashA), forGood = false)
        assertTrue(shared.isEmpty())
        assertEquals(70 * min, PlaybackRescue.resumeTarget(saved.pos, SourceHealth.expectedMs(runtime - min, runtime, saved.dur)))
        // A spot at the very end of the title starts over.
        assertEquals(0L, PlaybackRescue.resumeTarget(runtime - 5_000, SourceHealth.expectedMs(runtime, runtime)))
    }
}
