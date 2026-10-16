package com.bernardo.ereader

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Makes every voice come out at the same loudness. Each Kokoro / Piper voice has its own level (and a blend sits
 * in between), so switching voice or playing a file made with another one used to change the volume.
 *
 * How it works, per generated chunk (after trimming and pause squeezing, before caching / writing to a file):
 *  1. [measure] the chunk's speech level: K-weighted (ITU-R BS.1770) RMS over 100 ms blocks, keeping only blocks
 *     that pass a simple EBU R128-style gate: an absolute gate at [GATE_DB] (silence, breath) and a relative gate
 *     [RELATIVE_GATE_DB] below the ungated mean (quiet tails). The result is the level of the voiced part.
 *  2. Fold it into the voice's running level ([VoiceLevel.update]): a running mean for the first [TAU_SEC] of
 *     voiced speech, then an exponential moving average with that time constant. The gain follows this long-term
 *     level, not each sentence, so an exclamation stays louder than a whisper.
 *  3. [gain] = [TARGET_DB] minus that level, limited to [MIN_GAIN]..[MAX_GAIN]; [apply] multiplies by it with a
 *     soft limiter above [KNEE] so peaks never clip.
 *
 * Mirrored in tools/tts-timing/loudness.py; keep them in step.
 */
object Loudness {
    /** Where every voice lands: K-weighted RMS of the voiced part, dBFS. */
    const val TARGET_DB = -20f
    const val MAX_GAIN = 3f
    const val MIN_GAIN = 0.5f
    /** Blocks quieter than this (dBFS, K-weighted) are not speech. */
    const val GATE_DB = -40f
    /** Blocks this far below the ungated mean are dropped too (soft tails, breath between words). */
    const val RELATIVE_GATE_DB = -10f
    /** Voiced seconds over which the running level settles (running mean up to here, then an EMA). */
    const val TAU_SEC = 30f
    /** A chunk needs this much voiced audio to count towards the voice's level. */
    const val MIN_VOICED_SEC = 0.3f
    /** Soft limiter knee: samples above this (after gain) are squashed smoothly towards 1. */
    const val KNEE = 0.85f
    private const val BLOCK_MS = 100

    /** The level of a chunk's voiced part ([db], K-weighted RMS dBFS) and how much of it was voiced. NaN db = nothing voiced. */
    class Measure(val db: Float, val voicedSec: Float)

    /**
     * The running level of one voice. [db] is NaN until something was measured; [sec] is the voiced time it rests on
     * (capped at [TAU_SEC]). Both go to prefs so the level survives a restart.
     */
    class VoiceLevel(var db: Float = Float.NaN, var sec: Float = 0f) {
        /** Folds in a chunk's measurement; returns true when the level changed. */
        fun update(m: Measure): Boolean {
            if (m.db.isNaN() || m.voicedSec < MIN_VOICED_SEC) return false
            if (db.isNaN()) { db = m.db; sec = min(m.voicedSec, TAU_SEC); return true }
            val w = min(1f, m.voicedSec / min(sec + m.voicedSec, TAU_SEC))
            db += w * (m.db - db)
            sec = min(sec + m.voicedSec, TAU_SEC)
            return true
        }

        /** Linear gain that brings this voice to [TARGET_DB]; 1 until the voice has been heard. */
        val gain: Float get() = if (db.isNaN()) 1f else gain(db)
    }

    fun gain(levelDb: Float): Float = 10f.pow((TARGET_DB - levelDb) / 20f).coerceIn(MIN_GAIN, MAX_GAIN)

    fun measure(x: FloatArray, rate: Int): Measure {
        val block = rate * BLOCK_MS / 1000
        val n = x.size / block
        if (n == 0) return Measure(Float.NaN, 0f)
        val y = kWeight(x, rate)
        val power = FloatArray(n) { b ->
            var s = 0.0
            for (i in b * block until (b + 1) * block) s += y[i].toDouble() * y[i]
            (s / block).toFloat()
        }
        val absGate = 10f.pow(GATE_DB / 10f)
        var sum = 0.0; var count = 0
        for (p in power) if (p >= absGate) { sum += p; count++ }
        if (count == 0) return Measure(Float.NaN, 0f)
        val relGate = (sum / count).toFloat() * 10f.pow(RELATIVE_GATE_DB / 10f)
        sum = 0.0; count = 0
        for (p in power) if (p >= absGate && p >= relGate) { sum += p; count++ }
        if (count == 0) return Measure(Float.NaN, 0f)
        return Measure(10f * log10((sum / count).toFloat()), count * BLOCK_MS / 1000f)
    }

    /** A new array: [x] times [g], peaks soft-limited so nothing clips. Gain 1 and no peaks over the knee = a copy. */
    fun apply(x: FloatArray, g: Float): FloatArray {
        val out = FloatArray(x.size)
        val span = 1f - KNEE
        for (i in x.indices) {
            val v = x[i] * g
            val a = abs(v)
            out[i] = if (a <= KNEE) v else (KNEE + span * tanh((a - KNEE) / span)) * (if (v < 0) -1f else 1f)
        }
        return out
    }

    /** Plain RMS in dBFS of the whole array (for logs and tests). */
    fun rmsDb(x: FloatArray): Float {
        if (x.isEmpty()) return Float.NEGATIVE_INFINITY
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return 20f * log10(sqrt(s / x.size).toFloat())
    }

    // ---------- K-weighting: BS.1770 pre-filter (high shelf) + RLB high-pass, coefficients for any sample rate ----------

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun shelf(rate: Int): Biquad {
        val f0 = 1681.974450955533; val gDb = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(Math.PI * f0 / rate)
        val vh = 10.0.pow(gDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun highPass(rate: Int): Biquad {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(Math.PI * f0 / rate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0 / a0, -2.0 / a0, 1.0 / a0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun run(f: Biquad, x: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val x0 = x[i].toDouble()
            val y0 = f.b0 * x0 + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2
            y[i] = y0.toFloat()
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
        }
        return y
    }

    fun kWeight(x: FloatArray, rate: Int): FloatArray = run(highPass(rate), run(shelf(rate), x))

}
