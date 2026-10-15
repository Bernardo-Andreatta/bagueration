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
 * A pre-rendered stretch of a book: an AAC audio file plus a sync map (which sentence starts at which
 * millisecond), made with a fixed voice and settings. Several can exist for the same chapters.
 */
data class Render(
    val id: String,
    val bookId: String,
    val fromChar: Int,
    val toChar: Int,
    val label: String,          // "Chapters 3–7"
    val voiceId: String,
    val settings: String,       // VoicePreset.serialize() + "|phrasing"
    val status: String,         // processing, ready, failed, interrupted
    val durationMs: Long,
    val created: Long,
) {
    val ready get() = status == "ready"

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

object Renders {

    private fun root(ctx: Context) = File(ctx.filesDir, "renders").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)
    fun audio(ctx: Context, id: String) = File(dir(ctx, id), "audio.m4a")
    private fun syncFile(ctx: Context, id: String) = File(dir(ctx, id), "sync.bin")
    private fun metaFile(ctx: Context, id: String) = File(dir(ctx, id), "meta.txt")

    fun save(ctx: Context, r: Render) {
        dir(ctx, r.id).mkdirs()
        metaFile(ctx, r.id).writeText(listOf(r.id, r.bookId, r.fromChar, r.toChar, r.label, r.voiceId, r.settings,
            r.status, r.durationMs, r.created).joinToString("\n"))
    }

    fun load(ctx: Context, id: String): Render? = runCatching {
        val l = metaFile(ctx, id).readLines()
        Render(l[0], l[1], l[2].toInt(), l[3].toInt(), l[4], l[5], l[6], l[7], l[8].toLong(), l[9].toLong())
    }.getOrNull()

    fun list(ctx: Context, bookId: String): List<Render> =
        root(ctx).listFiles().orEmpty().mapNotNull { load(ctx, it.name) }.filter { it.bookId == bookId }.sortedBy { it.created }

    fun delete(ctx: Context, r: Render) {
        dir(ctx, r.id).deleteRecursively()
        if (activeId(ctx, r.bookId) == r.id) setActive(ctx, r.bookId, null)
    }

    fun sizeBytes(ctx: Context, r: Render) = dir(ctx, r.id).listFiles().orEmpty().sumOf { it.length() }

    // ---------- which file the reader plays ----------

    fun activeId(ctx: Context, bookId: String) = BookStore.prefs(ctx).getString("render_active_$bookId", null)
    fun setActive(ctx: Context, bookId: String, id: String?) =
        BookStore.prefs(ctx).edit().apply { if (id == null) remove("render_active_$bookId") else putString("render_active_$bookId", id) }.apply()

    /** The active, finished file that covers [pos], if any. */
    fun playableAt(ctx: Context, bookId: String, pos: Int): Render? =
        activeId(ctx, bookId)?.let { load(ctx, it) }?.takeIf { it.ready && pos >= it.fromChar && pos < it.toChar }

    // ---------- sync map ----------

    fun writeSync(ctx: Context, id: String, points: List<SyncPoint>) =
        DataOutputStream(syncFile(ctx, id).outputStream().buffered()).use { o ->
            o.writeInt(points.size)
            for (p in points) { o.writeInt(p.start); o.writeInt(p.end); o.writeLong(p.ms) }
        }

    fun readSync(ctx: Context, id: String): List<SyncPoint> = runCatching {
        DataInputStream(syncFile(ctx, id).inputStream().buffered()).use { i ->
            List(i.readInt()) { SyncPoint(i.readInt(), i.readInt(), i.readLong()) }
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
        val idx = codec.dequeueInputBuffer(50_000)
        if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, queuedSamples * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        runCatching { codec.stop() }; codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }

    /** Drop the partial file without finishing it. */
    fun abort() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxing) muxer.stop() }; runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
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
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
            }
        }
    }
}
