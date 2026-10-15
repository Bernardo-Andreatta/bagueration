package com.bernardo.ereader

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against real Kindle files when MOBI_SAMPLES points at a folder of .mobi/.azw3 files. */
class MobiReaderTest {
    @Test
    fun readsSampleBooks() {
        val dir = System.getenv("MOBI_SAMPLES")?.let(::File)
        assumeTrue("set MOBI_SAMPLES to run", dir != null && dir.isDirectory)
        val files = dir!!.listFiles()!!.filter { it.extension in setOf("mobi", "azw3", "azw", "prc") }
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val r = MobiReader(f.readBytes()).read()
            val plain = r.html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
            println("${f.name}: kf8=${r.isKf8} title=${r.title} author=${r.author} cover=${r.cover?.size} " +
                "html=${r.html.length} chars; sample: ${plain.substring(plain.length / 3, plain.length / 3 + 160)}")
            assertTrue("${f.name}: expected opening line", plain.contains("truth universally acknowledged"))
            assertTrue("${f.name}: cover should be an image", r.cover == null ||
                (r.cover!![0] == 0xFF.toByte() && r.cover!![1] == 0xD8.toByte()) || String(r.cover!!, 1, 3, Charsets.ISO_8859_1) == "PNG" ||
                String(r.cover!!, 0, 3, Charsets.ISO_8859_1) == "GIF")
        }
    }
}
