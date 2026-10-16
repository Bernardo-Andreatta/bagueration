package com.bernardo.ereader

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * A pre-rendered stretch of a book: AAC audio plus a sync map (which sentence starts at which millisecond),
 * made with a fixed voice and settings. Several can exist for the same chapters. The audio is written in parts
 * of a few minutes each, so what's finished can be played (and kept) while the rest is still being made.
 */
data class Render(
    val id: String,
    val bookId: String,
    val fromChar: Int,
    val toChar: Int,
    val label: String,          // "Chapters 3–7"
    val voiceId: String,
    val settings: String,       // VoicePreset.serialize() + "|phrasing"
    val status: String,         // processing, ready, failed, interrupted, paused
    val durationMs: Long,       // audio in the finished parts
    val created: Long,
    val doneChar: Int = fromChar,           // the finished parts cover [fromChar, doneChar); making resumes here
    val parts: List<Long> = emptyList(),    // length (ms) of each finished part; empty = older single-file render
    val error: String = "",                 // why it last failed, shown on its card
) {
    val ready get() = status == "ready"
    /** Some audio can be played already (finished, or the parts made so far). */
    val playable get() = ready || parts.isNotEmpty()
    /** End of the text that has audio. */
    val coveredTo get() = if (ready) toChar else doneChar
    val fraction get() = ((coveredTo - fromChar).toFloat() / (toChar - fromChar).coerceAtLeast(1)).coerceIn(0f, 1f)

    fun describe(): String {
        val v = Voices.byId(voiceId)
        val parts = settings.split('|')
        val speed = parts.getOrNull(0)?.toFloatOrNull()?.let { "${"%.2f".format(java.util.Locale.US, it).trimEnd('0').trimEnd('.')}×" } ?: ""
        val blend = when (parts.getOrNull(5)) { "1" -> " · light blend"; "2" -> " · half blend"; else -> "" }
        return "${v?.name ?: voiceId} · $speed$blend"
    }
}

/** One entry of the sync map: the sentence [start, end) begins at [ms] in the audio. */
class SyncPoint(val start: Int, val end: Int, val ms: Long)

/** One audio file of a render, starting at [startMs] of the whole. */
class RenderPart(val file: File, val startMs: Long, val ms: Long)

object Renders {

    private fun root(ctx: Context) = File(ctx.filesDir, "renders").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)
    fun audio(ctx: Context, id: String) = File(dir(ctx, id), "audio.m4a")   // single-file renders (before parts)
    fun partFile(ctx: Context, id: String, i: Int) = File(dir(ctx, id), "audio_$i.m4a")
    private fun syncFile(ctx: Context, id: String) = File(dir(ctx, id), "sync.bin")
    private fun metaFile(ctx: Context, id: String) = File(dir(ctx, id), "meta.txt")

    fun save(ctx: Context, r: Render) {
        dir(ctx, r.id).mkdirs()
        metaFile(ctx, r.id).writeText(listOf(r.id, r.bookId, r.fromChar, r.toChar, r.label, r.voiceId, r.settings,
            r.status, r.durationMs, r.created, r.doneChar, r.parts.joinToString(","), r.error.replace('\n', ' ')).joinToString("\n"))
    }

    fun load(ctx: Context, id: String): Render? = runCatching {
        val l = metaFile(ctx, id).readLines()
        val status = l[7]
        val parts = l.getOrNull(11).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        Render(l[0], l[1], l[2].toInt(), l[3].toInt(), l[4], l[5], l[6], status, l[8].toLong(), l[9].toLong(),
            doneChar = l.getOrNull(10)?.toIntOrNull() ?: if (status == "ready") l[3].toInt() else l[2].toInt(), parts = parts,
            error = l.getOrNull(12).orEmpty())
    }.getOrNull()

    /** The audio files to play, in order. */
    fun parts(ctx: Context, r: Render): List<RenderPart> {
        if (r.parts.isEmpty()) return if (r.ready && audio(ctx, r.id).exists()) listOf(RenderPart(audio(ctx, r.id), 0, r.durationMs)) else emptyList()
        var at = 0L
        return r.parts.mapIndexed { i, ms -> RenderPart(partFile(ctx, r.id, i), at, ms).also { at += ms } }
    }

    fun list(ctx: Context, bookId: String): List<Render> =
        root(ctx).listFiles().orEmpty().mapNotNull { load(ctx, it.name) }.filter { it.bookId == bookId }.sortedBy { it.created }

    fun delete(ctx: Context, r: Render) {
        dir(ctx, r.id).deleteRecursively()
        if (activeId(ctx, r.bookId) == r.id) setActive(ctx, r.bookId, null)
    }

    fun sizeBytes(ctx: Context, r: Render) = dir(ctx, r.id).listFiles().orEmpty().sumOf { it.length() }

    // ---------- which file the reader plays ----------

    /** [activeId] value for "read live, don't play pre-loaded audio in this book". */
    const val LIVE = "live"

    /** The file preferred where several cover the same text (the last one played), or [LIVE]. */
    fun activeId(ctx: Context, bookId: String) = BookStore.prefs(ctx).getString("render_active_$bookId", null)
    fun setActive(ctx: Context, bookId: String, id: String?) =
        BookStore.prefs(ctx).edit().apply { if (id == null) remove("render_active_$bookId") else putString("render_active_$bookId", id) }.apply()

    /**
     * The file whose audio covers [pos] (finished, or the part made so far): the active one if it does, else any
     * other (finished ones first, then the newest). None when the book is set to read live.
     */
    fun playableAt(ctx: Context, bookId: String, pos: Int): Render? {
        val active = activeId(ctx, bookId)
        if (active == LIVE) return null
        fun covers(r: Render) = r.playable && pos >= r.fromChar && pos < r.coveredTo
        active?.let { load(ctx, it) }?.takeIf(::covers)?.let { return it }
        return list(ctx, bookId).filter(::covers).maxWithOrNull(compareBy<Render>({ it.ready }, { it.created }))
    }

    /**
     * How much of [from, to) (a page) plays pre-loaded: all of it (one file after another, as the reader moves on
     * between them), part of it (a file still being made, or one that starts or ends inside it), or none.
     */
    fun coverage(ctx: Context, bookId: String, from: Int, to: Int): Coverage {
        if (activeId(ctx, bookId) == LIVE) return Coverage.NONE
        var pos = from
        while (pos < to) pos = playableAt(ctx, bookId, pos)?.coveredTo?.takeIf { it > pos } ?: break
        if (pos >= to) return Coverage.FULL
        return if (list(ctx, bookId).any { it.playable && it.fromChar < to && it.coveredTo > from }) Coverage.PARTIAL else Coverage.NONE
    }

    enum class Coverage { FULL, PARTIAL, NONE }

    // ---------- sync map ----------

    fun writeSync(ctx: Context, id: String, points: List<SyncPoint>) =
        DataOutputStream(syncFile(ctx, id).outputStream().buffered()).use { o ->
            o.writeInt(points.size)
            for (p in points) { o.writeInt(p.start); o.writeInt(p.end); o.writeLong(p.ms) }
        }

    /**
     * The sync map, one point per sentence. Files store a point for each clause of a sentence cut at commas
     * (same sentence end, later start); those are dropped so the highlight stays on the whole sentence instead of
     * shrinking clause by clause.
     */
    fun readSync(ctx: Context, id: String, keepClauses: Boolean = false): List<SyncPoint> = runCatching {
        val all = DataInputStream(syncFile(ctx, id).inputStream().buffered()).use { i ->
            List(i.readInt()) { SyncPoint(i.readInt(), i.readInt(), i.readLong()) }
        }
        if (keepClauses) all else all.fold(ArrayList<SyncPoint>()) { out, p ->
            val last = out.lastOrNull()
            if (last == null || p.end != last.end || p.start <= last.start) out += p
            out
        }
    }.getOrDefault(emptyList())
}

/** Streams mono float PCM into an AAC .m4a file (MediaCodec + MediaMuxer). */
class AacWriter(file: File, private val rate: Int, bitrate: Int = 64_000) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var queuedSamples = 0L
    /** Samples written so far = position in the file. */
    var samplesWritten = 0L
        private set

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(samples: FloatArray, count: Int = samples.size) {
        var off = 0
        while (off < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf((buf.capacity() / 2), count - off)
            for (i in 0 until n) {
                val v = (samples[off + i].coerceIn(-1f, 1f) * 32767).toInt()
                buf.put((v and 0xFF).toByte()); buf.put(((v shr 8) and 0xFF).toByte())
            }
            codec.queueInputBuffer(idx, 0, n * 2, queuedSamples * 1_000_000L / rate, 0)
            queuedSamples += n
            off += n
            drain(false)
        }
        samplesWritten += count
    }

    private val zeros = FloatArray(4096)
    fun silence(count: Int) {
        var left = count
        while (left > 0) { val n = minOf(zeros.size, left); write(zeros, n); left -= n }
    }

    fun finish() {
        // the end-of-stream mark needs a free input buffer, and a busy (or throttled) encoder can be holding them all:
        // take its output until one frees up. Without the mark, waiting for the end below never returned, and the
        // whole pre-load hung there (no progress, "Pause" never taken)
        val deadline = System.nanoTime() + 5_000_000_000L
        var marked = false
        while (!marked && System.nanoTime() < deadline) {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, queuedSamples * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                marked = true
            } else drain(false)
        }
        if (marked) drain(true, deadline)
        else AppLog.e("Render", "encoder never took the end mark: the part ends at the audio it gave back")
        runCatching { codec.stop() }; codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }

    /** Drop the partial file without finishing it. */
    fun abort() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
    }

    /** Take what the encoder has made; at the end, until its end mark comes out (or [deadline], nanoTime, passes). */
    private fun drain(endOfStream: Boolean, deadline: Long = Long.MAX_VALUE) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxing) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else if (System.nanoTime() > deadline) {
                    AppLog.e("Render", "encoder never gave back its end mark: the part ends at the audio it gave back")
                    return
                }
            }
        }
    }
}
