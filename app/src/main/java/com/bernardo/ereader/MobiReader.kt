package com.bernardo.ereader

import java.nio.charset.Charset

/**
 * Minimal MOBI / AZW / AZW3 / PRC reader (no Android deps, so it's unit-testable on the JVM).
 * Decompresses the text records (PalmDOC or HUFF/CDIC) into the book's raw HTML and pulls
 * title / author / cover from the EXTH header. DRM-protected files are rejected.
 *
 * Format notes: https://wiki.mobileread.com/wiki/MOBI and KindleUnpack's mobi_uncompress.py.
 */
class MobiReader(private val data: ByteArray) {

    class Result(val html: String, val title: String?, val author: String?, val cover: ByteArray?, val isKf8: Boolean)

    private val recordOffsets: IntArray

    init {
        require(data.size > 78) { "file too small" }
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        require(type == "BOOKMOBI" || type == "TEXtREAd") { "not a MOBI file" }
        val n = u16(76)  // PDB record count
        recordOffsets = IntArray(n) { u32(78 + it * 8).toInt() }
    }

    private fun u16(off: Int) = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
    private fun u32(off: Int): Long =
        ((data[off].toLong() and 0xFF) shl 24) or ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or (data[off + 3].toLong() and 0xFF)

    private fun record(i: Int): ByteArray {
        val start = recordOffsets[i]
        val end = if (i + 1 < recordOffsets.size) recordOffsets[i + 1] else data.size
        return data.copyOfRange(start, end)
    }

    fun read(): Result {
        val r0 = recordOffsets[0]
        val compression = u16(r0)
        val textRecords = u16(r0 + 8)
        if (u16(r0 + 12) != 0) error("this book is DRM-protected and can't be opened")

        val hasMobi = String(data, r0 + 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLen = if (hasMobi) u32(r0 + 20).toInt() else 0
        val encoding = if (hasMobi && u32(r0 + 28) == 65001L) Charsets.UTF_8 else Charset.forName("windows-1252")
        val version = if (hasMobi) u32(r0 + 36).toInt() else 0
        val firstImage = if (hasMobi) u32(r0 + 108).toInt() else -1
        val extraFlags = if (hasMobi && headerLen >= 0xE4) u16(r0 + 0xF2) else 0

        // EXTH metadata
        var title: String? = null
        var author: String? = null
        var coverIdx = -1
        if (hasMobi && (u32(r0 + 128) and 0x40L) != 0L) {
            var p = r0 + 16 + headerLen
            if (String(data, p, 4, Charsets.ISO_8859_1) == "EXTH") {
                val count = u32(p + 8).toInt()
                p += 12
                repeat(count) {
                    val t = u32(p).toInt()
                    val len = u32(p + 4).toInt()
                    val body = data.copyOfRange(p + 8, p + len)
                    when (t) {
                        100 -> author = author ?: String(body, encoding).trim()
                        503 -> title = String(body, encoding).trim()
                        201 -> if (body.size >= 4) coverIdx = ((body[0].toInt() and 0xFF) shl 24) or ((body[1].toInt() and 0xFF) shl 16) or
                            ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
                    }
                    p += len
                }
            }
        }
        if (title == null && hasMobi) {
            val off = u32(r0 + 84).toInt()
            val len = u32(r0 + 88).toInt()
            if (off > 0 && len > 0 && r0 + off + len <= data.size) title = String(data, r0 + off, len, encoding)
        }

        val huff = if (compression == 17480) {
            val huffIdx = u32(r0 + 112).toInt()
            val huffCount = u32(r0 + 116).toInt()
            HuffCdic(record(huffIdx), (1 until huffCount).map { record(huffIdx + it) })
        } else null

        val out = java.io.ByteArrayOutputStream()
        for (i in 1..textRecords) {
            if (i >= recordOffsets.size) break
            var rec = record(i)
            rec = rec.copyOf(rec.size - trailingSize(rec, extraFlags))
            out.write(when (compression) {
                1 -> rec
                2 -> palmDoc(rec)
                17480 -> huff!!.unpack(rec)
                else -> error("unsupported MOBI compression $compression")
            })
        }

        val cover = if (firstImage > 0 && coverIdx >= 0 && firstImage + coverIdx < recordOffsets.size)
            record(firstImage + coverIdx) else null
        return Result(String(out.toByteArray(), encoding), title, author, cover, version >= 8)
    }

    /** Text records can carry trailing entries (described by the extra-data flags) that aren't text. */
    private fun trailingSize(rec: ByteArray, flags: Int): Int {
        fun entry(size: Int): Int {
            var bitpos = 0
            var result = 0
            var s = size
            while (s > 0) {
                val v = rec[s - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitpos)
                bitpos += 7
                s--
                if ((v and 0x80) != 0 || bitpos >= 28) break
            }
            return result
        }
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += entry(rec.size - num)
            f = f shr 1
        }
        if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
        return num.coerceIn(0, rec.size)
    }

    companion object {
        /** PalmDOC LZ77 variant. */
        fun palmDoc(src: ByteArray): ByteArray {
            var buf = ByteArray(src.size * 8)
            var o = 0
            fun put(b: Int) {
                if (o == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[o++] = b.toByte()
            }
            var i = 0
            while (i < src.size) {
                val c = src[i++].toInt() and 0xFF
                when {
                    c == 0 || c in 0x09..0x7F -> put(c)
                    c in 0x01..0x08 -> repeat(c) { if (i < src.size) put(src[i++].toInt() and 0xFF) }
                    c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                    else -> {
                        if (i >= src.size) break
                        val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                        val dist = (pair shr 3) and 0x07FF
                        val len = (pair and 7) + 3
                        if (dist in 1..o) repeat(len) { put(buf[o - dist].toInt()) }
                    }
                }
            }
            return buf.copyOf(o)
        }
    }

    /** HUFF/CDIC decompression used by most Kindle-store style files. Port of KindleUnpack's HuffcdicReader. */
    private class HuffCdic(huff: ByteArray, cdics: List<ByteArray>) {
        private val dict1Len = IntArray(256)
        private val dict1Term = BooleanArray(256)
        private val dict1Max = LongArray(256)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val phrases = ArrayList<ByteArray?>()
        private val phraseDone = ArrayList<Boolean>()

        private fun u32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

        init {
            require(String(huff, 0, 4, Charsets.ISO_8859_1) == "HUFF") { "bad HUFF record" }
            val off1 = u32(huff, 8).toInt()
            val off2 = u32(huff, 12).toInt()
            for (i in 0 until 256) {
                val v = u32(huff, off1 + i * 4)
                val len = (v and 0x1F).toInt()
                dict1Len[i] = len
                dict1Term[i] = (v and 0x80) != 0L
                dict1Max[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
            }
            minCode[0] = 0
            maxCode[0] = (1L shl 32) - 1
            for (len in 1..32) {
                val mn = u32(huff, off2 + (len - 1) * 8)
                val mx = u32(huff, off2 + (len - 1) * 8 + 4)
                minCode[len] = mn shl (32 - len)
                maxCode[len] = ((mx + 1) shl (32 - len)) - 1
            }
            for (cdic in cdics) {
                require(String(cdic, 0, 4, Charsets.ISO_8859_1) == "CDIC") { "bad CDIC record" }
                val total = u32(cdic, 8).toInt()
                val bits = u32(cdic, 12).toInt()
                val n = minOf(1 shl bits, total - phrases.size)
                for (k in 0 until n) {
                    val off = ((cdic[16 + k * 2].toInt() and 0xFF) shl 8) or (cdic[17 + k * 2].toInt() and 0xFF)
                    val blen = ((cdic[16 + off].toInt() and 0xFF) shl 8) or (cdic[17 + off].toInt() and 0xFF)
                    phrases += cdic.copyOfRange(18 + off, 18 + off + (blen and 0x7FFF))
                    phraseDone += (blen and 0x8000) != 0
                }
            }
        }

        fun unpack(input: ByteArray): ByteArray {
            val data = input.copyOf(input.size + 8)
            var bitsLeft = input.size.toLong() * 8
            var pos = 0
            fun q(p: Int): Long {
                var x = 0L
                for (k in 0 until 8) x = (x shl 8) or (data[p + k].toLong() and 0xFF)
                return x
            }
            var x = q(0)
            var n = 32
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                if (n <= 0) {
                    pos += 4
                    x = q(pos)
                    n += 32
                }
                val code = (x ushr n) and 0xFFFFFFFFL
                val top = (code ushr 24).toInt()
                var len = dict1Len[top]
                var max = dict1Max[top]
                if (!dict1Term[top]) {
                    while (code < minCode[len]) len++
                    max = maxCode[len]
                }
                n -= len
                bitsLeft -= len
                if (bitsLeft < 0) break
                val r = ((max - code) ushr (32 - len)).toInt()
                var phrase = phrases[r]!!
                if (!phraseDone[r]) {
                    phrases[r] = null
                    phrase = unpack(phrase)
                    phrases[r] = phrase
                    phraseDone[r] = true
                }
                out.write(phrase)
            }
            return out.toByteArray()
        }
    }
}
