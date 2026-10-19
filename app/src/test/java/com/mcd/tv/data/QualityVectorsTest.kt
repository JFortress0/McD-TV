package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs every case in tools/qf-vectors.json, the quality fallback spec shared with the web app. */
class QualityVectorsTest {

    private fun vectors(): JSONObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/qf-vectors.json")
            if (f.isFile) return JSONObject(f.readText())
            dir = dir.parentFile
        }
        throw AssertionError("tools/qf-vectors.json not found above " + System.getProperty("user.dir"))
    }

    @Test
    fun constantsMatchTheKotlin() {
        val c = vectors().getJSONObject("constants")
        assertEquals(c.getLong("windowMs"), StallWatch.WINDOW_MS)
        assertEquals(c.getInt("stalls"), StallWatch.MAX_STALLS)
        assertEquals(c.getLong("totalMs"), StallWatch.MAX_STALLED_MS)
        assertEquals(c.getLong("longMs"), StallWatch.LONG_STALL_MS)
        assertEquals(c.getLong("graceMs"), StallWatch.STARTUP_MS)
        assertEquals(c.getLong("gapMs"), StallWatch.SAMPLE_GAP_MS)
        assertEquals(c.getInt("lowSamples"), StallWatch.LOW_SAMPLES)
        assertEquals(c.getInt("severeSamples"), StallWatch.SEVERE_SAMPLES)
        assertEquals(c.optDouble("severeRatio"), StallWatch.SEVERE_RATIO, 1e-9)
        assertEquals(c.optDouble("resetRatio"), StallWatch.RESET_RATIO, 1e-9)
        assertEquals(c.optDouble("defaultBitrate"), QualityFallback.DEFAULT_BITRATE, 1e-9)
        assertEquals(c.getLong("freshMs"), QualityFallback.FRESH_MS)
        assertEquals(c.getLong("memoryMs"), QualityFallback.MEMORY_MS)
        assertEquals(c.optDouble("upRatio"), QualityFallback.UP_RATIO, 1e-9)
        assertEquals(c.optDouble("clearRatio"), QualityFallback.CLEAR_RATIO, 1e-9)
    }

    @Test
    fun everyCase() {
        val cases = vectors().getJSONArray("cases")
        var ran = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            when (val kind = c.getString("kind")) {
                "monitor" -> monitor(name, c.getLong("t0"), c.getJSONArray("ops"))
                "estimator" -> {
                    val e = ThroughputEstimator()
                    val adds = c.getJSONArray("adds")
                    for (j in 0 until adds.length()) adds.getJSONArray(j).let { e.sample(it.getLong(1), it.getLong(0)) }
                    val want = c.optDouble("expect")
                    assertTrue("$name: got ${e.estimate()}, want $want", Math.abs(Math.round(e.estimate()).toDouble() - want) <= 1.0)
                }
                "startTier" -> {
                    val a = c.getJSONObject("in")
                    val o = c.getJSONObject("out")
                    val got = QualityFallback.startTier(a.getLong("now"), a.optDouble("est"), a.getLong("estAt"), a.optDouble("br"), a.getLong("down"))
                    assertEquals(name, QualityFallback.StartPick(o.getString("tier"), o.getString("why"), o.getBoolean("clear")), got)
                }
                else -> throw AssertionError("$name: unknown kind $kind")
            }
            ran++
        }
        assertEquals(cases.length(), ran)
        assertTrue(ran >= 27)
    }

    private fun monitor(name: String, t0: Long, ops: JSONArray) {
        val w = StallWatch(t0)
        for (j in 0 until ops.length()) {
            val op = ops.getJSONArray(j)
            when (op.getString(0)) {
                "stall" -> w.stall(op.getLong(1))
                "resume" -> w.resume(op.getLong(1))
                "cancel" -> w.cancel()
                "restart" -> w.restart(op.getLong(1))
                "sample" -> w.sample(op.getLong(1), op.optDouble(2), op.optDouble(3))
                "check" -> assertEquals("$name, op $j", op.getString(2), w.check(op.getLong(1)))
                else -> throw AssertionError("$name: unknown op ${op.getString(0)}")
            }
        }
    }
}
