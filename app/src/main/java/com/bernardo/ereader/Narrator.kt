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
        /**
         * The word being heard, [start, end) in the book (estimated: see [WordTiming]), inside the sentence from
         * [onSentence]. At most ~15 times a second, only while [wordHighlight] is on; (-1, -1) = none.
         */
        fun onWord(start: Int, end: Int)
        /** true while waiting for audio (model loading or synthesis falling behind). */
        fun onBuffering(waiting: Boolean)
        fun onFinished()
        fun onError(msg: String)
    }

    companion object {

        /** The app's narrator, for the settings screen (live changes + preview). */
        @Volatile var instance: Narrator? = null
        private const val MAX_SENTENCE = 300
        /** Audio files are written in parts of about this long (closed at a sentence end). */
        private const val RENDER_PART_SEC = 120
        /** Smallest clause cut off at a comma/dash: "Well," may stand alone, but what follows needs 2+ words. */
        private const val MIN_CLAUSE_WORDS_BEFORE = 1
        private const val MIN_CLAUSE_WORDS_AFTER = 2
        private const val CLOSERS = "\"'”’»)]"
        /** Bump when the way audio is made changes, so cached audio from before isn't reused. */
        private const val AUDIO_VERSION = 3
        private val SPACED_ELLIPSIS = Regex("\\.( \\.){2,}")
        private val FOOTNOTE_MARK = Regex("(?<=\\p{L}[\"'”’»)]?)(\\[\\d{1,3}]|[¹²³⁴⁵⁶⁷⁸⁹⁰]+|\\*+)(?=[\\s\\p{P}]|$)")
        private val DOUBLE_HYPHEN = Regex("\\s*--+\\s*")

        private val VOWEL_GROUPS = Regex("[aeiouyàáâãäåèéêëìíîïòóôõöùúûüæœ]+", RegexOption.IGNORE_CASE)
        private val WORD = Regex("[\\p{L}]+")

        /** Rough syllable count (vowel groups; ~3 letters per syllable for scripts without Latin vowels). */
        fun syllables(t: String): Int {
            var n = 0
            for (m in WORD.findAll(t)) {
                val w = m.value.lowercase()
                var g = VOWEL_GROUPS.findAll(w).count()
                if (g == 0) g = (w.length + 2) / 3
                else if (g > 1 && w.endsWith('e') && !w.endsWith("le") && !w.endsWith("ee")) g--   // silent e
                n += maxOf(1, g)
            }
            return n + 2 * Regex("\\d+").findAll(t).count()
        }

        /**
         * Kokoro drags very short inputs: alone, "Yes." or "Well," come out ~1.5-3x longer than the same word inside a
         * sentence (measured on US/UK, male/female voices), while 5+ syllables are steady. Speeding those up brings them
         * back to their in-sentence length.
         */
        fun shortInputBoost(text: String): Float =
            1f + 0.7f * kotlin.math.exp(-(maxOf(syllables(text), 1) - 1) / 1.6f)

        /** Abbreviations whose period doesn't end a sentence (lowercase, no dot). */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "mx", "dr", "st", "jr", "sr", "sra", "srta", "dra", "prof", "rev", "fr", "hon",
            "mt", "ft", "vs", "etc", "e.g", "i.e", "cf", "approx", "vol", "vols", "ch", "pp", "fig",
            "sgt", "capt", "col", "gen", "lt", "maj", "cmdr", "adm", "gov", "sen", "pres", "insp",
            "jan", "feb", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec", "inc", "ltd", "corp",
            // other languages, where they can't be an ordinary word ending a sentence
            "profa", "ud", "uds", "sig", "dott", "hr", "usw", "z.b", "bzw", "ecc", "p.ex", "sig.ra",
        )

        /**
         * Abbreviations that only count in their language: "out." is October in Portuguese and "went out." in
         * English, "mar." is March in Italian and the sea in Spanish.
         */
        private val ABBREVIATIONS_BY_LANG = mapOf(
            "pt" to setOf("exmo", "exma", "sto", "sta", "av", "pág", "págs", "séc", "sécs", "cap", "fev", "abr", "mai", "ago", "set", "out", "dez"),
            "es" to setOf("d", "dña", "núm", "excmo", "excma", "ilmo", "ilma", "avda", "tel", "sto", "sta", "cap", "pág", "págs", "ene", "ago", "dic"),
            "fr" to setOf("mme", "mlle", "ste", "bd", "éd", "op", "chap", "janv", "févr", "avr", "juil", "déc"),
            "it" to setOf("sigg", "ing", "avv", "pag", "pagg", "sec", "cap", "gen", "mar", "giu", "lug", "ott", "dic", "sig.na", "dott.ssa"),
            "de" to setOf("d.h", "u.a", "evtl", "ggf", "vgl", "jh", "nr", "str", "ca", "abs", "tel", "kap", "okt", "dez", "mrz"),
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
        /** An abbreviation that only counts before a number ("p. 12", "n.º 5", "S. 40"). */
        private fun beforeNumber(abbr: String, word: String) = Regex("(?<![\\p{L}.])${Regex.escape(abbr)}(?=\\s*\\d)") to word
        /** An abbreviation that only counts before a capitalized word ("S. Pietro", "D. Maria"). */
        private fun beforeName(abbr: String, word: String, nameEnds: String = "") =
            Regex("(?<![\\p{L}.])${Regex.escape(abbr)}(?=\\s+\\p{Lu}\\p{L}*$nameEnds\\b)") to word

        private val SPOKEN_BY_LANG: Map<String, List<Pair<Regex, String>>> = mapOf(
            "en" to SPOKEN,
            "pt" to spoken("Srta." to "Senhorita", "Sra." to "Senhora", "Sr." to "Senhor", "Dra." to "Doutora", "Dr." to "Doutor",
                "Profa." to "Professora", "Prof." to "Professor", "Exmo." to "Excelentíssimo", "Exma." to "Excelentíssima",
                "Sto." to "Santo", "Sta." to "Santa", "Av." to "Avenida", "séc." to "século", "etc." to "etcétera", "p. ex." to "por exemplo") +
                listOf(beforeNumber("pág.", "página"), beforeNumber("págs.", "páginas"), beforeNumber("n.º", "número"), beforeNumber("nº", "número"),
                    beforeNumber("cap.", "capítulo"), beforeName("D.", "Dona", nameEnds = "a"), beforeName("D.", "Dom")),
            "es" to spoken("Srta." to "Señorita", "Sra." to "Señora", "Sr." to "Señor", "Dra." to "Doctora", "Dr." to "Doctor",
                "Dña." to "Doña", "Uds." to "ustedes", "Ud." to "usted", "Prof." to "Profesor", "Excmo." to "Excelentísimo",
                "Sto." to "Santo", "Sta." to "Santa", "Avda." to "Avenida", "etc." to "etcétera") +
                listOf(beforeNumber("pág.", "página"), beforeNumber("págs.", "páginas"), beforeNumber("núm.", "número"), beforeNumber("n.º", "número"),
                    beforeNumber("nº", "número"), beforeNumber("cap.", "capítulo"), beforeName("D.", "Don")),
            "fr" to spoken("Mme" to "Madame", "Mlle" to "Mademoiselle", "M." to "Monsieur", "Dr" to "Docteur", "Bd" to "Boulevard",
                "éd." to "édition", "etc." to "et cetera") +
                listOf(beforeNumber("p.", "page"), beforeNumber("pp.", "pages"), beforeNumber("n°", "numéro"), beforeNumber("chap.", "chapitre"),
                    beforeName("St", "Saint"), beforeName("Ste", "Sainte")),
            "it" to spoken("Sig.ra" to "Signora", "Sig.na" to "Signorina", "Sig." to "Signor", "Dott.ssa" to "Dottoressa", "Dott." to "Dottor",
                "Ing." to "Ingegner", "Avv." to "Avvocato", "Prof." to "Professor", "sec." to "secolo", "ecc." to "eccetera") +
                listOf(beforeNumber("pag.", "pagina"), beforeNumber("pagg.", "pagine"), beforeNumber("n.", "numero"), beforeNumber("cap.", "capitolo"),
                    beforeName("S.", "San")),
            "de" to spoken("Hr." to "Herr", "Fr." to "Frau", "Dr." to "Doktor", "Prof." to "Professor", "Str." to "Straße", "ca." to "circa",
                "z.B." to "zum Beispiel", "d.h." to "das heißt", "u.a." to "unter anderem", "vgl." to "vergleiche", "Jh." to "Jahrhundert",
                "usw." to "und so weiter", "bzw." to "beziehungsweise") +
                listOf(beforeNumber("Nr.", "Nummer"), beforeNumber("S.", "Seite"), beforeNumber("Kap.", "Kapitel"), beforeName("St.", "Sankt")),
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
    /**
     * Exact pauses: cut at sentence ends, commas, dashes and "..." and insert our own silence there. Off = [phrasing]
     * decides what goes to the model, with Kokoro's own (much shorter, uneven) timing inside.
     */
    @Volatile var commaPauses = true
    /** Silence at a comma (; and : get 1.5x). */
    @Volatile var commaPauseMs = 180
    /** Gaps inside a phrase longer than this are shortened to it. 0 = off. */
    @Volatile var maxGapMs = 90
    /** With [commaPauses] off, how much text goes to the model at once: 1 = a sentence, 2 = up to 3 sentences, 3 = up to a paragraph. */
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
        val wrapped = RenderOut(out.writerFor, out.onProgress, out.onPart,
            onDone = { renderSession = null; out.onDone() },
            onPaused = { renderSession = null; out.onPaused() },
            onError = { msg -> renderSession = null; out.onError(msg) })
        renderSession = Session(text, from, false, until = until, spec = spec, out = wrapped).also { it.begin() }
    }

    fun cancelRender() {
        renderSession?.cancel()
        renderSession = null
    }

    /** Stop making the file at the next sentence end, keeping everything made so far (RenderOut.onPaused follows). */
    fun pauseRender() {
        renderSession?.pauseRequested = true
    }

    // ---------- playing a pre-made audio file, highlight driven by its sync map ----------

    private var filePlayer: FilePlayer? = null

    /** The audio file playing and where in it (ms), so a pause can resume at that exact spot. */
    val filePosition: Pair<String, Long>? get() = filePlayer?.position

    /**
     * Plays [r] from the sentence at [from] (or from [atMs] in the file, when that's inside that sentence: resuming
     * after a pause). A file still being made plays the parts finished so far, picking up new ones as it goes; where
     * the audio ends, reading continues live.
     */
    fun playRender(r: Render, text: String, from: Int, atMs: Long? = null) {
        stop()
        stopPrefetch()
        val sync = Renders.readSync(ctx, r.id)
        val parts = Renders.parts(ctx, r)
        if (sync.isEmpty() || parts.isEmpty()) return start(text, from)
        filePlayer = try { FilePlayer(r, text, sync, Renders.readSync(ctx, r.id, keepClauses = true), parts, from, atMs) } catch (t: Throwable) {
            AppLog.e("Narrator", "can't play audio file ${r.id}", t)
            return start(text, from)
        }
    }

    /**
     * [sync] has a point per sentence (clauses folded in, so the whole sentence stays lit); [raw] keeps the clause
     * points too, since word timing is estimated between consecutive points and shorter stretches land closer.
     */
    private inner class FilePlayer(private var r: Render, private val text: String, private var sync: List<SyncPoint>,
                                   private var raw: List<SyncPoint>, private var parts: List<RenderPart>, from: Int, atMs: Long?) {
        private var mp: android.media.MediaPlayer? = null
        private var part = 0
        @Volatile private var released = false
        private var shown = -1
        // the settings the file was made with ("speed|pause|commaPauses|commaPause|...") shape the word estimate
        private val fileSettings = r.settings.split('|')
        private val fSpeed = fileSettings.getOrNull(0)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
        private val fPause = fileSettings.getOrNull(1)?.toIntOrNull() ?: 400
        private val fCommaPause = fileSettings.getOrNull(3)?.toIntOrNull() ?: 180
        private var wordsFor = -1                 // raw index whose word table is [words]
        private var words: WordTiming.Words? = null
        private val tick = object : Runnable {
            override fun run() {
                if (released) return
                val pos = positionMs()
                val idx = indexAt(sync, pos)
                if (idx != shown) { shown = idx; listener.onSentence(sync[idx].start, sync[idx].end) }
                if (wordHighlight && raw.isNotEmpty()) {
                    val k = indexAt(raw, pos)
                    if (k != wordsFor) { wordsFor = k; words = wordsAt(k) }
                    val w = words
                    val i = w?.at(pos - raw[k].ms) ?: -1
                    if (i < 0) emitWord(-1, -1) else emitWord(w!!.starts[i], w.ends[i])
                }
                main.postDelayed(this, if (wordHighlight) 66 else 120)
            }
        }

        /** Last sync point at or before [pos]. */
        private fun indexAt(points: List<SyncPoint>, pos: Long): Int {
            var lo = 0; var hi = points.size - 1; var idx = 0
            while (lo <= hi) { val mid = (lo + hi) / 2; if (points[mid].ms <= pos) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
            return idx
        }

        /** Words of the stretch from raw point [k] to the next one (or the end of the audio made so far). */
        private fun wordsAt(k: Int): WordTiming.Words? {
            val p = raw.getOrNull(k) ?: return null
            val next = raw.getOrNull(k + 1)
            val endMs = next?.ms ?: parts.lastOrNull()?.let { it.startMs + it.ms } ?: return null
            // a clause of the same sentence follows: this stretch ends where it starts, after a comma pause
            val clause = next != null && next.end == p.end && next.start > p.start
            val until = if (clause) next!!.start else p.end
            val trailing = if (clause) fCommaPause else fPause
            return WordTiming.estimate(text, p.start, until, (endMs - p.ms).toInt().coerceAtLeast(0), trailing,
                commaPauseMs = (100 / fSpeed).toInt(), sentencePauseMs = (200 / fSpeed).toInt())
        }

        private fun positionMs() = parts[part].startMs + (runCatching { mp?.currentPosition?.toLong() }.getOrNull() ?: 0L)
        val position get() = r.id to positionMs()

        /** The speed the file was made at; the player stretches it to the speed chosen now. */
        private val madeAt = VoicePreset.parse(r.settings)?.speed ?: 1f
        private var rate = 1f

        /**
         * Play at the current [speed] whatever speed the file was made at: time-stretched (pitch kept) by the
         * player, so a 1× file can be heard at 1.5× without making it again. Positions stay in file time, so
         * the sentence sync map still lines up.
         */
        fun applySpeed() {
            val want = (speed / madeAt).coerceIn(0.5f, 2.5f)
            if (kotlin.math.abs(want - rate) < 0.005f) return
            rate = want
            val m = mp ?: return
            runCatching { m.playbackParams = m.playbackParams.setSpeed(rate) }
                .onFailure { AppLog.e("Narrator", "can't set audio file speed ${"%.2f".format(rate)}", it) }
        }

        init {
            val i = sync.indexOfLast { it.start <= from }.coerceAtLeast(0)
            var startAt = sync[i].ms
            // resuming after a pause: back where it stopped (less a moment, to pick up the thread), not the sentence start
            val next = sync.getOrNull(i + 1)?.ms ?: Long.MAX_VALUE
            if (atMs != null && atMs > startAt && atMs < next) startAt = maxOf(startAt, atMs - 1500)
            open(parts.indexOfLast { it.startMs <= startAt }.coerceAtLeast(0), startAt)
            applySpeed()
            listener.onBuffering(false)
            main.post(tick)
            AppLog.i("Narrator", "playing audio file ${r.id} from ${startAt}ms (${parts.size} parts, ${r.status}, " +
                "made at ${madeAt}×, playing at ${"%.2f".format(rate)}× of that)")
        }

        /** Start part [i] at [atMs] of the whole file. */
        private fun open(i: Int, atMs: Long) {
            mp?.let { runCatching { it.stop() }; it.release() }
            mp = null
            part = i
            val m = android.media.MediaPlayer()
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            m.setDataSource(parts[i].file.path)
            m.prepare()
            val local = atMs - parts[i].startMs
            if (local > 0) m.seekTo(local, android.media.MediaPlayer.SEEK_CLOSEST)
            m.setOnCompletionListener { if (!released) partEnded() }
            m.start()
            mp = m
            // the player's speed is per MediaPlayer: set it again on every part (and not before start(), which
            // setPlaybackParams would trigger on its own)
            if (rate != 1f) runCatching { m.playbackParams = m.playbackParams.setSpeed(rate) }
        }

        private fun partEnded() {
            // a file still being made may have finished more parts since we started
            if (part + 1 >= parts.size && !r.ready) Renders.load(ctx, r.id)?.let { fresh ->
                r = fresh
                parts = Renders.parts(ctx, fresh)
                sync = Renders.readSync(ctx, fresh.id).ifEmpty { sync }
                raw = Renders.readSync(ctx, fresh.id, keepClauses = true).ifEmpty { raw }
                wordsFor = -1   // the last stretch's end is known now
            }
            if (part + 1 < parts.size) {
                val ok = runCatching { open(part + 1, parts[part + 1].startMs) }
                    .onFailure { AppLog.e("Narrator", "audio file ${r.id} part ${part + 1} won't play", it) }.isSuccess
                if (ok) return
            }
            val at = r.coveredTo
            AppLog.i("Narrator", "audio file ${r.id} ends here, continuing live at $at")
            release()
            filePlayer = null
            if (at < text.length) start(text, at) else listener.onFinished()
        }

        fun release() {
            if (released) return
            released = true
            main.removeCallbacks(tick)
            emitWord(-1, -1)
            mp?.let { runCatching { it.stop() }; it.release() }
            mp = null
        }
    }

    /**
     * A setting that changes how speech is generated (voice, speed, commas, trimming) was changed while the
     * book is playing: re-render from the sentence being heard. Pause lengths apply live and don't need this.
     */
    fun applyToPlaying() {
        // a pre-made audio file keeps its voice and pauses, but follows the speed (stretched by the player)
        filePlayer?.let { it.applySpeed(); return }
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

    // ---------- loudness: every voice (and blend) brought to the same level, see Loudness ----------

    /** Running level per voice key ("kokoro:27#108"), loaded from prefs on first use. */
    private val levels = HashMap<String, Loudness.VoiceLevel>()

    private fun levelOf(key: String): Loudness.VoiceLevel = synchronized(levels) {
        levels.getOrPut(key) {
            val p = BookStore.prefs(ctx)
            Loudness.VoiceLevel(p.getFloat("loud_db_$key", Float.NaN), p.getFloat("loud_sec_$key", 0f))
        }
    }

    /**
     * [x] brought to the common loudness: measured, folded into the voice's running level (kept in prefs so it
     * survives restarts), then scaled by the gain that level calls for. The gain follows the voice, not the sentence.
     */
    private fun levelled(x: FloatArray, rate: Int, key: String): Pair<FloatArray, String> {
        val lv = levelOf(key)
        val m = Loudness.measure(x, rate)
        val g = synchronized(lv) {
            if (lv.update(m)) BookStore.prefs(ctx).edit().putFloat("loud_db_$key", lv.db).putFloat("loud_sec_$key", lv.sec).apply()
            lv.gain
        }
        val note = "chunk ${if (m.db.isNaN()) "unvoiced" else "%.1f dB".format(java.util.Locale.US, m.db)}, " +
            "voice %.1f dB over %.0fs, gain x%.2f".format(java.util.Locale.US, lv.db, lv.sec, g)
        return Loudness.apply(x, g) to note
    }

    // ---------- audio cache: recently generated sentences, so jumping back is instant ----------

    private class Cached(val samples: FloatArray, val rate: Int)
    private val cache = LinkedHashMap<String, Cached>(64, 0.75f, true)   // access order = LRU
    private var cacheSamples = 0L
    private val cacheBudget: Long get() = (baseAheadMs * 3 / 2 + 180_000L) / 1000 * 24_000   // floats in memory

    private fun cacheKey(text: String, v: String, sp: Float, gap: Int = maxGapMs) = "$AUDIO_VERSION|$v|$sp|$gap|$text"

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
                        val clause: Boolean = false, val rate: Int = 24000, val subs: List<Triple<Int, Int, Float>> = emptyList(),
                        /** Where each word of this piece falls in its audio (live sessions only). */
                        val words: WordTiming.Words? = null)

    /** Settings frozen for a render (live sessions follow the sliders instead). */
    class RenderSpec(val voiceId: String, val speed: Float, val pauseMs: Int, val commaPauses: Boolean,
                     val commaPauseMs: Int, val maxGapMs: Int, val phrasing: Int)

    /**
     * Where a render's audio goes, and who hears about progress. Audio is written in parts ([writerFor] gets the
     * sample rate and the part's index, counted from this run); [onPart] reports each finished part: its length,
     * the text it covers up to, and its sentences (ms from the part's start). [onProgress]: text done up to, audio ms.
     */
    class RenderOut(val writerFor: (Int, Int) -> AacWriter, val onProgress: (Int, Long) -> Unit,
                    val onPart: (Long, Int, List<SyncPoint>) -> Unit, val onDone: () -> Unit,
                    val onPaused: () -> Unit, val onError: (String) -> Unit)

    /** A queued piece: its audio (when ready) and an estimate of how long it'll play until then. */
    private class Item(val f: Future<Chunk?>, val estMs: Long)

    /**
     * What the listener hears right now: the play thread notes which piece it is writing and where the track's head
     * is after each write (no allocation); [wordTick] on the main thread reads it. The fields are written one by
     * one, so a reader can see a half-updated state for one tick: [begin] orders them so that only picks the previous
     * piece, never a wrong offset.
     */
    private class Playhead {
        @Volatile var rate = 24000
        @Volatile var chunk: Chunk? = null
        @Volatile var chunkBase = 0L       // frames written to the track before [chunk]
        @Volatile var prev: Chunk? = null
        @Volatile var prevBase = 0L
        @Volatile var headFrames = 0L      // the track's play head when last looked at
        @Volatile var headAt = 0L          // nanoTime of that look

        fun reset(r: Int) {
            rate = r; chunk = null; prev = null; chunkBase = 0L; prevBase = 0L; headFrames = 0L; headAt = System.nanoTime()
        }

        fun begin(c: Chunk, base: Long) {
            prev = chunk; prevBase = chunkBase; chunkBase = base; chunk = c
        }

        fun mark(track: AudioTrack) {
            headFrames = track.playbackHeadPosition.toLong(); headAt = System.nanoTime()
        }

        /** Frames heard now: the last mark, moved on by the time since (capped: during a stall the head stands still). */
        fun frames(): Long {
            val dt = (System.nanoTime() - headAt).coerceIn(0L, 300_000_000L)
            return headFrames + dt * rate / 1_000_000_000L
        }
    }

    // ---------- word highlight: which word is being heard, ~15x a second ----------

    /** Report the word being heard ([Listener.onWord]): on while the reader is on screen and the setting is on. */
    @Volatile var wordHighlight = false
        set(v) { field = v; main.post { if (v) wordTick.run() else emitWord(-1, -1) } }
    private var wordShownStart = -1   // main thread only
    private var wordShownEnd = -1

    private fun emitWord(a: Int, b: Int) {
        if (a == wordShownStart && b == wordShownEnd) return
        wordShownStart = a; wordShownEnd = b
        listener.onWord(a, b)
    }

    private val wordTick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (filePlayer != null) return   // it has its own tick
            val s = session
            if (!wordHighlight || s == null || s.preview) { emitWord(-1, -1); return }
            s.tickWord()
            main.postDelayed(this, 66)
        }
    }

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
                !prefetchOnly -> {
                    playThread = thread(name = "tts-play") { play() }
                    if (!preview) main.post(wordTick)
                }
            }
        }

        /** Set by [pauseRender]: finish the current part at the next sentence end and stop. */
        @Volatile var pauseRequested = false

        /**
         * Render mode: write every piece (and its pauses) into the audio file, noting where each sentence starts.
         * The audio goes into parts of [RENDER_PART_SEC], each closed at a sentence end, so what's made can be
         * played (or kept, if making stops) before the whole file is done.
         */
        private fun renderToFile() {
            val o = out!!
            var writer: AacWriter? = null
            var partSync = ArrayList<SyncPoint>()
            var part = 0
            var rate = 24000
            var closedSamples = 0L
            var midSentence = false   // the last piece was a clause: the rest of its sentence is still to come
            var doneTo = from
            fun closePart(upTo: Int) {
                val w = writer ?: return
                writer = null
                w.finish()
                closedSamples += w.samplesWritten
                val ms = w.samplesWritten * 1000 / rate
                val points = partSync
                partSync = ArrayList()
                main.post { o.onPart(ms, upTo, points) }
            }
            try {
                while (!cancelled) {
                    if (pauseRequested && !midSentence) {
                        closePart(doneTo)
                        cancel()
                        main.post { o.onPaused() }
                        return
                    }
                    // poll, so a pause is noticed while generation is waiting (e.g. stepping aside for live reading)
                    val f = queue.poll(250, TimeUnit.MILLISECONDS)?.f ?: continue
                    val c = try { f.get() } catch (_: java.util.concurrent.CancellationException) { null } ?: continue
                    if (c === endMarker) break
                    midSentence = c.clause
                    if (!c.clause) doneTo = c.end
                    if (c.samples.isEmpty() && writer == null && part == 0) continue   // silence before any audio: nothing to extend
                    if (writer == null) { if (part == 0) rate = c.rate; writer = o.writerFor(rate, part++) }
                    val w = writer!!
                    val base = w.samplesWritten
                    val subs = if (c.subs.isEmpty()) listOf(Triple(c.start, c.end, 0f)) else c.subs
                    for ((a, b, frac) in subs) partSync += SyncPoint(a, b, (base + (frac * c.samples.size).toLong()) * 1000 / rate)
                    w.write(c.samples)
                    w.silence((rate * (if (c.clause) cCommaPause else cPause) / 1000f * c.pauseFactor).toInt())
                    val audioMs = (closedSamples + w.samplesWritten) * 1000 / rate
                    val upTo = c.end
                    main.post { o.onProgress(upTo, audioMs) }
                    if (!c.clause && w.samplesWritten >= rate.toLong() * RENDER_PART_SEC) closePart(c.end)
                }
                if (cancelled) { writer?.abort(); return }
                closePart(until)
                main.post { o.onDone() }
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
                    // with exact pauses on, every sentence end is a cut like a comma: inside one model call Kokoro leaves
                    // anywhere from 0 to ~300 ms between sentences, which is what made the pacing feel uneven
                    if (cPhrasing > 1 && !cCommaPauses) {
                        val (maxSentences, maxChars) = if (cPhrasing == 2) 3 to 300 else 8 to 480
                        while (sentences.size < maxSentences && !isParagraphEnd(e)) {
                            val next = nextSentence(e, MAX_SENTENCE) ?: break
                            if (next.second - s > maxChars) break
                            sentences += next
                            e = next.second
                        }
                    }
                    pos = e
                    if ((s until e).none { text[it].isLetterOrDigit() }) {
                        // a scene break ("* * *") on its own line: a longer silence instead of nothing
                        if (isParagraphEnd(e)) queue.put(Item(FutureTask<Chunk?> { Chunk(s, e, FloatArray(0), 2f) }.also { it.run() }, cPause * 2L))
                        continue
                    }
                    // exact pauses: split at commas, dashes and mid-sentence "..." (exact pause, no word cuts, each clause
                    // keeps its mark so the intonation still "continues"). Left in one piece, Kokoro mostly runs straight
                    // through them (0-130 ms).
                    val parts = clauses(s, e)
                    for ((k, part) in parts.withIndex()) {
                        val shown = text.substring(part.first, part.second).trim()
                        // sentences inside this piece, for sentence-by-sentence highlighting
                        val pSpan = (part.second - part.first).coerceAtLeast(1).toFloat()
                        val subs = sentences.filter { (a, b) -> b > part.first && a < part.second }.map { (a, b) ->
                            Triple(maxOf(a, part.first), b, (maxOf(a, part.first) - part.first) / pSpan)
                        }
                        val isClause = k < parts.size - 1
                        // a heading ("CHAPTER IV") has no full stop: give it one so the voice lands instead of trailing off
                        val sentence = speakable(shown, voiceInfo.lang).let {
                            if (!isClause && isParagraphEnd(e) && it.trimEnd { c -> c in CLOSERS }.lastOrNull()?.isLetterOrDigit() == true) "$it." else it
                        }
                        val factor = if (isClause) clausePause(shown) else sentencePause(shown, e)
                        val v = "${voiceInfo.id}#$sid"; val sp = cSpeed
                        // only measured on Kokoro; Piper voices are left at the chosen speed
                        val genSpeed = if (voiceInfo.builtIn) sp * shortInputBoost(sentence) else sp
                        val key = cacheKey(sentence, v, sp, cGap)
                        // the silence written after this piece when it plays: the last word keeps its mark through it
                        val trailingMs = ((if (isClause) cCommaPause else cPause) * factor).toInt()
                        // word timing is estimated here, off the audio thread, once the piece's length is known
                        val wordsWanted = !rendering && !prefetchOnly && !preview
                        fun chunk(samples: FloatArray, rate: Int) = Chunk(s, e, samples, factor, isClause, rate, subs,
                            words = if (!wordsWanted) null else WordTiming.estimate(text, part.first, part.second,
                                (samples.size * 1000L / rate).toInt() + trailingMs, trailingMs,
                                commaPauseMs = (100 / sp).toInt(), sentencePauseMs = (200 / sp).toInt()))
                        // recently generated (heard or buffered ahead)? use it right away, no engine wait
                        // stay a set amount of listening time ahead (bigger on phones that generate slowly)
                        while (!cancelled && aheadMs() > aheadTargetMs) Thread.sleep(300)
                        if (cancelled) return
                        val cached = cacheGet(key)
                        val job: Future<Chunk?> = if (cached != null) {
                            FutureTask<Chunk?> { chunk(cached.samples, cached.rate) }.also { it.run() }
                        } else pool.submit<Chunk?> {
                            if (cancelled) return@submit null
                            cacheGet(key)?.let { return@submit chunk(it.samples, it.rate) }
                            run {
                                val eng = engineFor(voiceInfo)
                                if (cancelled) return@submit null
                                val t0 = System.nanoTime()
                                val audio = eng.generate(sentence, sid, genSpeed)
                                val (samples, levelNote) = levelled(
                                    squeezePauses(trimSilence(audio.samples, audio.sampleRate), audio.sampleRate, sentence), audio.sampleRate, v)
                                AppLog.i("Narrator", "gen ${(System.nanoTime() - t0) / 1_000_000}ms for " +
                                    "${audio.samples.size * 1000L / audio.sampleRate}ms audio, ${sentence.length} chars; $levelNote")
                                val genMs = (System.nanoTime() - t0) / 1_000_000.0
                                val audioMs = audio.samples.size * 1000.0 / audio.sampleRate
                                if (audioMs > 300) rtf = rtf * 0.8 + (genMs / audioMs) * 0.2
                                val c = Cached(samples, audio.sampleRate)
                                if (!rendering) {   // renders don't push live audio out of the caches
                                    cachePut(key, c)   // keep it even if this session was cancelled
                                    diskPut(key, c)
                                }
                                chunk(samples, audio.sampleRate)
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
                val spaced = if (c == '.') spacedEllipsisEnd(i) else -1   // ". . ."
                if (c == '.' && spaced < 0 && isAbbreviation(i)) { i++; continue }
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    var j = if (spaced > 0) spaced else i + 1
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

        /** Index right after a spaced ellipsis ". . ." starting at [i], or -1. */
        private fun spacedEllipsisEnd(i: Int): Int {
            var k = i
            var dots = 1
            while (k + 2 < text.length && text[k + 1] == ' ' && text[k + 2] == '.') { k += 2; dots++ }
            return if (dots >= 3) k + 1 else -1
        }

        private fun isEllipsisEnd(t: String) = t.endsWith("...") || t.endsWith("…") || t.endsWith(". .")

        /** Relative to commaPauseMs. */
        private fun clausePause(clause: String): Float {
            val t = clause.trimEnd().trimEnd { it in CLOSERS }
            if (isEllipsisEnd(t)) return 2f   // "I... I don't know"
            return when (t.lastOrNull()) {
                ',' -> 1f
                ';', ':' -> 1.5f
                '—', '–', '-' -> 1.2f
                else -> 0.7f   // before a "(" aside
            }
        }

        private fun sentencePause(clause: String, e: Int): Float {
            val t = clause.trimEnd().trimEnd { it in CLOSERS }
            val ellipsis = isEllipsisEnd(t)
            val f = when {
                ellipsis -> 1.6f
                t.endsWith("?") || t.endsWith("!") -> 1.25f
                t.endsWith(".") -> 1f
                else -> 0.45f   // sentence cut at MAX_SENTENCE
            }
            val words = t.split(' ').count { w -> w.any { it.isLetterOrDigit() } }
            if (isParagraphEnd(e)) {
                // a heading ("CHAPTER IV", "The Long Night") gets a little more room than a paragraph
                val heading = t.lastOrNull()?.isLetterOrDigit() == true && words <= 8
                return if (heading) 2.5f else maxOf(f, 2f)
            }
            // "He stopped. He turned." / "Yes? No?" — short sentences keep moving, a full pause after each drags
            return if (words <= 2 && !ellipsis) minOf(f, 0.75f) else f
        }

        private fun clauses(s: Int, e: Int): List<Pair<Int, Int>> {
            if (!cCommaPauses) return listOf(s to e)
            fun words(a: Int, b: Int) = text.substring(a, b).split(' ', '\n').count { w -> w.any { it.isLetterOrDigit() } }
            fun lastWord(a: Int, b: Int) = Regex("(\\w+)\\W*$").find(text.substring(a, b))?.groupValues?.get(1)?.lowercase()
            fun firstWord(a: Int, b: Int) = Regex("^\\W*(\\w+)").find(text.substring(a, b))?.groupValues?.get(1)?.lowercase()
            val out = ArrayList<Pair<Int, Int>>()
            var start = s
            var i = s
            while (i < e) {
                val c = text[i]
                var cut = -1
                var next = i + 1
                when {
                    c == ',' || c == ';' || c == ':' -> {
                        var j = i + 1
                        while (j < e && text[j] in CLOSERS) j++
                        // "1,000" and "6:45" have no space after the mark and stay together
                        if (j < e && text[j].isWhitespace()) cut = j
                        next = j
                    }
                    // em dash anywhere; en dash or hyphen only with spaces around ("1914–1918", "north-south" stay); "--"
                    c == '—' || (c in "–-" && text.getOrNull(i - 1) == ' ' && text.getOrNull(i + 1) == ' ') ||
                        (c == '-' && text.getOrNull(i + 1) == '-') -> {
                        var j = i + 1
                        while (j < e && text[j] in "—–-") j++
                        // "I—I can't" is a stammer, not a break
                        if (lastWord(start, i) == null || lastWord(start, i) != firstWord(j, e)) cut = j
                        next = j
                    }
                    // "..." / "…" / ". . ." inside a sentence ("It was late... far too late")
                    c == '…' || (c == '.' && (text.startsWith("...", i) || spacedEllipsisEnd(i) > 0)) -> {
                        var j = if (c == '.' && spacedEllipsisEnd(i) > 0) spacedEllipsisEnd(i) else i + 1
                        while (j < e && text[j] in ".…") j++
                        while (j < e && text[j] in CLOSERS) j++
                        if (j < e && text[j].isWhitespace()) cut = j
                        next = j
                    }
                    // before an aside in parentheses
                    c == '(' && i > start && text[i - 1] == ' ' -> cut = i
                }
                if (cut > 0 && words(start, cut) >= MIN_CLAUSE_WORDS_BEFORE && words(cut, e) >= MIN_CLAUSE_WORDS_AFTER) {
                    out += start to cut
                    start = cut
                }
                i = next
            }
            out += start to e
            // a short dialogue tag stays with its quote: "Straight quotes," he said, | "are..."
            var k = 1
            while (k < out.size) {
                val (a, b) = out[k]
                val prev = text.substring(out[k - 1].first, out[k - 1].second).trimEnd()
                if (words(a, b) <= 3 && text.substring(a, b).trim().firstOrNull()?.isLowerCase() == true && prev.lastOrNull()?.let { it in CLOSERS } == true) {
                    out[k - 1] = out[k - 1].first to b
                    out.removeAt(k)
                } else k++
            }
            return out
        }

        /** Text as it should be spoken: expand abbreviations, numerals and years the voice would otherwise trip over. */
        private fun speakable(t: String, lang: String): String {
            var out = t.replace(SPACED_ELLIPSIS, "…")
                .replace(FOOTNOTE_MARK, "")      // "it[1]", "footnote*", "word²" were read out as "one" / "asterisk"
                .replace(DOUBLE_HYPHEN, " — ")
            for ((re, say) in pronunciations) out = re.replace(out, Regex.escapeReplacement(say))
            for ((re, word) in SPOKEN_BY_LANG[lang].orEmpty()) out = re.replace(out, word)
            return SpokenText.normalise(out, lang)
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
            if (word.isEmpty()) return isGermanOrdinal(dot)
            if (word == "no" || word == "nos" || word == "n") return nextIsDigit(dot + 1) || text.getOrNull(dot + 1) == 'º'   // "No. 5", "n.º 5", not "He said no."
            // "p. 12", "s. 40", "v. 3": a single letter before a number is page / Seite / verse, not a sentence end
            if (word.length == 1 && text[k].isLowerCase()) return nextIsDigit(dot + 1)
            return word in ABBREVIATIONS || ABBREVIATIONS_BY_LANG[voiceInfo.lang]?.contains(word) == true ||
                (word.length == 1 && text[k].isUpperCase() && word != "i")
        }

        /** German writes ordinals as "3.": "am 3. Mai", "Ludwig XIV." — a short number, then a capitalized word. */
        private fun isGermanOrdinal(dot: Int): Boolean {
            if (voiceInfo.lang != "de") return false
            var k = dot
            while (k > 0 && text[k - 1].isDigit()) k--
            val digits = dot - k
            if (digits !in 1..2 || (k > 0 && text[k - 1].isLetterOrDigit())) return false
            var j = dot + 1
            while (j < text.length && text[j] == ' ') j++
            return j < text.length && text[j].isUpperCase()
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

        val playhead = Playhead()

        /** Main thread, ~15x a second while playing: which word is being heard, from the track's play head. */
        fun tickWord() {
            val ph = playhead
            val head = ph.frames()
            // the track plays a little behind what was last written: the head may still be in the previous piece
            val c: Chunk?; val base: Long
            if (head >= ph.chunkBase) { c = ph.chunk; base = ph.chunkBase } else { c = ph.prev; base = ph.prevBase }
            val w = c?.words ?: return emitWord(-1, -1)
            val i = w.at((head - base) * 1000 / ph.rate)
            if (i < 0) emitWord(-1, -1) else emitWord(w.starts[i], w.ends[i])
        }

        private fun play() {
            var track: AudioTrack? = null
            var rate = 24000
            val silenceBuf = FloatArray(4096)
            var shown = -1 to -1   // sentence highlighted now
            var written = 0L       // frames written to the track so far
            val ph = playhead
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
                    if (c.samples.isEmpty() && track == null) continue   // a scene break before anything was played
                    if (track != null && rate != c.rate && c.samples.isNotEmpty()) { track.run { stop(); release() }; track = null }
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
                        written = 0L
                        ph.reset(rate)
                    }
                    // highlight sentence by sentence inside a multi-sentence chunk, timed by its share of the text
                    val subs = if (c.subs.size <= 1) listOf(Triple(c.subs.firstOrNull()?.first ?: c.start, c.end, 0f)) else c.subs
                    var nextSub = 0
                    fun announce(i: Int) {
                        val (a, b, _) = subs[i]
                        // a clause after a comma continues the sentence already highlighted (same end, later start):
                        // keep the whole sentence lit instead of shrinking it clause by clause
                        if (b == shown.second && a > shown.first) return
                        shown = a to b
                        playingStart = a
                        if (!preview) main.post { if (!cancelled) listener.onSentence(a, b) }
                    }
                    // write in slices so stop() takes effect quickly
                    ph.begin(c, written)
                    var off = 0
                    while (off < c.samples.size && !cancelled) {
                        while (nextSub < subs.size && off >= subs[nextSub].third * c.samples.size) announce(nextSub++)
                        val n = minOf(2048, c.samples.size - off)
                        track.write(c.samples, off, n, AudioTrack.WRITE_BLOCKING)
                        off += n
                        written += n
                        ph.mark(track)
                    }
                    // pause before the next sentence (written as silence so it stays in sync with the highlight)
                    var silence = (rate * (if (c.clause) cCommaPause else cPause) / 1000f * c.pauseFactor).toInt()
                    while (silence > 0 && !cancelled) {
                        val n = minOf(silenceBuf.size, silence)
                        track.write(silenceBuf, 0, n, AudioTrack.WRITE_BLOCKING)
                        silence -= n
                        written += n
                        ph.mark(track)
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
