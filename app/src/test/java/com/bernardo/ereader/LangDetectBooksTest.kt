package com.bernardo.ereader

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints what the detector picks for real book texts in $LANG_SAMPLES (diagnostic, never fails). */
class LangDetectBooksTest {
    @Test fun realBooks() {
        val dir = System.getenv("LANG_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (f in dir!!.listFiles()!!.filter { it.extension == "txt" }) {
            val text = f.readText()
            println("DETECT ${f.name.take(40)} -> ${LangDetect.detect(text)}   (${LangDetect.explain(text)})")
        }
    }
}
