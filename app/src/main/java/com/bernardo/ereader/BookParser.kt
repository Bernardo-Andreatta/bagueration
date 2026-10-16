package com.bernardo.ereader

import android.graphics.Typeface
import android.content.Context
import android.text.Html
import android.util.Base64
import android.text.Spanned
import android.text.style.StyleSpan
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** A chapter heading lives at [start, headEnd) in the book text. */
class Chapter(val title: String, val start: Int, val headEnd: Int) {
    /**
     * The title as shown in lists: numbered by its place in the book ("12. The Long Night"), unless it already
     * carries a number of its own ("270", "Chapter XII", "Capítulo 3"), which is then left alone.
     */
    fun displayName(index: Int): String =
        if (NUMBERED.containsMatchIn(title)) title else "${index + 1}. $title"

    private companion object {
        val NUMBERED = Regex("^\\s*(?:\\d|[IVXLC]+\\b|(?:chapter|capítulo|capitulo|capítol|chapitre|capitolo|kapitel|part|parte|partie|book|livro|libro|livre)\\b)", RegexOption.IGNORE_CASE)
    }
}

/**
 * Plain book text + chapter list + inline styles.
 * [styles] is a flat list of (start, end, Typeface style) triples.
 */
class Book(
    val text: String,
    val chapters: List<Chapter>,
    val styles: IntArray,
    // only filled at import time (stored separately, not in the .meta file)
    val title: String? = null,
    val author: String? = null,
    val cover: ByteArray? = null,
    val language: String? = null,
)

object BookParser {

    /** Detects the format from the file contents (file managers often mislabel .mobi/.fb2), then parses. */
    fun parse(ctx: Context, file: File, name: String): Book {
        val head = ByteArray(4096).let { b -> file.inputStream().use { val n = it.read(b); b.copyOf(maxOf(n, 0)) } }
        val ascii = String(head, Charsets.ISO_8859_1)
        val lower = name.lowercase()
        return when {
            ascii.startsWith("%PDF") -> PdfParser.parse(ctx, file)
            ascii.startsWith("PK") -> parseZip(file)
            head.size > 68 && ascii.substring(60, 68).let { it == "BOOKMOBI" || it == "TEXtREAd" } -> parseMobi(file.readBytes())
            ascii.startsWith("{\\rtf") -> error("RTF files aren't supported; save it as .docx, .epub or .txt")
            head.size > 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() -> error("old .doc files aren't supported; save it as .docx")
            "<FictionBook" in ascii -> parseFb2(file.readBytes())
            Regex("(?i)<(!doctype html|html)[\\s>]").containsMatchIn(ascii) -> parseHtml(decodeText(file.readBytes()))
            lower.endsWith(".md") || lower.endsWith(".markdown") -> parseHtml(markdownToHtml(decodeText(file.readBytes())))
            else -> parseTxt(decodeText(file.readBytes()))
        }
    }

    /** UTF-8 unless it clearly isn't, then Windows-1252 (common for older Portuguese/Spanish .txt files). */
    private fun decodeText(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        val bad = utf.count { it == '\uFFFD' }
        return if (bad > 10 && bad > utf.length / 2000) String(bytes, Charset.forName("windows-1252")) else utf
    }

    private fun parseZip(file: File): Book {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("META-INF/container.xml") != null -> return parseEpub(file)
                zip.getEntry("word/document.xml") != null -> return parseDocx(zip)
                else -> zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }?.let { e ->
                    return parseFb2(zip.getInputStream(e).use { it.readBytes() })
                }
            }
        }
        error("unsupported archive (expected .epub, .docx or .fb2.zip)")
    }

    // ---------- TXT ----------

    fun parseTxt(raw: String): Book {
        val unwrapped = raw.replace("\r\n", "\n").replace('\r', '\n')
            // unwrap hard-wrapped lines (Gutenberg style), keep blank lines as paragraph breaks
            .replace(Regex("(?<!\n)\n(?!\n)"), " ")
        val (text, _) = normalize(unwrapped)
        return Book(text, detectTxtChapters(text), IntArray(0))
    }

    private val CHAPTER_LINE = Regex(
        "(?i)^(chapter|chap\\.|part|book|prologue|epilogue|introduction|preface|cap[ií]tulo|parte)\\b.{0,80}$"
    )

    fun detectTxtChapters(text: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        var pos = 0
        while (pos < text.length) {
            val end = text.indexOf("\n\n", pos).let { if (it < 0) text.length else it }
            val para = text.substring(pos, end)
            if (para.length <= 90 && CHAPTER_LINE.matches(para.trim())) out += Chapter(para.trim(), pos, end)
            pos = end + 2
        }
        return out
    }

    // ---------- EPUB ----------

    private fun parseEpub(file: File): Book = ZipFile(file).use { zip ->
        fun read(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

        val container = read("META-INF/container.xml") ?: error("Not an EPUB: missing container.xml")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: error("No OPF in EPUB")
        val opfDir = dirOf(opfPath)
        val opf = read(opfPath) ?: error("Missing $opfPath")

        // manifest: id -> (full path, media-type, properties)
        class Item(val path: String, val type: String, val props: String)
        val manifest = HashMap<String, Item>()
        for (tag in Regex("(?s)<(?:\\w+:)?item\\b[^>]*>").findAll(opf)) {
            val t = tag.value
            val id = attr(t, "id") ?: continue
            val href = attr(t, "href") ?: continue
            manifest[id] = Item(resolve(opfDir, href), attr(t, "media-type").orEmpty(), attr(t, "properties").orEmpty())
        }
        val spine = Regex("(?s)<(?:\\w+:)?itemref\\b[^>]*>").findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()

        // table of contents: full path -> first label pointing at it
        val toc = HashMap<String, String>()
        manifest.values.firstOrNull { "nav" in it.props.split(' ') }?.let { nav ->
            val html = read(nav.path) ?: return@let
            val tocNav = Regex("(?is)<nav[^>]*toc[^>]*>(.*?)</nav>").find(html)?.groupValues?.get(1) ?: html
            for (m in Regex("(?is)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(tocNav)) {
                toc.putIfAbsent(resolve(dirOf(nav.path), m.groupValues[1]), stripTags(m.groupValues[2]))
            }
        }
        if (toc.isEmpty()) manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }?.let { ncx ->
            val xml = read(ncx.path) ?: return@let
            val re = Regex("(?is)<navLabel>\\s*<text>(.*?)</text>\\s*</navLabel>\\s*<content[^>]*src=\"([^\"]+)\"")
            for (m in re.findAll(xml)) toc.putIfAbsent(resolve(dirOf(ncx.path), m.groupValues[2]), stripTags(m.groupValues[1]))
        }

        val sections = spine.mapNotNull { id -> manifest[id]?.let { item -> read(item.path)?.let { toc[item.path] to it } } }
        val (text, chapters, styles) = assemble(sections)
        // metadata + cover image
        val title = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let(::stripTags)
        val author = Regex("(?is)<dc:creator[^>]*>(.*?)</dc:creator>").find(opf)?.groupValues?.get(1)?.let(::stripTags)
        val coverId = Regex("(?is)<meta\\b[^>]*>").findAll(opf).map { it.value }
            .firstOrNull { attr(it, "name") == "cover" }?.let { attr(it, "content") }
        val coverItem = manifest.values.firstOrNull { "cover-image" in it.props.split(' ') }
            ?: coverId?.let { manifest[it] }?.takeIf { it.type.startsWith("image/") }
            ?: manifest.entries.firstOrNull { (id, it) -> it.type.startsWith("image/") && ("cover" in id.lowercase() || "cover" in it.path.lowercase()) }?.value
        val cover = coverItem?.let { item -> zip.getEntry(item.path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }

        val language = Regex("(?is)<dc:language[^>]*>(.*?)</dc:language>").find(opf)?.groupValues?.get(1)?.trim()
        Book(text, chapters, styles, title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() }, cover, language)
    }

    /**
     * Turns a list of (toc title or null, html) sections into one text with chapters + bold/italic styles.
     * Each section with a title (or a leading h1-h3) becomes a chapter.
     */
    private fun assemble(sections: List<Pair<String?, String>>): Triple<String, List<Chapter>, IntArray> {
        val sb = StringBuilder()
        val styles = ArrayList<Int>()
        val chapters = ArrayList<Chapter>()
        for ((tocTitle, html) in sections) {
            val body = html
                .replace(Regex("(?is)<head.*?</head>"), "")
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
            val (secText, secStyles) = normalize(Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY))
            if (secText.isBlank()) continue

            if (sb.isNotEmpty()) sb.append("\n\n")
            var base = sb.length
            val title = tocTitle ?: firstHeading(body)
            if (title != null && title.isNotBlank()) {
                val firstPara = secText.substringBefore('\n')
                if (firstPara.length <= 150 && loose(firstPara).let { it.isNotEmpty() && (loose(title).contains(it) || it.contains(loose(title))) }) {
                    chapters += Chapter(title, base, base + firstPara.length)
                } else {
                    // heading isn't in the body text: insert it
                    sb.append(title).append("\n\n")
                    chapters += Chapter(title, base, base + title.length)
                    base = sb.length
                }
            }
            sb.append(secText)
            for (i in secStyles.indices step 3) {
                styles += secStyles[i] + base; styles += secStyles[i + 1] + base; styles += secStyles[i + 2]
            }
        }
        val text = sb.toString()
        return Triple(text, chapters.ifEmpty { detectTxtChapters(text) }, styles.toIntArray())
    }

    /** Split one big HTML document before each h1/h2 so every heading starts a chapter. */
    private fun splitAtHeadings(html: String): List<Pair<String?, String>> =
        html.split(Regex("(?i)(?=<h[12][\\s>])")).filter { it.isNotBlank() }.map { null to it }

    // ---------- MOBI / AZW / AZW3 ----------

    private fun parseMobi(bytes: ByteArray): Book {
        val r = MobiReader(bytes).read()
        // classic MOBI marks chapters with page breaks; KF8 (AZW3) stores one html file after another
        var sections: List<Pair<String?, String>> = r.html.split(if (r.isKf8) Regex("(?i)(?=<\\?xml|<html[\\s>])") else Regex("(?i)<mbp:pagebreak\\s*/?>"))
            .filter { it.isNotBlank() }.map { null to it }
        if (sections.size <= 1) sections = splitAtHeadings(r.html)
        val (text, chapters, styles) = assemble(sections)
        return Book(text, chapters, styles, r.title, r.author, r.cover)
    }

    // ---------- HTML / Markdown ----------

    private fun parseHtml(html: String): Book {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::stripTags)
        val (text, chapters, styles) = assemble(splitAtHeadings(html))
        return Book(text, chapters, styles, title?.takeIf { it.isNotBlank() })
    }

    private fun markdownToHtml(md: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun inline(s: String) = esc(s)
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<![*\\w])[*_](.+?)[*_](?![*\\w])"), "<i>$1</i>")
        return md.replace("\r\n", "\n").split(Regex("\n\\s*\n")).joinToString("\n") { block ->
            val b = block.trim()
            val h = Regex("^(#{1,6})\\s+(.*)$").find(b)
            when {
                h != null -> { val lvl = minOf(h.groupValues[1].length, 3); "<h$lvl>${inline(h.groupValues[2])}</h$lvl>" }
                else -> "<p>${inline(b.replace(Regex("\\s*\n\\s*"), " "))}</p>"
            }
        }
    }

    // ---------- FB2 ----------

    private fun parseFb2(bytes: ByteArray): Book {
        val enc = Regex("encoding=[\"']([\\w-]+)[\"']").find(String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1))
            ?.groupValues?.get(1)
        val xml = String(bytes, enc?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8)
        val info = Regex("(?is)<title-info>(.*?)</title-info>").find(xml)?.groupValues?.get(1).orEmpty()
        val title = Regex("(?is)<book-title>(.*?)</book-title>").find(info)?.groupValues?.get(1)?.let(::stripTags)
        val author = Regex("(?is)<author>(.*?)</author>").find(info)?.groupValues?.get(1)?.let { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { t ->
                Regex("(?is)<$t>(.*?)</$t>").find(a)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }.joinToString(" ")
        }?.takeIf { it.isNotBlank() }
        val coverRef = Regex("(?is)<coverpage>.*?href=[\"']#([^\"']+)[\"']").find(info)?.groupValues?.get(1)
        val cover = coverRef?.let { id ->
            Regex("(?is)<binary[^>]*id=[\"']${Regex.escape(id)}[\"'][^>]*>(.*?)</binary>").find(xml)?.groupValues?.get(1)
        }?.let { runCatching { Base64.decode(it.replace(Regex("\\s"), ""), Base64.DEFAULT) }.getOrNull() }

        // main body only (skip <body name="notes">)
        val body = Regex("(?is)<body(?![^>]*name=[\"']notes)[^>]*>(.*?)</body>").find(xml)?.groupValues?.get(1).orEmpty()
        val html = body
            .replace(Regex("(?is)<image[^>]*/>"), "")
            .replace(Regex("(?i)<title>"), "<h2>").replace(Regex("(?i)</title>"), "</h2>")
            .replace(Regex("(?i)<subtitle>"), "<p><b>").replace(Regex("(?i)</subtitle>"), "</b></p>")
            .replace(Regex("(?i)<emphasis>"), "<i>").replace(Regex("(?i)</emphasis>"), "</i>")
            .replace(Regex("(?i)<strong>"), "<b>").replace(Regex("(?i)</strong>"), "</b>")
            .replace(Regex("(?i)<empty-line\\s*/>"), "<br/>")
            .replace(Regex("(?i)<v>"), "<p>").replace(Regex("(?i)</v>"), "</p>")
        // titles contain <p> lines; flatten them so a heading stays one line
        val flat = Regex("(?is)<h2>(.*?)</h2>").replace(html) { m ->
            "<h2>" + stripTags(m.groupValues[1].replace(Regex("(?i)</p>\\s*<p>"), " — ")) + "</h2>"
        }
        val (text, chapters, styles) = assemble(splitAtHeadings(flat))
        val lang = Regex("(?is)<lang>(.*?)</lang>").find(info)?.groupValues?.get(1)?.trim()
        return Book(text, chapters, styles, title?.takeIf { it.isNotBlank() }, author, cover, lang)
    }

    // ---------- DOCX ----------

    private fun parseDocx(zip: ZipFile): Book {
        fun read(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
        val doc = read("word/document.xml") ?: error("not a .docx")
        val html = StringBuilder()
        for (p in Regex("(?s)<w:p[ >].*?</w:p>").findAll(doc)) {
            val para = p.value
            val style = Regex("<w:pStyle w:val=\"([^\"]+)\"").find(para)?.groupValues?.get(1).orEmpty()
            val sb = StringBuilder()
            for (r in Regex("(?s)<w:r[ >].*?</w:r>").findAll(para)) {
                val run = r.value
                val props = Regex("(?s)<w:rPr>(.*?)</w:rPr>").find(run)?.groupValues?.get(1).orEmpty()
                fun on(tag: String) = Regex("<w:$tag(?: w:val=\"(?:1|true|on)\")?\\s*/>").containsMatchIn(props)
                var t = Regex("(?s)<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>").findAll(run).joinToString("") { m ->
                    when {
                        m.value == "<w:tab/>" -> " "
                        m.value == "<w:br/>" -> "<br/>"
                        else -> m.groupValues[1]
                    }
                }
                if (t.isEmpty()) continue
                if (on("i")) t = "<i>$t</i>"
                if (on("b")) t = "<b>$t</b>"
                sb.append(t)
            }
            if (sb.isEmpty()) continue
            val heading = Regex("(?i)^(heading ?[12]|title|t[ií]tulo ?[12]?)$").matches(style)
            html.append(if (heading) "<h2>$sb</h2>" else "<p>$sb</p>").append('\n')
        }
        val core = read("docProps/core.xml").orEmpty()
        val title = Regex("(?is)<dc:title>(.*?)</dc:title>").find(core)?.groupValues?.get(1)?.let(::stripTags)
        val author = Regex("(?is)<dc:creator>(.*?)</dc:creator>").find(core)?.groupValues?.get(1)?.let(::stripTags)
        val (text, chapters, styles) = assemble(splitAtHeadings(html.toString()))
        return Book(text, chapters, styles, title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() })
    }

    private fun attr(tag: String, name: String) =
        Regex("(?<![\\w-])$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun dirOf(path: String) = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    private fun resolve(dir: String, href: String): String {
        val parts = ArrayList<String>()
        val clean = try { URLDecoder.decode(href.substringBefore('#'), "UTF-8") } catch (_: Exception) { href.substringBefore('#') }
        for (seg in (dir + clean).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun stripTags(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun firstHeading(html: String) =
        Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.get(1)?.let(::stripTags)?.takeIf { it.isNotEmpty() }

    private fun loose(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Single pass whitespace cleanup that keeps bold/italic spans aligned with the new offsets.
     * Collapses runs of spaces, trims around newlines, max one blank line, drops image placeholders.
     */
    private fun normalize(src: CharSequence): Pair<String, IntArray> {
        val n = src.length
        val out = StringBuilder(n)
        val map = IntArray(n + 1)
        var pendingSpace = false
        var pendingNl = 0
        for (i in 0 until n) {
            map[i] = out.length
            when (val c = src[i]) {
                '\uFFFC' -> {}
                '\n' -> { pendingNl++; pendingSpace = false }
                ' ', '\t', '\u00A0', '\r', '\u2028', '\u2029' -> if (pendingNl == 0) pendingSpace = true
                else -> {
                    if (out.isNotEmpty()) {
                        if (pendingNl > 0) out.append(if (pendingNl >= 2) "\n\n" else "\n")
                        else if (pendingSpace) out.append(' ')
                    }
                    map[i] = out.length
                    pendingNl = 0; pendingSpace = false
                    out.append(c)
                }
            }
        }
        map[n] = out.length

        val styles = ArrayList<Int>()
        if (src is Spanned) {
            for (span in src.getSpans(0, n, StyleSpan::class.java)) {
                val st = span.style
                if (st != Typeface.BOLD && st != Typeface.ITALIC && st != Typeface.BOLD_ITALIC) continue
                val s = map[src.getSpanStart(span)]
                val e = map[src.getSpanEnd(span)]
                if (e > s) { styles += s; styles += e; styles += st }
            }
        }
        return out.toString() to styles.toIntArray()
    }

    // ---------- storage: <id>.txt (text) + <id>.meta (chapters + styles) ----------

    private const val META_VERSION = 1

    fun save(dir: File, id: String, book: Book) {
        File(dir, "$id.txt").writeText(book.text)
        DataOutputStream(File(dir, "$id.meta").outputStream().buffered()).use { o ->
            o.writeInt(META_VERSION)
            o.writeInt(book.chapters.size)
            for (c in book.chapters) { o.writeUTF(c.title.take(500)); o.writeInt(c.start); o.writeInt(c.headEnd) }
            o.writeInt(book.styles.size)
            for (v in book.styles) o.writeInt(v)
        }
    }

    fun load(dir: File, id: String): Book? {
        val txt = File(dir, "$id.txt").takeIf { it.exists() } ?: return null
        val text = txt.readText()
        val meta = File(dir, "$id.meta")
        if (meta.exists()) try {
            DataInputStream(meta.inputStream().buffered()).use { i ->
                if (i.readInt() == META_VERSION) {
                    val chapters = List(i.readInt()) { Chapter(i.readUTF(), i.readInt(), i.readInt()) }
                    val styles = IntArray(i.readInt()) { i.readInt() }
                    return Book(text, chapters, styles)
                }
            }
        } catch (_: Exception) {}
        // books imported by the first version: plain text only, guess chapters
        return Book(text, detectTxtChapters(text), IntArray(0))
    }
}
