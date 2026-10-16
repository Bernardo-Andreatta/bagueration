package com.bernardo.ereader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfHeadingTest {

    @Test
    fun headingRunIntoTextGetsItsOwnLine() {
        val text = "fim da frase anterior. Capítulo 1 Era uma vez um reino."
        val start = text.indexOf("Capítulo")
        val ch = Chapter("Capítulo 1", start, start + "Capítulo 1".length)
        val out = PdfParser.headingsOnOwnLine(text, listOf(ch))
        assertEquals("fim da frase anterior.\nCapítulo 1\nEra uma vez um reino.", out)
        assertEquals(text.length, out.length)
    }

    @Test
    fun headingAlreadyOnItsOwnLineIsUntouched() {
        val text = "fim.\n\nCapítulo 2\n\nTexto."
        val start = text.indexOf("Capítulo")
        val out = PdfParser.headingsOnOwnLine(text, listOf(Chapter("Capítulo 2", start, start + 10)))
        assertEquals(text, out)
    }

    @Test
    fun chapterWithoutItsTitleOnThePageLeavesTheSentenceAlone() {
        // the page doesn't begin with the bookmark's title (headEnd == start) and continues a sentence
        val text = "uma frase que continua na página seguinte e segue."
        val start = text.indexOf("página")
        val out = PdfParser.headingsOnOwnLine(text, listOf(Chapter("Parte 1", start, start)))
        assertEquals(text, out)
    }
}
