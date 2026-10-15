package com.bernardo.ereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF -> reflowable text. PDFs have no paragraphs, only positioned lines, so this joins wrapped lines,
 * repairs words hyphenated across lines, drops bare page numbers, and maps bookmarks to chapters.
 * Scanned (image-only) PDFs have no text layer and are rejected.
 */
object PdfParser {

    fun parse(ctx: Context, file: File): Book {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(file).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                paragraphEnd = "\n\n"
                pageEnd = "\u000C"
            }
            val pages = stripper.getText(doc).split('\u000C')

            val sb = StringBuilder()
            val pageStarts = IntArray(doc.numberOfPages)
            for ((i, raw) in pages.withIndex()) {
                if (i < pageStarts.size) pageStarts[i] = sb.length
                val page = cleanPage(raw)
                if (page.isEmpty()) continue
                if (sb.isNotEmpty()) {
                    // a page break in the middle of a sentence is just a space
                    val endsSentence = sb.trimEnd().lastOrNull()?.let { it in ".!?…:\"”’)" } ?: true
                    sb.append(if (endsSentence) "\n\n" else " ")
                    if (i < pageStarts.size) pageStarts[i] = sb.length
                }
                sb.append(page)
            }
            val text = sb.toString()
            if (text.count { it.isLetter() } < 200) error("this PDF has no text layer (scanned pages can't be read aloud)")

            val info = doc.documentInformation
            return Book(
                text = text,
                chapters = outlineChapters(doc, pageStarts, text).ifEmpty { BookParser.detectTxtChapters(text) },
                styles = IntArray(0),
                title = info?.title?.takeIf { it.isNotBlank() },
                author = info?.author?.takeIf { it.isNotBlank() },
                cover = renderCover(file),
            )
        }
    }

    private fun cleanPage(raw: String): String {
        val lines = raw.replace("\r", "").lines().map { it.trim() }.toMutableList()
        // running page numbers at the top/bottom
        while (lines.isNotEmpty() && (lines.first().isEmpty() || lines.first().matches(Regex("\\d{1,4}")))) lines.removeAt(0)
        while (lines.isNotEmpty() && (lines.last().isEmpty() || lines.last().matches(Regex("\\d{1,4}")))) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n")
            .replace(Regex("(\\p{L})-\n(\\p{Ll})"), "$1$2")   // "exam-\nple" -> "example"
            .replace(Regex("(?<!\n)\n(?!\n)"), " ")           // unwrap lines inside a paragraph
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Top-level (and second-level) bookmarks -> chapters at the start of their page. */
    private fun outlineChapters(doc: PDDocument, pageStarts: IntArray, text: String): List<Chapter> {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val found = ArrayList<Chapter>()
        fun visit(item: PDOutlineItem, depth: Int) {
            val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
            val idx = page?.let { doc.pages.indexOf(it) } ?: -1
            val title = item.title?.trim().orEmpty()
            if (idx in pageStarts.indices && title.isNotEmpty()) {
                val start = pageStarts[idx]
                // style the heading if the page actually begins with it
                val headEnd = if (text.regionMatches(start, title, 0, title.length, ignoreCase = true)) start + title.length else start
                found += Chapter(title, start, headEnd)
            }
            if (depth < 1) item.children().forEach { visit(it, depth + 1) }
        }
        outline.children().forEach { visit(it, 0) }
        return found.sortedBy { it.start }.distinctBy { it.start }
    }

    private fun renderCover(file: File): ByteArray? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val w = 480
                    val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(1, 1200)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it); bmp.recycle() }.toByteArray()
                }
            }
        }
    }.getOrNull()
}
