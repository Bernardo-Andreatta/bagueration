package com.bernardo.ereader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Imported books live in filesDir/books as <id>.txt + <id>.meta (+ <id>.jpg cover). Progress etc. in prefs. */
object BookStore {

    class Entry(val id: String, val title: String, val author: String?, val cover: File?, val progress: Int, val modified: Long)

    fun dir(ctx: Context) = File(ctx.filesDir, "books").apply { mkdirs() }
    fun prefs(ctx: Context) = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)!!

    fun list(ctx: Context): List<Entry> {
        val d = dir(ctx)
        val p = prefs(ctx)
        return d.listFiles().orEmpty().filter { it.extension == "txt" }.map { f ->
            val id = f.nameWithoutExtension
            val len = f.length().coerceAtLeast(1)
            Entry(
                id = id,
                title = p.getString("title_$id", id) ?: id,
                author = p.getString("author_$id", null),
                cover = File(d, "$id.jpg").takeIf { it.exists() },
                progress = (p.getInt("pos_$id", 0) * 100L / len).toInt().coerceIn(0, 100),
                modified = p.getLong("opened_$id", f.lastModified()),
            )
        }.sortedByDescending { it.modified }
    }

    /** Blocking: copy, parse, save. Returns the book id. Call off the main thread. */
    fun import(ctx: Context, uri: Uri): String {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "book.txt"
        val tmp = File(ctx.cacheDir, "import.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val book = try { BookParser.parse(ctx, tmp, name) } finally { tmp.delete() }
        if (book.text.isBlank()) error("no text found")

        val id = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val d = dir(ctx)
        BookParser.save(d, id, book)
        book.cover?.let { saveCover(it, File(d, "$id.jpg")) }
        prefs(ctx).edit()
            .putString("title_$id", book.title ?: name.substringBeforeLast('.'))
            .putString("author_$id", book.author)
            .putLong("opened_$id", System.currentTimeMillis())
            .putString("lang2_$id", LangDetect.detect(book.text, book.language))
            .putString("langtag_$id", book.language)
            .apply()
        return id
    }

    fun delete(ctx: Context, id: String) {
        val d = dir(ctx)
        for (ext in listOf("txt", "meta", "jpg")) File(d, "$id.$ext").delete()
        val p = prefs(ctx)
        p.edit().apply {
            for (k in listOf("title_", "author_", "pos_", "opened_", "lang_", "lang2_", "langtag_", "voice_book_", "skip_voice_offer_", "annot_", "pron_", "render_active_", "live_tip_", "chapter_", "heard_", "heardfile_")) remove("$k$id")
            if (p.getString("last_book", null) == id) remove("last_book")
        }.apply()
    }

    fun markOpened(ctx: Context, id: String) =
        prefs(ctx).edit().putLong("opened_$id", System.currentTimeMillis()).apply()

    /** Downscale to thumbnail size so the library grid stays light. */
    private fun saveCover(bytes: ByteArray, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
    }
}
