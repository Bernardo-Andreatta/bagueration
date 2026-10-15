package com.bernardo.ereader

import android.content.Context

/** A bookmark (note == null) or a note on the text [start, end) of a book. */
data class Annotation(val id: Long, val start: Int, val end: Int, val note: String?) {
    val isNote get() = note != null
}

/** Bookmarks and notes, per book, kept in the app's preferences. */
object Annotations {

    private const val FIELD = '\u0001'
    private const val ENTRY = '\u0002'

    private fun key(bookId: String) = "annot_$bookId"

    fun list(ctx: Context, bookId: String): List<Annotation> =
        BookStore.prefs(ctx).getString(key(bookId), "").orEmpty().split(ENTRY).mapNotNull { e ->
            val f = e.split(FIELD)
            if (f.size < 4) null else runCatching {
                Annotation(f[0].toLong(), f[1].toInt(), f[2].toInt(), f[3].takeIf { f.size > 4 && f[4] == "n" })
            }.getOrNull()
        }.sortedBy { it.start }

    private fun write(ctx: Context, bookId: String, all: List<Annotation>) =
        BookStore.prefs(ctx).edit().putString(key(bookId), all.joinToString(ENTRY.toString()) {
            listOf(it.id, it.start, it.end, it.note ?: "", if (it.isNote) "n" else "b").joinToString(FIELD.toString())
        }).apply()

    fun add(ctx: Context, bookId: String, start: Int, end: Int, note: String?): Annotation {
        val a = Annotation(System.currentTimeMillis(), start, end, note)
        // one bookmark per spot is enough
        val rest = list(ctx, bookId).filterNot { !it.isNote && note == null && it.start == start }
        write(ctx, bookId, rest + a)
        return a
    }

    fun update(ctx: Context, bookId: String, a: Annotation) =
        write(ctx, bookId, list(ctx, bookId).map { if (it.id == a.id) a else it })

    fun remove(ctx: Context, bookId: String, a: Annotation) =
        write(ctx, bookId, list(ctx, bookId).filterNot { it.id == a.id })

    fun clear(ctx: Context, bookId: String) = BookStore.prefs(ctx).edit().remove(key(bookId)).apply()
}
