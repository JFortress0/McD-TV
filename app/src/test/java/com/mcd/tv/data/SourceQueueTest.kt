package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Try next source": which source the player tries after one fails. */
class SourceQueueTest {

    private fun src(title: String, url: String? = null, hash: String? = null, fileIdx: Int? = null) =
        StreamSource("Addon", "Addon 1080p", title, url, hash, fileIdx, "", "", 0.0, null, false)

    @Test
    fun nextIsTheFollowingSource() {
        val list = listOf(src("a", url = "http://x/a"), src("b", url = "http://x/b"), src("c", url = "http://x/c"))
        assertEquals(1, SourceQueue.next(list, 0))
        assertEquals(2, SourceQueue.next(list, 1))
    }

    @Test
    fun lastSourceHasNoNext() {
        val list = listOf(src("a", url = "http://x/a"), src("b", url = "http://x/b"))
        assertNull(SourceQueue.next(list, 1))
        assertEquals(0, SourceQueue.remaining(list, 1))
    }

    @Test
    fun emptyListHasNoNext() {
        assertNull(SourceQueue.next(emptyList(), -1))
        assertEquals(0, SourceQueue.remaining(emptyList(), -1))
    }

    @Test
    fun unknownPositionStartsAtTheTop() {
        val list = listOf(src("a", url = "http://x/a"), src("b", url = "http://x/b"))
        assertEquals(0, SourceQueue.next(list, -1))
        assertEquals(0, SourceQueue.next(list, -5))
    }

    @Test
    fun positionPastTheEndHasNoNext() {
        val list = listOf(src("a", url = "http://x/a"))
        assertNull(SourceQueue.next(list, 7))
    }

    @Test
    fun skipsTheSameLinkAgain() {
        val list = listOf(src("a", url = "http://x/same"), src("a copy", url = "http://x/same"), src("b", url = "http://x/b"))
        assertEquals(2, SourceQueue.next(list, 0))
    }

    @Test
    fun skipsTheSameTorrentFileButNotOtherFilesInIt() {
        val list = listOf(
            src("pack e1", hash = "ABCDEF", fileIdx = 1),
            src("pack e1 again", hash = "abcdef", fileIdx = 1),
            src("pack e2", hash = "abcdef", fileIdx = 2),
        )
        assertEquals(2, SourceQueue.next(list, 0))
    }

    @Test
    fun skipsDuplicatesOfAnySourceAlreadyPassed() {
        val list = listOf(
            src("a", url = "http://x/a"),
            src("b", url = "http://x/b"),
            src("a again", url = "http://x/a"),
            src("c", url = "http://x/c"),
        )
        assertEquals(3, SourceQueue.next(list, 1))
    }

    @Test
    fun remainingCountsWhatIsLeftWithoutDuplicates() {
        val list = listOf(
            src("a", url = "http://x/a"),
            src("b", url = "http://x/b"),
            src("b again", url = "http://x/b"),
            src("c", url = "http://x/c"),
        )
        assertEquals(2, SourceQueue.remaining(list, 0))
        assertEquals(3, SourceQueue.remaining(list, -1))
    }

    @Test
    fun walkingTheListEndsAfterEverySourceOnce() {
        val list = (1..20).map { src("s$it", url = "http://x/$it") }
        var i = -1
        var steps = 0
        while (true) {
            i = SourceQueue.next(list, i) ?: break
            steps++
            assertTrue("never loops", steps <= list.size)
        }
        assertEquals(list.size, steps)
    }

    @Test
    fun storesListsByIdAndKeepsOnlyTheLatest() {
        val first = listOf(src("a", url = "http://x/a"))
        val id = SourceQueue.put(first)
        assertTrue(id > 0)
        assertSame(first, SourceQueue.get(id))
        assertNull(SourceQueue.get(0))
        repeat(10) { SourceQueue.put(listOf(src("n$it", url = "http://x/n$it"))) }
        assertNull("old lists are dropped", SourceQueue.get(id))
    }
}
