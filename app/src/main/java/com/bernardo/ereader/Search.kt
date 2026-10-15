package com.bernardo.ereader

import java.text.Normalizer

/**
 * Case- and accent-insensitive search over a book. The folded copy has exactly the same length as the
 * text (one char per char), so match offsets map straight back to the book.
 */
class BookSearch(private val text: String) {

    class Hit(val start: Int, val end: Int)

    private val folded: CharArray by lazy {
        val cache = HashMap<Char, Char>()
        CharArray(text.length) { i -> fold(text[i], cache) }
    }

    private fun fold(c: Char, cache: HashMap<Char, Char>): Char {
        if (c.code < 128) return c.lowercaseChar()
        return cache.getOrPut(c) {
            // "é" -> "e", "ç" -> "c", "Ä" -> "a"; anything that doesn't decompose stays itself
            Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                ?.lowercaseChar() ?: c.lowercaseChar()
        }
    }

    fun find(query: String, from: Int = 0, to: Int = text.length, limit: Int = 500): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cache = HashMap<Char, Char>()
        val needle = CharArray(q.length) { fold(q[it], cache) }
        val hay = folded
        val out = ArrayList<Hit>()
        var i = from.coerceAtLeast(0)
        val last = to.coerceAtMost(hay.size) - needle.size
        val first = needle[0]
        while (i <= last && out.size < limit) {
            if (hay[i] == first) {
                var k = 1
                while (k < needle.size && hay[i + k] == needle[k]) k++
                if (k == needle.size) { out += Hit(i, i + needle.size); i += needle.size; continue }
            }
            i++
        }
        return out
    }

    /** "…a few words before MATCH a few words after…" with the match position inside the snippet. */
    fun snippet(hit: Hit, around: Int = 50): Triple<String, Int, Int> {
        var a = (hit.start - around).coerceAtLeast(0)
        var b = (hit.end + around).coerceAtMost(text.length)
        while (a > 0 && !text[a - 1].isWhitespace() && hit.start - a < around + 15) a--
        while (b < text.length && !text[b].isWhitespace() && b - hit.end < around + 15) b++
        val raw = text.substring(a, b).replace('\n', ' ')
        val prefix = if (a > 0) "…" else ""
        val suffix = if (b < text.length) "…" else ""
        return Triple(prefix + raw + suffix, prefix.length + (hit.start - a), prefix.length + (hit.end - a))
    }
}
