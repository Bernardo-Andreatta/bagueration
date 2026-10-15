package com.bernardo.ereader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Offline narrator: Kokoro-82M (int8) running through sherpa-onnx.
 * Splits the book into sentences starting at any char offset, synthesizes ahead on one
 * thread and plays on another so there is (usually) no gap between sentences.
 */
class Narrator(context: Context, @Volatile var listener: Listener) {
    // application context: the narrator can outlive an activity while an audio file is being made
    private val ctx: Context = context.applicationContext

    interface Listener {
        fun onReady()
        fun onSentence(start: Int, end: Int)
        /** true while waiting for audio (model loading or synthesis falling behind). */
        fun onBuffering(waiting: Boolean)
        fun onFinished()
        fun onError(msg: String)
    }

    companion object {

        /** The app's narrator, for the settings screen (live changes + preview). */
        @Volatile var instance: Narrator? = null
        private const val MAX_SENTENCE = 300
        private const val MIN_CLAUSE_WORDS = 3

        /** Abbreviations whose period doesn't end a sentence (lowercase, no dot). */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "mx", "dr", "st", "jr", "sr", "sra", "srta", "dra", "prof", "rev", "fr", "hon",
            "mt", "ft", "vs", "etc", "e.g", "i.e", "cf", "approx", "vol", "vols", "ch", "pp", "fig",
            "sgt", "capt", "col", "gen", "lt", "maj", "cmdr", "adm", "gov", "sen", "pres", "insp",
            "jan", "feb", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "inc", "ltd", "corp",
            // other languages
            "profa", "ud", "uds", "sig", "dott", "hr", "usw", "z.b", "bzw", "ecc", "p.ex", "sig.ra",
        )

        /** How abbreviations are spoken. */
        private val SPOKEN = listOf(
            "Mrs." to "Missus", "Mr." to "Mister", "Ms." to "Miz", "Mx." to "Mix", "Dr." to "Doctor", "Prof." to "Professor",
            "Rev." to "Reverend", "Fr." to "Father", "Jr." to "Junior", "Sr." to "Senior", "Capt." to "Captain",
            "Sgt." to "Sergeant", "Lt." to "Lieutenant", "Col." to "Colonel", "Gen." to "General", "Gov." to "Governor",
            "Insp." to "Inspector", "Det." to "Detective", "Mt." to "Mount", "vs." to "versus", "etc." to "etcetera",
            "e.g." to "for example", "i.e." to "that is", "approx." to "approximately",
        ).map { (abbr, word) -> Regex("(?<![\\p{L}.])${Regex.escape(abbr)}") to word } +
            listOf(Regex("(?<![\\p{L}.])No\\.(?=\\s*\\d)") to "Number") +
            // "St. Petersburg" -> Saint, "Baker St." -> Street
            listOf(Regex("(?<![\\p{L}.])St\\.(?=\\s+\\p{Lu})") to "Saint", Regex("(?<![\\p{L}.])St\\.") to "Street")
        private fun spoken(vararg pairs: Pair<String, String>) =
            pairs.map { (abbr, word) -> Regex("(?<![\\p{L}.])${Regex.escape(abbr)}") to word }

        private val SPOKEN_BY_LANG: Map<String, List<Pair<Regex, String>>> = mapOf(
            "en" to SPOKEN,
            "pt" to spoken("Srta." to "Senhorita", "Sra." to "Senhora", "Sr." to "Senhor", "Dra." to "Doutora", "Dr." to "Doutor",
                "Profa." to "Professora", "Prof." to "Professor", "etc." to "etcétera", "p. ex." to "por exemplo"),
            "es" to spoken("Srta." to "Señorita", "Sra." to "Señora", "Sr." to "Señor", "Dra." to "Doctora", "Dr." to "Doctor",
                "Uds." to "ustedes", "Ud." to "usted", "Prof." to "Profesor", "etc." to "etcétera"),
            "fr" to spoken("Mme" to "Madame", "Mlle" to "Mademoiselle", "M." to "Monsieur", "Dr" to "Docteur", "etc." to "et cetera"),
            "it" to spoken("Sig.ra" to "Signora", "Sig." to "Signor", "Dott.ssa" to "Dottoressa", "Dott." to "Dottor", "ecc." to "eccetera"),
            "de" to spoken("Hr." to "Herr", "Fr." to "Frau", "Dr." to "Doktor", "z.B." to "zum Beispiel", "usw." to "und so weiter", "bzw." to "beziehungsweise"),
        )

    }

    /**
     * One engine at a time, on one worker thread. It's rebuilt when the voice needs a different model
     * (Kokoro in another language, or a downloaded Piper voice).
     */
    private var engine: OfflineTts? = null
    private var engineKey: String? = null
    private var dataDir: String? = null
    private var pool: ExecutorService? = null
    @Volatile private var ready = false
    /** True while a model is being (re)loaded, e.g. after switching to a voice in another language. */
    @Volatile var loadingEngine = false

    init { instance = this }
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    /** Voice id from [Voices] ("kokoro:3", "piper:de_DE-thorsten-medium"). */
    @Volatile var voiceId = Voices.DEFAULT_ID
    @Volatile var speed = 1.0f
    /** Silence after a sentence; scaled by punctuation (commas less, ?! more, paragraphs double). */
    @Volatile var pauseMs = 400
    /** Split long sentences at commas for a real pause there. Off = whole sentences, Kokoro's own comma timing. */
    @Volatile var commaPauses = true
    /** Silence at a comma (; and : get 1.5x). */
    @Volatile var commaPauseMs = 180
    /** Gaps inside a phrase longer than this are shortened to it. 0 = off. */
    @Volatile var maxGapMs = 90
    /** How much text goes to the model at once: 1 = a sentence, 2 = up to 3 sentences, 3 = up to a paragraph. */
    @Volatile var phrasing = 2
    /** Pronunciation fixes for the current book (word -> how to say it), applied before anything else. */
    @Volatile var pronunciations: List<Pair<Regex, String>> = emptyList()
    val isPreviewing get() = session?.preview == true
    var threads = 4
    val isReady get() = ready
    val isPlaying get() = session != null || filePlayer != null
    val isPlayingFile get() = filePlayer != null

    // ---------- buffering profile, picked from the phone's memory ----------
    private val totalRamMb: Long = runCatching {
        android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem / 1_048_576
    }.getOrDefault(4_000L)
    /** How much audio to keep ready ahead of the listener (audio costs ~5.5 MB per minute in memory). */
    private val baseAheadMs = when { totalRamMb >= 7_000 -> 300_000L; totalRamMb >= 5_000 -> 180_000L; else -> 90_000L }
    /** Generation time / audio time, smoothed. Above ~1 the phone can't keep up (usually thermal throttling). */
    @Volatile private var rtf = 0.6
    /** Slow phone (or throttling): keep a bigger lead so stalls are absorbed. */
    val currentRtf get() = rtf
    private val aheadTargetMs get() = if (rtf > 0.8) baseAheadMs * 3 / 2 else baseAheadMs

    fun init() = thread(name = "tts-init") {
        try {
            dataDir = copyAssetDir("kokoro/espeak-ng-data", "espeak-ng-data-v1")
            // audio priority for the voice worker; the engine's own threads are created from it and inherit it.
            // Android still moves background apps to slower cores, but this keeps us ahead of other work.
            pool = Executors.newSingleThreadExecutor { r ->
                Thread({
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
                    r.run()
                }, "tts-worker")
            }
            // load the current voice's model up front so the first sentence starts quickly
            pool!!.submit { runCatching { engineFor(Voices.byId(voiceId) ?: Voices.byId(Voices.DEFAULT_ID)!!) } }.get()
            ready = true
            AppLog.i("Narrator", "buffer profile: RAM ${totalRamMb} MB -> ${baseAheadMs / 1000}s ahead, memory cache ${cacheBudget * 4 / 1_048_576} MB, disk cache 300 MB")
            main.post { listener.onReady() }
        } catch (t: Throwable) {
            AppLog.e("Narrator", "TTS init failed", t)
            main.post { listener.onError("TTS init failed: ${t.message}") }
        }
    }

    /** Returns an engine able to speak [v], loading it if needed. Only call on the worker thread. */
    private fun engineFor(v: VoiceInfo): OfflineTts {
        val key = if (v.builtIn) "kokoro:${Voices.espeakLang(v)}" else "piper:${v.piper}"
        engine?.takeIf { key == engineKey }?.let { return it }
        AppLog.i("Narrator", "loading engine $key (voice ${v.id}, threads $threads)")
        loadingEngine = true
        try {
            engine?.release()
            engine = null
            engineKey = null
            val e = if (v.builtIn) {
                val lang = Voices.espeakLang(v)
                OfflineTts(assetManager = ctx.assets, config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "kokoro/model.onnx",
                        voices = "kokoro/voices.bin",
                        tokens = "kokoro/tokens.txt",
                        lexicon = if (v.lang == "en") "kokoro/lexicon-us-en.txt" else "",
                        lang = lang,
                        dataDir = dataDir!!,
                    ),
                    numThreads = threads, debug = false, provider = "cpu",
                )))
            } else {
                val dir = Voices.piperDir(ctx, v)
                val model = dir.listFiles().orEmpty().first { it.name.endsWith(".onnx") }
                OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = dataDir!!,
                    ),
                    numThreads = threads, debug = false, provider = "cpu",
                )))
            }
            engine = e
            engineKey = key
            AppLog.i("Narrator", "engine loaded: $key")
            return e
        } finally {
            loadingEngine = false
        }
    }

    fun start(text: String, from: Int) {
        stop()
        stopPrefetch()
        session = Session(text, from, false).also { it.begin() }
    }

    private var prefetchSession: Session? = null

    /**
     * Not reading (paused, or a book was just opened): keep generating ahead from [from] into the cache, up to the
     * same lead as when reading, so pressing play starts instantly and with a full buffer.
     */
    fun prefetch(text: String, from: Int) {
        if (session != null || filePlayer != null || renderSession != null) return
        stopPrefetch()
        prefetchSession = Session(text, from, false, prefetchOnly = true).also { it.begin() }
    }

    fun stopPrefetch() {
        prefetchSession?.cancel()
        prefetchSession = null
    }

    /** Speak a sample with the current settings, or with [withVoice] (no highlighting / progress callbacks). */
    fun preview(text: String, withVoice: String? = null) {
        stop()
        stopPrefetch()
        session = Session(text, 0, true, withVoice).also { it.begin() }
    }

    fun stop() {
        session?.cancel()
        session = null
        filePlayer?.release()
        filePlayer = null
    }

    // ---------- renders: make an audio file without playing it ----------

    private var renderSession: Session? = null
    val isRendering get() = renderSession != null

    fun render(text: String, from: Int, until: Int, spec: RenderSpec, out: RenderOut) {
        cancelRender()
        stopPrefetch()
        val wrapped = RenderOut(out.writerFor, out.onProgress,
            onDone = { ms, sync -> renderSession = null; out.onDone(ms, sync) },
            onError = { msg -> renderSession = null; out.onError(msg) })
        renderSession = Session(text, from, false, until = until, spec = spec, out = wrapped).also { it.begin() }
    }

    fun cancelRender() {
        renderSession?.cancel()
        renderSession = null
    }

    // ---------- playing a finished audio file, highlight driven by its sync map ----------

    private var filePlayer: FilePlayer? = null

    /** Plays [r] from the sentence at [from]; when the file ends, reading continues live from where it stops. */
    fun playRender(r: Render, text: String, from: Int) {
        stop()
        stopPrefetch()
        val sync = Renders.readSync(ctx, r.id)
        if (sync.isEmpty()) return start(text, from)
        filePlayer = FilePlayer(r, text, sync, from)
    }

    private inner class FilePlayer(private val r: Render, private val text: String, private val sync: List<SyncPoint>, from: Int) {
        private val mp = android.media.MediaPlayer()
        @Volatile private var released = false
        private var shown = -1
        private val tick = object : Runnable {
            override fun run() {
                if (released) return
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                // last sync point at or before the current position
                var lo = 0; var hi = sync.size - 1; var idx = 0
                while (lo <= hi) { val mid = (lo + hi) / 2; if (sync[mid].ms <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
                if (idx != shown) { shown = idx; listener.onSentence(sync[idx].start, sync[idx].end) }
                main.postDelayed(this, 120)
            }
        }

        init {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(Renders.audio(ctx, r.id).path)
            mp.prepare()
            val startAt = sync.lastOrNull { it.start <= from }?.ms ?: 0L
            mp.seekTo(startAt, android.media.MediaPlayer.SEEK_CLOSEST)
            mp.setOnCompletionListener {
                if (released) return@setOnCompletionListener
                AppLog.i("Narrator", "audio file ${r.id} finished, continuing live at ${r.toChar}")
                release()
                filePlayer = null
                if (r.toChar < text.length) start(text, r.toChar) else listener.onFinished()
            }
            mp.start()
            listener.onBuffering(false)
            main.post(tick)
            AppLog.i("Narrator", "playing audio file ${r.id} from ${startAt}ms")
        }

        fun release() {
            if (released) return
            released = true
            main.removeCallbacks(tick)
            runCatching { mp.stop() }
            mp.release()
        }
    }

    /**
     * A setting that changes how speech is generated (voice, speed, commas, trimming) was changed while the
     * book is playing: re-render from the sentence being heard. Pause lengths apply live and don't need this.
     */
    fun applyToPlaying() {
        if (filePlayer != null) return   // a pre-made audio file can't change; settings apply to live reading
        val s = session ?: return
        if (s.preview) return
        start(s.bookText, s.playingStart)
    }

    fun release() {
        stop()
        stopPrefetch()
        cancelRender()
        if (instance === this) instance = null
        ready = false
        val p = pool
        p?.shutdownNow()
        thread {
            p?.awaitTermination(10, TimeUnit.SECONDS)
            engine?.release()
            engine = null
        }
    }

    /** espeak-ng needs real files on disk, so copy them out of the APK once. */
    private fun copyAssetDir(assetPath: String, outName: String): String {
        File(ctx.filesDir, "espeak-ng-data").takeIf { it.exists() }?.deleteRecursively() // v0.19 leftovers
        val out = File(ctx.filesDir, outName)
        val marker = File(out, ".copied")
        if (marker.exists()) return out.absolutePath
        fun copy(path: String, dst: File) {
            val children = ctx.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                dst.parentFile?.mkdirs()
                ctx.assets.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } else {
                dst.mkdirs()
                for (c in children) copy("$path/$c", File(dst, c))
            }
        }
        copy(assetPath, out)
        marker.createNewFile()
        return out.absolutePath
    }

    /** Audio for one sentence ([start, end) in the book). */
    // ---------- audio cache: recently generated sentences, so jumping back is instant ----------

    private class Cached(val samples: FloatArray, val rate: Int)
    private val cache = LinkedHashMap<String, Cached>(64, 0.75f, true)   // access order = LRU
    private var cacheSamples = 0L
    private val cacheBudget: Long get() = (baseAheadMs * 3 / 2 + 180_000L) / 1000 * 24_000   // floats in memory

    private fun cacheKey(text: String, v: String, sp: Float, gap: Int = maxGapMs) = "$v|$sp|$gap|$text"

    /** Memory first, then the disk cache (which survives app restarts). */
    private fun cacheGet(key: String): Cached? {
        synchronized(cache) { cache[key] }?.let { return it }
        return diskGet(key)?.also { c -> synchronized(cache) { cache[key] = c; cacheSamples += c.samples.size } }
    }

    // generated audio is also kept on disk as 16-bit PCM (~2.9 MB/min), capped, oldest dropped first
    private val diskDir by lazy { File(ctx.cacheDir, "tts").apply { mkdirs() } }
    private val diskCapBytes = 300L * 1024 * 1024
    private var diskPuts = 0

    private fun diskFile(key: String): File {
        val h = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(diskDir, "$h.pcm")
    }

    private fun diskGet(key: String): Cached? = runCatching {
        val f = diskFile(key)
        if (!f.exists()) return null
        java.io.DataInputStream(f.inputStream().buffered()).use { i ->
            val rate = i.readInt(); val n = i.readInt()
            val bytes = ByteArray(n * 2).also { i.readFully(it) }
            val sh = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            f.setLastModified(System.currentTimeMillis())
            Cached(FloatArray(n) { sh.get(it) / 32767f }, rate)
        }
    }.getOrNull()

    private fun diskPut(key: String, c: Cached) {
        runCatching {
            val f = diskFile(key)
            val tmp = File(f.path + ".tmp")
            val buf = java.nio.ByteBuffer.allocate(8 + c.samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.order(java.nio.ByteOrder.BIG_ENDIAN).putInt(c.rate).putInt(c.samples.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in c.samples) buf.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            tmp.writeBytes(buf.array())
            tmp.renameTo(f)
            if (++diskPuts % 25 == 0) {
                val files = diskDir.listFiles().orEmpty().sortedBy { it.lastModified() }
                var total = files.sumOf { it.length() }
                for (old in files) { if (total <= diskCapBytes) break; total -= old.length(); old.delete() }
            }
        }
    }

    private fun cachePut(key: String, c: Cached) = synchronized(cache) {
        cache.put(key, c)?.let { cacheSamples -= it.samples.size }
        cacheSamples += c.samples.size
        val it = cache.entries.iterator()
        while (cacheSamples > cacheBudget && it.hasNext()) {
            cacheSamples -= it.next().value.samples.size
            it.remove()
        }
    }

    /**
     * Audio for one model call. [start, end) covers it in the book; [subs] are the sentences inside it as
     * (start, end, fraction of the way through the text) so the highlight can step sentence by sentence.
     */
    private class Chunk(val start: Int, val end: Int, val samples: FloatArray, val pauseFactor: Float = 1f,
                        val clause: Boolean = false, val rate: Int = 24000, val subs: List<Triple<Int, Int, Float>> = emptyList())

    /** Settings frozen for a render (live sessions follow the sliders instead). */
    class RenderSpec(val voiceId: String, val speed: Float, val pauseMs: Int, val commaPauses: Boolean,
                     val commaPauseMs: Int, val maxGapMs: Int, val phrasing: Int)

    /** Where a render's audio goes, and who hears about progress. */
    class RenderOut(val writerFor: (Int) -> AacWriter, val onProgress: (Float, Long) -> Unit,
                    val onDone: (Long, List<SyncPoint>) -> Unit, val onError: (String) -> Unit)

    /** A queued piece: its audio (when ready) and an estimate of how long it'll play until then. */
    private class Item(val f: Future<Chunk?>, val estMs: Long)

    private inner class Session(private val text: String, private val from: Int, val preview: Boolean,
                                private val voiceOverride: String? = null, val prefetchOnly: Boolean = false,
                                private val until: Int = text.length, private val spec: RenderSpec? = null,
                                private val out: RenderOut? = null) {
        private val rendering = out != null
        private val voiceInfo = Voices.byId(spec?.voiceId ?: voiceOverride ?: voiceId) ?: Voices.byId(Voices.DEFAULT_ID)!!
        private val sid = Voices.effectiveSid(ctx, voiceInfo)
        // settings: frozen for renders, live otherwise
        private val cSpeed get() = spec?.speed ?: speed
        private val cPause get() = spec?.pauseMs ?: pauseMs
        private val cCommaPauses get() = spec?.commaPauses ?: commaPauses
        private val cCommaPause get() = spec?.commaPauseMs ?: commaPauseMs
        private val cGap get() = spec?.maxGapMs ?: maxGapMs
        private val cPhrasing get() = spec?.phrasing ?: phrasing
        @Volatile private var cancelled = false
        // in-order pieces; how far ahead we generate is limited by audio time (aheadTargetMs), not count
        private val queue = java.util.concurrent.LinkedBlockingQueue<Item>()
        private val endMarker = Chunk(-1, -1, FloatArray(0))
        private val endFuture = FutureTask<Chunk?> { endMarker }.also { it.run() }
        @Volatile var playingStart = from
        val bookText get() = text
        private var genThread: Thread? = null
        private var playThread: Thread? = null

        fun begin() {
            genThread = thread(name = "tts-gen") { generate() }
            when {
                rendering -> playThread = thread(name = "tts-render") { renderToFile() }
                !prefetchOnly -> playThread = thread(name = "tts-play") { play() }
            }
        }

        /** Render mode: write every piece (and its pauses) into the audio file, noting where each sentence starts. */
        private fun renderToFile() {
            val o = out!!
            var writer: AacWriter? = null
            val sync = ArrayList<SyncPoint>()
            var rate = 24000
            try {
                while (!cancelled) {
                    val f = queue.take().f
                    val c = try { f.get() } catch (_: java.util.concurrent.CancellationException) { null } ?: continue
                    if (c === endMarker) break
                    if (writer == null) { rate = c.rate; writer = o.writerFor(rate) }
                    val base = writer.samplesWritten
                    val subs = if (c.subs.isEmpty()) listOf(Triple(c.start, c.end, 0f)) else c.subs
                    for ((a, b, frac) in subs) sync += SyncPoint(a, b, (base + (frac * c.samples.size).toLong()) * 1000 / rate)
                    writer.write(c.samples)
                    writer.silence((rate * (if (c.clause) cCommaPause else cPause) / 1000f * c.pauseFactor).toInt())
                    val done = ((c.end - from).toFloat() / (until - from).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val audioMs = writer.samplesWritten * 1000 / rate
                    main.post { o.onProgress(done, audioMs) }
                }
                if (cancelled) { writer?.abort(); return }
                val total = writer?.let { it.samplesWritten * 1000 / rate } ?: 0L
                writer?.finish()
                main.post { o.onDone(total, sync) }
            } catch (_: InterruptedException) {
                writer?.abort()
            } catch (t: Throwable) {
                AppLog.e("Narrator", "render failed", t)
                writer?.abort()
                main.post { o.onError(t.message ?: t.javaClass.simpleName) }
            }
        }

        /** Audio ready or being generated ahead of the listener (pauses included, estimates for unfinished pieces). */
        private fun aheadMs(): Long = queue.sumOf { item ->
            if (item.f.isDone) durationMs(item.f) + cPause else item.estMs
        }

        fun cancel() {
            cancelled = true
            genThread?.interrupt()
            playThread?.interrupt()
            while (true) (queue.poll() ?: break).f.cancel(false)
        }

        private fun generate() {
            try {
                while (!ready && !cancelled) Thread.sleep(100) // still loading the model
                val pool = pool ?: return
                var pos = from.coerceIn(0, text.length)
                while (!cancelled && pos < until) {
                    // a render steps aside while you're listening live, and picks up when you stop
                    while (rendering && !cancelled && session != null) Thread.sleep(500)
                    val (s, e0) = nextSentence(pos, MAX_SENTENCE) ?: break
                    var e = e0
                    // longer phrasing: give the model several sentences (never across a paragraph) so it can
                    // carry intonation and timing from one sentence into the next
                    val sentences = arrayListOf(s to e0)
                    if (cPhrasing > 1) {
                        val (maxSentences, maxChars) = if (cPhrasing == 2) 3 to 300 else 8 to 480
                        while (sentences.size < maxSentences && !isParagraphEnd(e)) {
                            val next = nextSentence(e, MAX_SENTENCE) ?: break
                            if (next.second - s > maxChars) break
                            sentences += next
                            e = next.second
                        }
                    }
                    pos = e
                    if ((s until e).none { text[it].isLetterOrDigit() }) continue
                    // comma pauses: split at commas between clauses of 3+ words (exact pause, no word cuts, each clause
                    // keeps its comma so the intonation still "continues"). Works with any phrasing: sentences without
                    // commas still go to the model together.
                    val parts = clauses(s, e)
                    for ((k, part) in parts.withIndex()) {
                        val shown = text.substring(part.first, part.second).trim()
                        // sentences inside this piece, for sentence-by-sentence highlighting
                        val pSpan = (part.second - part.first).coerceAtLeast(1).toFloat()
                        val subs = sentences.filter { (a, b) -> b > part.first && a < part.second }.map { (a, b) ->
                            Triple(maxOf(a, part.first), b, (maxOf(a, part.first) - part.first) / pSpan)
                        }
                        val sentence = speakable(shown, voiceInfo.lang)
                        val factor = if (k < parts.size - 1) clausePause(shown) else sentencePause(shown, e)
                        val v = "${voiceInfo.id}#$sid"; val sp = cSpeed
                        val isClause = k < parts.size - 1
                        val key = cacheKey(sentence, v, sp, cGap)
                        // recently generated (heard or buffered ahead)? use it right away, no engine wait
                        // stay a set amount of listening time ahead (bigger on phones that generate slowly)
                        while (!cancelled && aheadMs() > aheadTargetMs) Thread.sleep(300)
                        if (cancelled) return
                        val cached = cacheGet(key)
                        val job: Future<Chunk?> = if (cached != null) {
                            FutureTask<Chunk?> { Chunk(s, e, cached.samples, factor, isClause, cached.rate, subs) }.also { it.run() }
                        } else pool.submit<Chunk?> {
                            if (cancelled) return@submit null
                            cacheGet(key)?.let { return@submit Chunk(s, e, it.samples, factor, isClause, it.rate, subs) }
                            run {
                                val eng = engineFor(voiceInfo)
                                if (cancelled) return@submit null
                                val t0 = System.nanoTime()
                                val audio = eng.generate(sentence, sid, sp)
                                AppLog.i("Narrator", "gen ${(System.nanoTime() - t0) / 1_000_000}ms for " +
                                    "${audio.samples.size * 1000L / audio.sampleRate}ms audio, ${sentence.length} chars")
                                val samples = squeezePauses(trimSilence(audio.samples, audio.sampleRate), audio.sampleRate, sentence)
                                val genMs = (System.nanoTime() - t0) / 1_000_000.0
                                val audioMs = audio.samples.size * 1000.0 / audio.sampleRate
                                if (audioMs > 300) rtf = rtf * 0.8 + (genMs / audioMs) * 0.2
                                val c = Cached(samples, audio.sampleRate)
                                if (!rendering) {   // renders don't push live audio out of the caches
                                    cachePut(key, c)   // keep it even if this session was cancelled
                                    diskPut(key, c)
                                }
                                Chunk(s, e, samples, factor, isClause, audio.sampleRate, subs)
                            }
                        }
                        // ~14.5 characters per second of speech at 1x
                        queue.put(Item(job, (sentence.length / (14.5 * sp) * 1000).toLong() + cPause))
                        if (cancelled) { job.cancel(false); return }
                    }
                }
                queue.put(Item(endFuture, 0))
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                AppLog.e("Narrator", "generation failed", t)
                main.post { listener.onError("TTS error: ${t.message}") }
            }
        }

        /** Next sentence starting at/after pos: ends after . ! ? … (+ closing quotes), at a newline, or at MAX_SENTENCE chars. */
        private fun nextSentence(pos: Int, max: Int): Pair<Int, Int>? {
            var s = pos
            while (s < text.length && text[s].isWhitespace()) s++
            if (s >= text.length) return null
            val limit = minOf(text.length, s + max)
            var i = s
            var e = -1
            while (i < limit) {
                val c = text[i]
                if (c == '\n') { e = i; break }
                if (c == '.' && isAbbreviation(i)) { i++; continue }
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    var j = i + 1
                    while (j < text.length && text[j] in ".!?…\"'”’»)]") j++
                    // a period followed by a lowercase letter / digit is probably an abbreviation ("Mr. smith", "3.5")
                    if (j >= text.length || (text[j].isWhitespace() && !nextIsLower(j))) { e = j; break }
                    i = j
                    continue
                }
                i++
            }
            if (e < 0) {
                e = limit
                if (limit < text.length) {
                    val window = text.substring(s, limit)
                    val cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "))
                        .takeIf { it > max / 3 } ?: window.lastIndexOf(' ').takeIf { it > 0 }
                    if (cut != null) e = s + cut + 1
                }
            }
            return s to e
        }

        private fun lastPunct(clause: String) = clause.trimEnd().trimEnd { it in "\"'”’»)]" }.lastOrNull() ?: ' '

        /** Relative to commaPauseMs. */
        private fun clausePause(clause: String) = when (lastPunct(clause)) {
            ',' -> 1f
            ';', ':' -> 1.5f
            '—', '–', '-' -> 1.2f
            else -> 0.35f
        }

        private fun sentencePause(clause: String, e: Int): Float {
            val t = clause.trimEnd().trimEnd { it in "\"'”’»)]" }
            val f = when {
                t.endsWith("...") || t.endsWith("…") -> 1.6f
                t.endsWith("?") || t.endsWith("!") -> 1.25f
                t.endsWith(".") -> 1f
                else -> 0.45f   // sentence cut at MAX_SENTENCE
            }
            if (isParagraphEnd(e)) return maxOf(f, 2f)
            // "Hello!", "Yes." — a full stop-length pause after one or two words sounds like a hiccup
            val words = t.split(' ').count { w -> w.any { it.isLetterOrDigit() } }
            return if (words <= 2) minOf(f, 0.5f) else f
        }

        private fun clauses(s: Int, e: Int): List<Pair<Int, Int>> {
            if (!cCommaPauses) return listOf(s to e)
            fun words(a: Int, b: Int) = text.substring(a, b).split(' ', '\n').count { w -> w.any { it.isLetterOrDigit() } }
            val out = ArrayList<Pair<Int, Int>>()
            var start = s
            var i = s
            while (i < e) {
                val c = text[i]
                if (c == ',' || c == ';' || c == ':') {
                    var j = i + 1
                    while (j < e && text[j] in "\"'”’»)") j++
                    // "1,000" has no space after the comma and stays together
                    if (j < e && text[j].isWhitespace() && words(start, j) >= MIN_CLAUSE_WORDS && words(j, e) >= MIN_CLAUSE_WORDS) {
                        out += start to j
                        start = j
                    }
                    i = j
                    continue
                }
                i++
            }
            out += start to e
            return out
        }

        /** Text as it should be spoken: expand abbreviations the voice would otherwise trip over. */
        private fun speakable(t: String, lang: String): String {
            var out = t
            for ((re, say) in pronunciations) out = re.replace(out, Regex.escapeReplacement(say))
            for ((re, word) in SPOKEN_BY_LANG[lang].orEmpty()) out = re.replace(out, word)
            return out
        }

        /**
         * Some voices (bm_lewis especially) hesitate 150-280 ms between words, filled with breath at ~1/10
         * of speech level. Shorten any quiet stretch >= 130 ms inside a phrase to ~90 ms: only the middle
         * of the quiet part is removed (40 ms / 50 ms edges kept) and the splice is crossfaded, so words
         * are never clipped. Commas get their own pause separately.
         */
        private fun squeezePauses(x: FloatArray, rate: Int, spoken: String): FloatArray {
            val maxGap = cGap
            if (maxGap <= 0) return x
            val fr = rate / 100
            val n = x.size / fr
            if (n < 20) return x
            val quiet = BooleanArray(n) { f ->
                var sum = 0f
                for (i in f * fr until (f + 1) * fr) sum += x[i] * x[i]
                kotlin.math.sqrt(sum / fr) < 0.01f
            }
            val first = quiet.indexOfFirst { !it }
            val last = quiet.indexOfLast { !it }
            if (first < 0 || last <= first) return x
            // pauses at punctuation inside the text ("Hello?" he called) are real, not hesitations: estimate where
            // each mark falls in the audio and leave quiet runs within 300 ms of it alone
            val marks = Regex("[,;:?!.—…](?=[\\s\"'”’»)])").findAll(spoken).map { it.range.first }
                .filter { it < spoken.trimEnd().length - 2 }
                .map { first + (it + 1f) / spoken.length * (last - first) }.toList()
            fun protected(r0: Int, r1: Int) = marks.any { it >= r0 - 30 && it <= r1 + 30 }
            val headMs = minOf(40, maxGap / 2)
            val head = rate * headMs / 1000
            val tail = rate * (maxGap - headMs) / 1000
            val minRun = (maxGap + 40) / 10                   // only touch gaps clearly longer than the target
            val cuts = ArrayList<IntArray>()
            var r0 = -1
            for (k in first..last + 1) {
                val q = k <= last && quiet[k]
                if (q && r0 < 0) r0 = k
                else if (!q && r0 >= 0) {
                    if (k - r0 >= minRun && !protected(r0, k)) cuts += intArrayOf(r0 * fr + head, k * fr - tail)
                    r0 = -1
                }
            }
            if (cuts.isEmpty()) return x
            val xf = rate / 200                               // 5 ms crossfade
            val out = FloatArray(x.size)
            var o = 0
            var prev = 0
            for ((a, b) in cuts.map { it[0] to it[1] }) {
                if (b - a <= xf) continue
                System.arraycopy(x, prev, out, o, a - prev)
                o += a - prev
                for (i in 0 until xf) {                       // blend the end of the kept head into the tail
                    val w = i.toFloat() / xf
                    out[o + i] = x[a + i] * (1 - w) + x[b + i] * w
                }
                o += xf
                prev = b + xf
            }
            System.arraycopy(x, prev, out, o, x.size - prev)
            o += x.size - prev
            return out.copyOf(o)
        }

        /** Kokoro pads clips with its own silence; strip it so our pauses are what you hear. */
        private fun trimSilence(x: FloatArray, rate: Int): FloatArray {
            val th = 0.008f
            var a = 0
            while (a < x.size && kotlin.math.abs(x[a]) < th) a++
            var b = x.size - 1
            while (b > a && kotlin.math.abs(x[b]) < th) b--
            if (a >= b) return x
            a = maxOf(0, a - rate / 100 * 2)          // keep 20 ms before
            b = minOf(x.size - 1, b + rate / 100 * 5) // and 50 ms after (natural decay)
            return x.copyOfRange(a, b + 1)
        }

        private fun isParagraphEnd(e: Int): Boolean {
            var k = e
            while (k < text.length && text[k] == ' ') k++
            return k >= text.length || text[k] == '\n'
        }

        /** "Mr." / "Dr." / "e.g." / an initial like "J." before the period at [dot]. */
        private fun isAbbreviation(dot: Int): Boolean {
            var k = dot
            while (k > 0 && (text[k - 1].isLetter() || text[k - 1] == '.')) k--
            val word = text.substring(k, dot).lowercase()
            if (word.isEmpty()) return false
            if (word == "no" || word == "nos") return nextIsDigit(dot + 1)   // "No. 5" but not "He said no."
            return word in ABBREVIATIONS || (word.length == 1 && text[k].isUpperCase() && word != "i")
        }

        private fun nextIsDigit(from: Int): Boolean {
            var k = from
            while (k < text.length && text[k] == ' ') k++
            return k < text.length && text[k].isDigit()
        }

        private fun nextIsLower(from: Int): Boolean {
            var k = from
            while (k < text.length && text[k] == ' ') k++
            return k < text.length && (text[k].isLowerCase() || text[k].isDigit())
        }

        private fun durationMs(f: Future<Chunk?>): Long =
            runCatching { f.get() }.getOrNull()?.takeIf { it !== endMarker }?.let { it.samples.size * 1000L / it.rate } ?: 0L

        private fun awaitQuietly(f: Future<*>) {
            try { f.get() } catch (e: InterruptedException) { throw e } catch (_: Exception) {}
        }

        private fun play() {
            var track: AudioTrack? = null
            var rate = 24000
            val silenceBuf = FloatArray(4096)
            try {
                while (!cancelled) {
                    val f = queue.take().f
                    if (!f.isDone) {
                        // first start, or synthesis fell behind: refill a few clauses instead of
                        // stuttering one clause at a time
                        if (track != null) AppLog.i("Narrator", "UNDERRUN")
                        main.post { if (!cancelled && !preview) listener.onBuffering(true) }
                        awaitQuietly(f)
                        // refill by amount of audio: first start ~3 s (no gap after a tiny "Hello!"); after running dry
                        // 8 s, or 20 s when the phone is falling behind (throttling) so it doesn't stutter repeatedly
                        val wantMs = when { track == null -> 3_000L; rtf >= 0.95 -> 20_000L; else -> 8_000L }
                        if (track != null) AppLog.i("Narrator", "refilling ${wantMs / 1000}s (rtf ${"%.2f".format(rtf)})")
                        var readyMs = durationMs(f)
                        for (next in queue.toArray()) {
                            if (readyMs >= wantMs || cancelled) break
                            val nf = (next as Item).f
                            awaitQuietly(nf)
                            if (runCatching { nf.get() }.getOrNull() === endMarker) break
                            readyMs += durationMs(nf)
                        }
                    }
                    val c = try { f.get() } catch (_: java.util.concurrent.CancellationException) { null } ?: continue
                    if (c === endMarker) break
                    if (track != null && rate != c.rate) { track.run { stop(); release() }; track = null }
                    if (track == null) {
                        rate = c.rate
                        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(min * 2)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                        track.play()
                    }
                    // highlight sentence by sentence inside a multi-sentence chunk, timed by its share of the text
                    val subs = if (c.subs.size <= 1) listOf(Triple(c.subs.firstOrNull()?.first ?: c.start, c.end, 0f)) else c.subs
                    var nextSub = 0
                    fun announce(i: Int) {
                        val (a, b, _) = subs[i]
                        playingStart = a
                        if (!preview) main.post { if (!cancelled) listener.onSentence(a, b) }
                    }
                    // write in slices so stop() takes effect quickly
                    var off = 0
                    while (off < c.samples.size && !cancelled) {
                        while (nextSub < subs.size && off >= subs[nextSub].third * c.samples.size) announce(nextSub++)
                        val n = minOf(2048, c.samples.size - off)
                        track.write(c.samples, off, n, AudioTrack.WRITE_BLOCKING)
                        off += n
                    }
                    // pause before the next sentence (written as silence so it stays in sync with the highlight)
                    var silence = (rate * (if (c.clause) cCommaPause else cPause) / 1000f * c.pauseFactor).toInt()
                    while (silence > 0 && !cancelled) {
                        val n = minOf(silenceBuf.size, silence)
                        track.write(silenceBuf, 0, n, AudioTrack.WRITE_BLOCKING)
                        silence -= n
                    }
                }
                if (!cancelled && track != null) {
                    // let the tail of the buffer drain
                    Thread.sleep(400)
                }
            } catch (_: InterruptedException) {
            } finally {
                track?.run { pause(); flush(); release() }
                if (!cancelled) main.post {
                    if (session === this) session = null
                    listener.onFinished()
                }
            }
        }
    }
}
