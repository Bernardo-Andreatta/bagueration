package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingTest {
    private fun words(text: String, w: WordTiming.Words) = (0 until w.size).map { text.substring(w.starts[it], w.ends[it]) }

    @Test
    fun findsWordsWithApostrophesAndHyphens() {
        val t = "“Don't,” she said — the north-south line was 1,000 miles."
        val w = WordTiming.estimate(t, 0, t.length, 3000)
        assertEquals(listOf("Don't", "she", "said", "the", "north-south", "line", "was", "1", "000", "miles"), words(t, w))
    }

    @Test
    fun endTimesAreMonotonicAndCoverTheWholePiece() {
        val t = "The morning was cool and still, and the town had not quite woken up."
        val w = WordTiming.estimate(t, 0, t.length, 4200, trailingPauseMs = 400)
        for (i in 1 until w.size) assertTrue("word $i", w.endMs[i] > w.endMs[i - 1])
        assertEquals(4200, w.endMs[w.size - 1])
        assertEquals(0, w.at(-5))
        assertEquals(0, w.at(0))
        assertEquals(w.size - 1, w.at(4200))
        assertEquals(w.size - 1, w.at(99_999))
        // every moment maps to the word whose end is the first past it
        for (ms in 0 until 4200 step 7) {
            val i = w.at(ms.toLong())
            assertTrue(w.endMs[i] > ms)
            if (i > 0) assertTrue(w.endMs[i - 1] <= ms)
        }
    }

    @Test
    fun aWordBeforeACommaKeepsThePause() {
        val t = "Well, now"
        val w = WordTiming.estimate(t, 0, t.length, 1000, commaPauseMs = 300)
        // "Well" and "now" weigh the same (4 letters, 3 letters + per-word cost differ slightly); the comma adds 300 ms to "Well"
        val well = w.endMs[0]
        val now = w.endMs[1] - w.endMs[0]
        assertTrue("well=$well now=$now", well - now in 300..400)
    }

    @Test
    fun longerWordsTakeLonger() {
        val t = "a extraordinarily"
        val w = WordTiming.estimate(t, 0, t.length, 1000)
        assertTrue(w.endMs[0] < 300)
        assertTrue(w.endMs[1] - w.endMs[0] > 700)
    }

    @Test
    fun trailingPauseGoesToTheLastWordOnly() {
        val t = "Yes."
        val w = WordTiming.estimate(t, 0, t.length, 700, trailingPauseMs = 400)
        assertEquals(1, w.size)
        assertEquals(700, w.endMs[0])
    }

    @Test
    fun pausesAreScaledDownWhenTheyWouldEatTheSpeech() {
        val t = "One. Two. Three. Four."
        val w = WordTiming.estimate(t, 0, t.length, 600, sentencePauseMs = 500)
        assertEquals(4, w.size)
        for (i in 1 until w.size) assertTrue(w.endMs[i] > w.endMs[i - 1])
        assertEquals(600, w.endMs[3])
    }

    @Test
    fun offsetsAreInTheBookNotThePiece() {
        val t = "Before. Inside the piece. After."
        val from = t.indexOf("Inside"); val until = t.indexOf(" After")
        val w = WordTiming.estimate(t, from, until, 1000)
        assertEquals(listOf("Inside", "the", "piece"), words(t, w))
        assertEquals(from, w.starts[0])
    }

    @Test
    fun emptyPiece() {
        val w = WordTiming.estimate("* * *", 0, 5, 1000)
        assertEquals(0, w.size)
        assertEquals(-1, w.at(10))
    }

    @Test
    fun estimateIsCheap() {
        val t = "The morning was cool and still, and the town had not quite woken up. ".repeat(5)
        WordTiming.estimate(t, 0, t.length, 20000)   // warm up
        val t0 = System.nanoTime()
        repeat(100) { WordTiming.estimate(t, 0, t.length, 20000) }
        val perCall = (System.nanoTime() - t0) / 100 / 1000
        assertTrue("$perCall us per 350-char piece", perCall < 2000)
    }
}
