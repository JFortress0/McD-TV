package com.mcd.tv.data

import com.mcd.tv.data.ProgressMerge.Rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conflict rules for one title's saved progress (ProgressMerge): latest wins, never regress, length never shrinks. */
class ProgressMergeTest {
    private val min = 60_000L
    private val runtime = 209 * min
    private val death = (34 * 60 + 50) * 1000L

    @Test
    fun nothingHereTakesTheRemote() {
        val r = Rec(0, 0, death, runtime, 100)
        assertEquals(r, ProgressMerge.merge(null, r))
    }

    @Test
    fun latestWinsEvenWhenItRewinds() {
        val tv = Rec(0, 0, 90 * min, runtime, 1_000)
        val web = Rec(0, 0, 20 * min, runtime, 50_000) // rewound on the phone, later
        assertEquals(web, ProgressMerge.merge(tv, web))
    }

    @Test
    fun anOlderRecordNeverReplacesANewerOne() {
        val tv = Rec(0, 0, death, runtime, 50_000)
        val stale = Rec(0, 0, 150 * min, runtime, 1_000) // an old message replayed after the TV was off
        assertEquals(tv, ProgressMerge.merge(tv, stale))
        assertFalse(ProgressMerge.changes(tv, stale))
        // The same save seen twice (clocks a little off): no change.
        assertFalse(ProgressMerge.changes(tv, tv.copy(at = tv.at + 800)))
    }

    @Test
    fun theLengthNeverShrinksToACutOffFiles() {
        // The phone saved the stop with the cut-off file's own length (a version before this fix).
        val tv = Rec(0, 0, 10 * min, runtime, 1_000)
        val web = Rec(0, 0, death, death, 50_000)
        val m = ProgressMerge.merge(tv, web)
        assertEquals(death, m.positionMs)
        assertEquals(runtime, m.durationMs)
        assertFalse(ContinueRules.finished(m.positionMs.toFloat() / m.durationMs)) // still in progress everywhere
        // The other way round too: a newer local record keeps the longer length it already knew.
        val m2 = ProgressMerge.merge(Rec(0, 0, death, death, 50_000), Rec(0, 0, 10 * min, runtime, 1_000))
        assertEquals(Rec(0, 0, death, runtime, 50_000), m2)
    }

    @Test
    fun anotherEpisodeKeepsItsOwnLength() {
        val e1 = Rec(1, 1, 40 * min, 58 * min, 1_000)
        val e2 = Rec(1, 2, 5 * min, 22 * min, 50_000) // a short episode, not a cut-off file
        assertEquals(e2, ProgressMerge.merge(e1, e2))
    }

    @Test
    fun aNormalLengthDifferenceIsKept() {
        // Two healthy files of different cuts: the winner's own length stays.
        val a = Rec(0, 0, 60 * min, runtime, 1_000)
        val b = Rec(0, 0, 61 * min, 199 * min, 50_000)
        assertEquals(b, ProgressMerge.merge(a, b))
        assertTrue(ProgressMerge.changes(a, b))
    }
}
