package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessTest {
    private val rate = 24000

    /** A sine at [hz] whose RMS is [rmsDb] dBFS, [sec] long. */
    private fun sine(rmsDb: Float, sec: Float, hz: Float = 1000f): FloatArray {
        val amp = Math.pow(10.0, rmsDb / 20.0) * Math.sqrt(2.0)
        return FloatArray((rate * sec).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
    }

    private fun padded(x: FloatArray, silenceSec: Float): FloatArray {
        val z = FloatArray((rate * silenceSec).toInt())
        return z + x + z
    }

    @Test
    fun measuresKWeightedRms() {
        // K-weighting is close to flat at 1 kHz (the shelf starts around 1.5 kHz)
        val m = Loudness.measure(sine(-20f, 2f), rate)
        assertEquals(-20f, m.db, 1.5f)
        assertEquals(2f, m.voicedSec, 0.15f)
        val quiet = Loudness.measure(sine(-32f, 2f), rate)
        assertEquals(-32f, quiet.db, 1.5f)
    }

    @Test
    fun gateIgnoresSilenceAroundSpeech() {
        val bare = Loudness.measure(sine(-23f, 1.5f), rate)
        val m = Loudness.measure(padded(sine(-23f, 1.5f), 2f), rate)
        assertEquals(bare.db, m.db, 0.3f)
        assertEquals(1.5f, m.voicedSec, 0.15f)
    }

    @Test
    fun relativeGateDropsQuietTails() {
        // 1 s of speech followed by 1 s of a tail 20 dB down: the tail is above the absolute gate but not the relative one
        val x = sine(-20f, 1f) + sine(-40f + 1f, 1f)
        val m = Loudness.measure(x, rate)
        assertEquals(-20f, m.db, 1.5f)
        assertEquals(1f, m.voicedSec, 0.15f)
    }

    @Test
    fun silenceIsUnvoiced() {
        val m = Loudness.measure(FloatArray(rate), rate)
        assertTrue(m.db.isNaN())
        assertEquals(0f, m.voicedSec, 0f)
        assertEquals(1f, Loudness.VoiceLevel().gain, 0f)
    }

    @Test
    fun gainAimsAtTargetWithinLimits() {
        assertEquals(1f, Loudness.gain(Loudness.TARGET_DB), 1e-4f)
        assertEquals(2f, Loudness.gain(Loudness.TARGET_DB - 6.0206f), 1e-3f)
        assertEquals(Loudness.MAX_GAIN, Loudness.gain(-45f), 0f)
        assertEquals(Loudness.MIN_GAIN, Loudness.gain(-3f), 0f)
    }

    @Test
    fun applyScalesAndSoftLimits() {
        val x = floatArrayOf(0.1f, -0.1f, 0.5f, -0.9f, 0.9f)
        val y = Loudness.apply(x, 2.5f)
        assertEquals(0.25f, y[0], 1e-6f)
        assertEquals(-0.25f, y[1], 1e-6f)
        for (v in y) assertTrue("clipped: $v", abs(v) <= 1f)
        assertTrue(y[4] > Loudness.KNEE)
        assertEquals(-y[4], y[3], 1e-6f)
        // gain 1 and nothing over the knee: untouched
        val same = Loudness.apply(floatArrayOf(0.2f, -0.6f), 1f)
        assertEquals(0.2f, same[0], 0f); assertEquals(-0.6f, same[1], 0f)
    }

    @Test
    fun voiceLevelFollowsLongTermNotEachSentence() {
        val lv = Loudness.VoiceLevel()
        // first chunk sets the level outright
        assertTrue(lv.update(Loudness.Measure(-30f, 3f)))
        assertEquals(-30f, lv.db, 1e-4f)
        // 30 s of -30 dB speech: still -30
        repeat(9) { lv.update(Loudness.Measure(-30f, 3f)) }
        assertEquals(-30f, lv.db, 1e-3f)
        assertEquals(Loudness.TAU_SEC, lv.sec, 1e-3f)
        // one 3 s exclamation 12 dB louder moves the voice's level by only a tenth of that
        lv.update(Loudness.Measure(-18f, 3f))
        assertEquals(-28.8f, lv.db, 0.05f)
        assertTrue(lv.gain > 2.5f)
        // too little voiced audio: ignored
        val before = lv.db
        assertFalse(lv.update(Loudness.Measure(-5f, 0.2f)))
        assertFalse(lv.update(Loudness.Measure(Float.NaN, 0f)))
        assertEquals(before, lv.db, 0f)
    }

    @Test
    fun voiceLevelIsRunningMeanAtFirst() {
        val lv = Loudness.VoiceLevel()
        lv.update(Loudness.Measure(-20f, 2f))
        lv.update(Loudness.Measure(-26f, 2f))
        assertEquals(-23f, lv.db, 1e-3f)
        lv.update(Loudness.Measure(-26f, 4f))
        assertEquals(-24.5f, lv.db, 1e-3f)
    }

    @Test
    fun twoVoicesEndUpAtTheSameLevel() {
        for (db in listOf(-14f, -24f, -31f)) {
            val x = padded(sine(db, 2f), 0.5f)
            val lv = Loudness.VoiceLevel()
            lv.update(Loudness.measure(x, rate))
            val y = Loudness.apply(x, lv.gain)
            assertEquals(Loudness.TARGET_DB, Loudness.measure(y, rate).db, 1.5f)
        }
    }
}
