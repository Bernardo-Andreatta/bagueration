package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchTest {
    private val text = "Você não sabe. VOCE sabe? O Coração de Lumian. lumian's coat."

    @Test fun caseAndAccentInsensitive() {
        val s = BookSearch(text)
        assertEquals(2, s.find("voce").size)
        assertEquals(1, s.find("coracao").size)
        assertEquals(2, s.find("LUMIAN").size)
        val hit = s.find("coração").single()
        assertEquals("Coração", text.substring(hit.start, hit.end))
    }

    @Test fun rangeAndSnippet() {
        val s = BookSearch(text)
        assertEquals(1, s.find("lumian", from = 0, to = 46).size)
        val (snip, a, b) = s.snippet(s.find("Coração").single(), around = 8)
        assertEquals("Coração", snip.substring(a, b))
    }

    @Test fun fastOnBigBook() {
        val big = buildString { repeat(200_000) { append("The rain had stopped and the streets were still wet. Klein walked home. ") } }  // ~15M chars
        val s = BookSearch(big)
        val t0 = System.nanoTime()
        val hits = s.find("klein", limit = 500)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("SEARCH ${big.length} chars: ${hits.size} hits in ${ms} ms (incl. folding)")
        assertEquals(500, hits.size)
        assertTrue(ms < 3000)
    }
}
