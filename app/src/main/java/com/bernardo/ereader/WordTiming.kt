package com.bernardo.ereader

/**
 * Where each word of a piece of text falls in its audio, estimated: the voice gives no word timestamps, but the
 * piece's length is known once it is generated. Its duration (minus the pauses at punctuation) is shared out
 * over the words by character count, with a fixed cost per word so short words aren't instant.
 */
object WordTiming {

    /**
     * Words of a piece, in book offsets: word i is [starts[i], ends[i]) and is heard until [endMs][i] from the
     * piece's first sample (its following pause included, so the mark stays on it through the pause).
     */
    class Words(val starts: IntArray, val ends: IntArray, val endMs: IntArray) {
        val size get() = starts.size

        /** Index of the word heard at [ms] (the last one once past the end; -1 for an empty piece). */
        fun at(ms: Long): Int {
            if (endMs.isEmpty()) return -1
            var lo = 0; var hi = endMs.size - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (endMs[mid] > ms) hi = mid else lo = mid + 1 }
            return lo
        }
    }

    private val EMPTY = Words(IntArray(0), IntArray(0), IntArray(0))

    /** Time a word costs on top of its letters, in letter units ("a" is not 5x quicker than "about"). */
    private const val WORD_COST = 1.5f
    /** Digits are read out as words ("1914" = "nineteen fourteen"). */
    private const val DIGIT_WEIGHT = 2.5f
    /** Non-Latin letters without vowel groups (CJK) are a syllable each. */
    private const val CJK_WEIGHT = 2f
    private const val CLOSERS = "\"'”’»)]"

    /**
     * Estimates the words of [text] in [from, until) when that stretch plays for [totalMs] (speech plus the pause
     * after it, [trailingPauseMs]). Punctuation inside the stretch takes [commaPauseMs] (, ; : — …) or
     * [sentencePauseMs] (. ! ?) out of the speech time before the rest is shared by weight.
     */
    fun estimate(text: CharSequence, from: Int, until: Int, totalMs: Int, trailingPauseMs: Int = 0,
                 commaPauseMs: Int = 120, sentencePauseMs: Int = 250): Words {
        val a = from.coerceIn(0, text.length)
        val b = until.coerceIn(a, text.length)
        // pass 1: count words
        var n = 0
        var i = a
        while (i < b) {
            if (isWordChar(text[i])) { n++; i = wordEnd(text, i, b) } else i++
        }
        if (n == 0) return EMPTY
        val starts = IntArray(n); val ends = IntArray(n)
        val weight = FloatArray(n); val pause = IntArray(n)
        var k = 0
        i = a
        var pauseTotal = 0
        while (i < b) {
            if (!isWordChar(text[i])) { i++; continue }
            val e = wordEnd(text, i, b)
            starts[k] = i; ends[k] = e
            var w = WORD_COST
            for (j in i until e) {
                val c = text[j]
                w += when {
                    c.isDigit() -> DIGIT_WEIGHT
                    c.isLetter() && !c.isLatinLike() -> CJK_WEIGHT
                    c.isLetter() -> 1f
                    else -> 0.2f   // apostrophes, hyphens inside a word
                }
            }
            weight[k] = w
            // the mark after the word (closing quotes skipped) decides its pause; the last word's pause is the
            // piece's own trailing silence, whatever follows it in the text
            if (k < n - 1) {
                var j = e
                while (j < b && text[j] in CLOSERS) j++
                pause[k] = when (text.getOrNull(j)) {
                    '.', '!', '?' -> sentencePauseMs
                    ',', ';', ':', '—', '–', '…' -> commaPauseMs
                    '-' -> if (text.getOrNull(j + 1) == '-') commaPauseMs else 0
                    else -> 0
                }
                pauseTotal += pause[k]
            }
            k++
            i = e
        }
        pause[n - 1] = trailingPauseMs.coerceAtLeast(0)
        pauseTotal += pause[n - 1]
        // pauses can't eat more than half the speech: scale them down when the estimate overshoots
        var pauseScale = 1f
        val speech = totalMs - pauseTotal
        if (pauseTotal > 0 && speech < totalMs / 2) pauseScale = (totalMs / 2f) / pauseTotal
        var weightTotal = 0f
        for (x in weight) weightTotal += x
        val perUnit = (totalMs - pauseTotal * pauseScale) / weightTotal
        val endMs = IntArray(n)
        var t = 0f
        for (j in 0 until n) {
            t += weight[j] * perUnit + pause[j] * pauseScale
            endMs[j] = t.toInt()
        }
        endMs[n - 1] = totalMs.coerceAtLeast(endMs[n - 1])
        return Words(starts, ends, endMs)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit()

    /** End of the word starting at [i]: letters and digits, with apostrophes and hyphens between them ("don't", "north-south"). */
    private fun wordEnd(text: CharSequence, i: Int, limit: Int): Int {
        var j = i + 1
        while (j < limit) {
            val c = text[j]
            if (c.isLetterOrDigit()) { j++; continue }
            if ((c == '\'' || c == '’' || c == '-') && j + 1 < limit && text[j + 1].isLetterOrDigit()) { j++; continue }
            break
        }
        return j
    }

    private fun Char.isLatinLike(): Boolean {
        val b = Character.UnicodeBlock.of(this)
        return b == Character.UnicodeBlock.BASIC_LATIN || b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
            b == Character.UnicodeBlock.LATIN_EXTENDED_A || b == Character.UnicodeBlock.LATIN_EXTENDED_B ||
            b == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL || b == Character.UnicodeBlock.GREEK ||
            b == Character.UnicodeBlock.CYRILLIC
    }
}
