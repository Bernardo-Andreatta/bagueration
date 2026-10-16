package com.bernardo.ereader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pre-loaded audio for a book: chapters (or a length) made ahead of time with the current voice and settings, so
 * they play with no loading. Shows what plays on the page you came from, the progress of what's being pre-loaded,
 * and one obvious next step per file; secondary actions sit in each file's "More" sheet. A file can be played
 * while it's still being pre-loaded (the parts finished so far), and "Pause" stops preparing it, keeping what's done.
 */
class RendersActivity : Activity() {

    companion object {
        const val EXTRA_BOOK = "book"
        /** The chapter on screen in the reader (index), preselected when preparing. */
        const val EXTRA_CHAPTER = "chapter"
        /** Where the reader is (book offset): the screen says what plays there. */
        const val EXTRA_POS = "pos"
        /** Open straight into "Pre-load chapters" (the first-time path from the reader). */
        const val EXTRA_NEW = "new"
        /** "Play" was pressed on a file: the reader starts (book id, position) when it comes back. */
        var playRequest: Pair<String, Int>? = null
    }

    private lateinit var th: ReaderTheme
    private lateinit var ui: Ui
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK)!! }
    private val book by lazy { BookParser.load(BookStore.dir(this), bookId) }
    private val textLength by lazy { book?.text?.length ?: 0 }
    private val chapters by lazy { book?.chapters?.ifEmpty { null } ?: listOf(Chapter("Whole book", 0, 0)) }
    private val hereChapter by lazy { intent.getIntExtra(EXTRA_CHAPTER, 0).coerceIn(0, chapters.size - 1) }
    private val herePos by lazy { intent.getIntExtra(EXTRA_POS, -1).takeIf { it >= 0 } ?: chapters[hereChapter].start }
    private lateinit var list: LinearLayout

    /** What the list was built from: it's rebuilt when this changes, and only the progress moves in between. */
    private var shown = ""
    private val tickers = mutableListOf<() -> Unit>()
    private val refresh = object : Runnable {
        override fun run() {
            if (signature() != shown) render() else tickers.forEach { it() }
            list.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        th = Themes.current(this)
        ui = Ui(this, th)
        Themes.applyChrome(this, th)
        Ui.edgeToEdge(this)
        val bar = ui.topBar("Pre-loaded audio", BookStore.prefs(this).getString("title_$bookId", bookId),
            nav = Ui.Action(R.drawable.ic_chevron_left, "Back") { finish() },
            actions = listOf(
                Ui.Action(R.drawable.ic_info, "About pre-loaded audio") { showHelp() },
                Ui.Action(R.drawable.ic_add, "Pre-load chapters") { newFile() },
            ))
        RenderManager.markInterrupted(this, bookId)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(list) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(th.bg)
            addView(bar.view)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            Ui.onInsets(this) { b ->
                bar.view.setPadding(b.left, b.top, b.right, 0)
                scroll.setPadding(b.left, 0, b.right, b.bottom)
            }
        })
        render()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_NEW, false)) list.post { newFile() }
    }

    override fun onResume() { super.onResume(); list.post(refresh) }
    override fun onPause() { super.onPause(); list.removeCallbacks(refresh) }

    // ---------- state ----------

    private enum class Kind { MAKING, QUEUED, READY, PAUSED, STOPPED, FAILED }

    private fun kindOf(r: Render) = when {
        RenderManager.current?.render?.id == r.id -> Kind.MAKING
        RenderManager.isWaiting(r.id) -> Kind.QUEUED
        r.ready -> Kind.READY
        r.status == "paused" -> Kind.PAUSED
        r.status == "interrupted" || r.status == "processing" -> Kind.STOPPED
        else -> Kind.FAILED
    }

    /** Pre-loading waits while the voice reads live (both use the same engine). */
    private fun onHold() = Narrator.instance?.let { it.isPlaying && !it.isPreviewing && !it.isPlayingFile } == true

    private fun signature() = Renders.list(this, bookId).joinToString(";") { "${it.id}:${kindOf(it)}:${it.parts.size}" } +
        "|${Renders.activeId(this, bookId)}|${RenderManager.pausing}|${onHold()}|${Background.unrestricted(this)}"

    private fun chapterEnd(i: Int) = if (i + 1 < chapters.size) chapters[i + 1].start else textLength
    private fun chapterName(i: Int) = if (book?.chapters.isNullOrEmpty()) chapters[i].title else chapters[i].displayName(i)
    private fun overlaps(r: Render, from: Int, to: Int) = r.fromChar < to && r.toChar > from

    /** The file that plays when you read from [pos], if any (see [Renders.playableAt]). */
    private fun usedAt(pos: Int) = Renders.playableAt(this, bookId, pos)

    // ---------- list ----------

    private fun render() {
        shown = signature()
        tickers.clear()
        list.removeAllViews()
        val renders = Renders.list(this, bookId).sortedWith(compareBy({ it.fromChar }, { it.created }))
        if (renders.isEmpty()) { list.addView(emptyState()); return }

        val busy = renders.any { kindOf(it).let { k -> k == Kind.MAKING || k == Kind.QUEUED } }
        if (busy && !Background.unrestricted(this)) list.addView(batteryRow())

        // what plays on the page you came from, then everything else in book order
        val hs = chapters[hereChapter].start
        val he = chapterEnd(hereChapter)
        val (here, rest) = renders.partition { overlaps(it, hs, he) }
        list.addView(header(chapterName(hereChapter), R.drawable.ic_bookmark))
        val live = Renders.activeId(this, bookId) == Renders.LIVE
        val used = usedAt(herePos)
        when {
            live -> list.addView(noteCard("Pre-loaded audio is off in this book: it reads live.", "Turn it on", R.drawable.ic_headphones) {
                Renders.setActive(this, bookId, null); render()
            })
            used == null -> list.addView(noteCard(
                if (here.isEmpty()) "This chapter reads live." else "Reads live until the audio for this page is pre-loaded.",
                if (here.isEmpty()) "Pre-load this chapter" else null, R.drawable.ic_add) { newFile() })
        }
        for (r in here) list.addView(card(r, playsHere = r.id == used?.id))
        if (rest.isNotEmpty()) {
            list.addView(header("Other chapters", null))
            for (r in rest) list.addView(card(r, playsHere = false))
        }
        list.addView(ui.pill("Pre-load more chapters", R.drawable.ic_add, filled = false, wide = true) { newFile() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    private fun header(text: String, icon: Int?) = ui.header(text, icon).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(4), dp(20), dp(4), dp(2))
    }

    /** A one-line card under a heading: what's going on, and an optional action. */
    private fun noteCard(text: String, action: String?, icon: Int, onAction: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(10), dp(10))
        minimumHeight = dp(60)
        background = ui.surface()
        addView(TextView(this@RendersActivity).apply {
            this.text = text; textSize = 14f; setTextColor(th.dim); setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) addView(ui.pill(action, icon, filled = true) { onAction() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    /** Shown only while something is being pre-loaded and the phone would slow it down with the screen off. */
    private fun batteryRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(10), dp(8))
        minimumHeight = dp(60)
        background = ui.rounded(ui.tinted(0.1f), 20, th.outline)
        addView(TextView(this@RendersActivity).apply {
            text = "About 3× slower with the screen off"; textSize = 13.5f; setTextColor(th.text); setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER_VERTICAL
            Icons.leading(this, R.drawable.ic_bolt, th.accent, sizeDp = 18f, gapDp = 10f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.pill("Fix", null, filled = false) { Background.request(this@RendersActivity) }.apply {
            contentDescription = "Allow unrestricted battery use, so pre-loading stays fast with the screen off"
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun emptyState() = ui.emptyState(R.drawable.ic_headphones, "Listen without waiting",
        "Pre-load a few chapters ahead of time: they play straight away, with no loading, and keep the phone cool. " +
            "You can start listening after a couple of minutes.",
        "Pre-load chapters") { newFile() }

    private fun card(r: Render, playsHere: Boolean): View {
        val kind = kindOf(r)
        val p = RenderManager.current?.takeIf { it.render.id == r.id }
        val pausing = kind == Kind.MAKING && RenderManager.pausing == r.id
        val hold = kind == Kind.MAKING && onHold()
        // the reader picks it for its own chapters (or for the page you came from)
        val inUse = playsHere || (r.playable && usedAt(r.fromChar)?.id == r.id)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(6), dp(8))
            background = ui.surface(if (playsHere) th.accent else null)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }

        // title row: status badge, name and voice, status pill
        val badgeIcon = when (kind) {
            Kind.MAKING -> R.drawable.ic_graphic_eq
            Kind.QUEUED -> R.drawable.ic_hourglass
            Kind.READY -> R.drawable.ic_headphones
            Kind.PAUSED, Kind.STOPPED -> R.drawable.ic_pause
            Kind.FAILED -> R.drawable.ic_error
        }
        val strong = kind == Kind.MAKING || kind == Kind.READY
        val status = when (kind) {
            Kind.MAKING -> if (pausing) "Pausing" else if (hold) "On hold" else "Pre-loading"
            Kind.QUEUED -> "Waiting"
            Kind.READY -> "Ready"
            Kind.PAUSED, Kind.STOPPED -> "Paused"
            Kind.FAILED -> "Didn't finish"
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
            addView(ImageView(this@RendersActivity).apply {
                setImageDrawable(Icons.get(this@RendersActivity, badgeIcon, if (strong) th.accent else th.dim, 22f))
                scaleType = ImageView.ScaleType.CENTER
                background = ui.oval(if (strong) ui.tinted(0.16f) else neutral(0.08f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@RendersActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@RendersActivity).apply {
                    text = r.label; textSize = 16f; setTextColor(th.text); typeface = medium
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@RendersActivity).apply {
                    text = if (kind == Kind.READY) "${RenderManager.formatDuration(r.durationMs)} · ${r.describe()}" else r.describe()
                    textSize = 13f; setTextColor(th.dim); setPadding(0, dp(2), 0, 0)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@RendersActivity).apply {
                text = status; textSize = 11.5f; typeface = medium; letterSpacing = 0.02f
                setTextColor(if (strong) th.accent else th.dim)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = ui.rounded(if (strong) ui.tinted(0.16f) else neutral(0.08f), 12)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        // progress: how much is pre-loaded, and what's going on
        if (kind != Kind.READY) {
            val bar = ui.progressBar(if (kind == Kind.MAKING && !pausing && !hold) th.accent else Themes.mix(th.surface, th.dim, 0.8f))
            val line = TextView(this).apply { textSize = 13f; setTextColor(th.text); setLineSpacing(0f, 1.2f); setPadding(0, dp(8), dp(10), 0) }
            fun update() {
                val fraction = p?.fraction ?: r.fraction
                val made = p?.audioMs ?: r.durationMs
                bar.progress = (fraction * 1000).toInt()
                val pct = "${(fraction * 100).toInt()}%"
                val ready = if (made > 0) " · ${RenderManager.formatDuration(made)} ready to play" else ""
                line.text = when (kind) {
                    Kind.MAKING -> pct + ready + when {
                        pausing -> " · pausing after this sentence"
                        hold -> " · waits while you listen live"
                        else -> p?.etaMs?.let { " · about ${RenderManager.formatDuration(it)} left" } ?: ""
                    }
                    Kind.QUEUED -> "Starts when the one before it is done" + if (fraction > 0) " · $pct pre-loaded" else ""
                    Kind.PAUSED -> "$pct pre-loaded$ready"
                    Kind.STOPPED -> "$pct pre-loaded · paused when the app was closed"
                    else -> if (r.playable) "Stopped at $pct by an error$ready" else "Couldn't be pre-loaded"
                }
                box.contentDescription = "${r.label}, $status, ${line.text}"
            }
            update()
            if (kind == Kind.MAKING) tickers += ::update
            box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(14); marginEnd = dp(10) })
            box.addView(line)
        }

        // which page it plays on
        val note = when {
            playsHere -> "Plays on the page you're on"
            inUse && r.ready -> "Plays when you read these chapters"
            inUse -> "Plays when you read the part pre-loaded so far"
            r.playable -> "Another pre-loaded audio covers these chapters"
            else -> null
        }
        note?.let { t ->
            box.addView(TextView(this).apply {
                text = t; textSize = 12.5f; setLineSpacing(0f, 1.2f)
                val c = if (inUse) th.accent else th.dim
                setTextColor(c)
                Icons.leading(this, if (inUse) R.drawable.ic_headphones else R.drawable.ic_info, c, sizeDp = 16f, gapDp = 8f)
                setPadding(0, dp(10), dp(10), 0)
            })
        }

        // the one obvious next step (filled), maybe a second, and the rest under "More"
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(v: View) = actions.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        val playPill = { filled: Boolean -> ui.pill("Play", R.drawable.ic_play, filled) { play(r) } }
        when (kind) {
            Kind.MAKING, Kind.QUEUED -> {
                if (r.playable) add(playPill(true))
                if (!pausing) add(ui.pill("Pause", R.drawable.ic_pause, filled = false) { RenderManager.pause(this, r.id); render() }
                    .apply { contentDescription = "Pause pre-loading, keep what's done" })
            }
            Kind.READY -> add(playPill(true))
            Kind.PAUSED, Kind.STOPPED -> {
                add(ui.pill("Resume", R.drawable.ic_download, filled = true) { resume(r) }
                    .apply { contentDescription = "Resume pre-loading" })
                if (r.playable) add(playPill(false))
            }
            Kind.FAILED -> {
                add(ui.pill("Try again", R.drawable.ic_refresh, filled = true) { resume(r) })
                if (r.playable) add(playPill(false))
            }
        }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(ui.iconButton(R.drawable.ic_more, "More for ${r.label}", tint = th.dim) { more(r, kind, inUse) })
        box.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return box
    }

    private fun neutral(t: Float) = Themes.mix(th.surface, th.text, t)

    /** The secondary actions for a file, with its details at the top. */
    private fun more(r: Render, kind: Kind, inUse: Boolean) {
        val making = kind == Kind.MAKING || kind == Kind.QUEUED
        val head = TextView(this).apply {
            text = listOfNotNull(r.describe(), RenderManager.formatDuration(r.durationMs).takeIf { r.durationMs > 0 },
                sizeMb(r).takeIf { r.durationMs > 0 }).joinToString(" · ")
            textSize = 13f; setTextColor(th.dim); setPadding(dp(14), 0, dp(14), dp(8))
        }
        ui.menuSheet(r.label, listOfNotNull(
            if (r.playable && r.fromChar != startOf(r)) Ui.Action(R.drawable.ic_skip_prev, "Play from its start") { play(r, fromStart = true) }
            else null,
            if (r.playable && !inUse) Ui.Action(R.drawable.ic_headphones, "Use this one for these chapters") {
                Renders.setActive(this, bookId, r.id); render()
            } else null,
            if (inUse) Ui.Action(R.drawable.ic_voice, "Read live instead") { Renders.setActive(this, bookId, Renders.LIVE); render() } else null,
            Ui.Action(R.drawable.ic_delete, if (making) "Stop and delete" else "Delete") { confirmDelete(r, making) },
        ), header = head)
    }

    /** Where "Play" starts: where you left off if the file covers it, else its start. */
    private fun startOf(r: Render): Int {
        val heard = BookStore.prefs(this).getInt("pos_$bookId", -1)
        return if (heard >= r.fromChar && heard < r.coveredTo) heard else r.fromChar
    }

    private fun sizeMb(r: Render): String {
        val mb = Renders.sizeBytes(this, r) / 1_048_576.0
        return if (mb < 10) "%.1f MB".format(java.util.Locale.US, mb) else "${mb.toInt()} MB"
    }

    /** Prefer [r] for its chapters and go back to the book, reading from where you are if it covers it, else its start. */
    private fun play(r: Render, fromStart: Boolean = false) {
        Renders.setActive(this, bookId, r.id)
        playRequest = bookId to (if (fromStart) r.fromChar else startOf(r))
        finish()
    }

    private fun confirmDelete(r: Render, making: Boolean) {
        val what = if (r.durationMs > 0) "The ${RenderManager.formatDuration(r.durationMs)} pre-loaded so far will be deleted." else ""
        Themes.dialog(this).setTitle(if (making) "Stop pre-loading and delete?" else "Delete this pre-loaded audio?")
            .setMessage("${r.label} · ${r.describe()}" + if (what.isNotEmpty()) "\n\n$what" else "")
            .setPositiveButton("Delete") { _, _ ->
                if (making) RenderManager.cancel(this, r.id) else Renders.delete(this, r)
                render()
            }
            .setNegativeButton("Keep", null).show()
    }

    /** Prepare it again from where it stopped, keeping the parts already made. */
    private fun resume(r: Render) {
        val b = book ?: return
        RenderManager.enqueue(this, r, b.text, specFrom(r.voiceId, r.settings))
        render()
    }

    private fun showHelp() {
        val restricted = !Background.unrestricted(this)
        ui.sheet("About pre-loaded audio") {
            fun para(t: String) = addView(ui.hint(t).apply { textSize = 14f; setTextColor(th.text); setPadding(dp(12), dp(4), dp(12), dp(8)) })
            para("Pre-loaded audio is made once, ahead of time, with the voice and settings you have now. It plays straight " +
                "away, with no loading, and keeps the phone cool on long listens.")
            para("You can start listening after the first couple of minutes, while the rest is pre-loaded. Where it ends, " +
                "reading carries on live.")
            para("When you read a chapter, its pre-loaded audio plays. If two cover the same chapters, the one you played last is used.")
            para("Pre-loading waits while you listen live: both use the same voice engine.")
            if (restricted) {
                para("Your phone slows this app down about 3× with the screen off. Allowing unrestricted battery use keeps pre-loading fast.")
                addView(ui.pill("Allow unrestricted battery use", R.drawable.ic_bolt, filled = false) { Background.request(this@RendersActivity) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); topMargin = dp(4) })
            }
        }.show()
    }

    // ---------- prepare chapters ----------

    private fun specFrom(voiceId: String, settings: String): Narrator.RenderSpec {
        val p = settings.split('|')
        val preset = VoicePreset.parse(p.take(6).joinToString("|")) ?: VoicePreset.current(this, Voices.byId(voiceId)!!)
        return Narrator.RenderSpec(voiceId, preset.speed, preset.pauseMs, preset.commaPauses, preset.commaPauseMs,
            preset.maxGapMs, p.getOrNull(6)?.toIntOrNull() ?: 2)
    }

    /**
     * The "Pre-load chapters" sheet: the book's chapters as a checklist (the one on screen preselected) with each
     * chapter's length and what's already pre-loaded; tapping the first and last picks a range. Or a set length from a
     * starting chapter. The audio length, file size and time to prepare follow every change.
     */
    private fun newFile() {
        val b = book ?: return
        val bookText = b.text
        val voice = Voices.byId(Narrator.instance?.voiceId) ?: Voices.resolve(this, bookId, BookStore.prefs(this).getString("lang2_$bookId", null))
        val preset = VoicePreset.current(this, voice)
        val phrasing = BookStore.prefs(this).getInt("phrasing", 2)
        val settings = preset.serialize() + "|" + phrasing
        val existing = Renders.list(this, bookId)
        var fromIdx = hereChapter
        var toIdx = fromIdx
        var byLength = false
        var lengthMin = 60

        fun msFor(chars: Int) = RenderManager.estimateMs(chars, preset.speed, preset.pauseMs)
        fun range(): Pair<Int, Int> {
            val from = chapters[fromIdx].start
            if (!byLength) return from to chapterEnd(toIdx)
            val wantMs = lengthMin * 60_000L
            var to = from
            while (to < bookText.length && msFor(to - from) < wantMs) to = (bookText.indexOf("\n\n", to + 1).takeIf { it > 0 } ?: bookText.length)
            return from to minOf(bookText.length, to)
        }
        /** Short form for the file's name: the chapter's own title if short, else its first words. */
        fun chapterShort(i: Int): String {
            val t = chapters[i].title.trim()
            return if (t.length <= 24) t else Regex("^\\S+(?:\\s+\\S+)?").find(t)?.value ?: "Chapter ${i + 1}"
        }
        /** What's already pre-loaded for a chapter: "ready" (all of it plays), "some" (part, or being pre-loaded). */
        fun preparedState(i: Int): String? {
            val s = chapters[i].start; val e = chapterEnd(i)
            if (existing.any { it.playable && it.fromChar <= s && it.coveredTo >= e }) return "ready"
            return if (existing.any { overlaps(it, s, e) }) "some" else null
        }
        val prepared = chapters.indices.map(::preparedState)
        fun chapterAt(pos: Int) = chapters.indexOfLast { it.start <= pos }.coerceAtLeast(0)
        val sameSettings = existing.filter { it.voiceId == voice.id && it.settings == settings && it.status != "failed" }

        /**
         * What pre-loading [from, to) means, given what's already pre-loaded with these very settings: nothing (it's
         * all there), adding the rest to a file that ends inside the selection, making only the part before a file
         * that covers the end, or a new file (a copy, when only other settings cover it).
         */
        class Plan(val kind: String, val from: Int, val to: Int, val extend: Render? = null, val note: String = "")
        fun plan(from: Int, to: Int): Plan {
            if (byLength) return Plan("new", from, to)
            if (sameSettings.any { it.fromChar <= from && it.toChar >= to })
                return Plan("dup", from, from, note = "Already pre-loaded with these settings.")
            val ext = sameSettings.filter { it.fromChar <= from && it.toChar > from && it.toChar < to }.maxByOrNull { it.toChar }
            if (ext != null) return Plan("extend", ext.toChar, to, ext,
                "Up to ${chapterShort(chapterAt(ext.toChar - 1))} is already pre-loaded with these settings: the rest is added to that file.")
            val tail = sameSettings.filter { it.fromChar > from && it.fromChar < to && it.toChar >= to }.minByOrNull { it.fromChar }
            if (tail != null) return Plan("partial", from, tail.fromChar,
                note = "From ${chapterShort(chapterAt(tail.fromChar))} on is already pre-loaded with these settings: only the chapters before it are made.")
            val other = existing.any { overlaps(it, from, to) && (it.voiceId != voice.id || it.settings != settings) }
            return Plan("new", from, to, note = if (other) "Already pre-loaded with another voice or settings: this makes a new copy." else "")
        }
        lateinit var makeBtn: TextView

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lengthChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val hintLine = TextView(this).apply { textSize = 12.5f; setTextColor(th.dim); setPadding(dp(4), dp(8), dp(4), dp(6)) }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows); overScrollMode = View.OVER_SCROLL_NEVER; isVerticalFadingEdgeEnabled = true }
        val summary = TextView(this).apply { textSize = 15f; typeface = medium; setTextColor(th.text) }
        val details = TextView(this).apply { textSize = 12.5f; setTextColor(th.dim); setLineSpacing(0f, 1.2f); setPadding(0, dp(3), 0, 0) }
        val presets = listOf(30, 60, 120, 240)
        lateinit var otherChip: TextView
        val rowViews = ArrayList<LinearLayout>()
        val checks = ArrayList<ImageView>()

        fun update() {
            if (toIdx < fromIdx) toIdx = fromIdx
            val (from, to) = range()
            val inRange = chapters.indices.filter { chapters[it].start < to && chapterEnd(it) > from || it == fromIdx }
            for (i in chapters.indices) {
                val on = i in inRange
                rowViews[i].background = ui.pressable(if (on) ui.tinted(0.12f, th.bar) else null)
                checks[i].setImageDrawable(if (on) Icons.get(this, R.drawable.ic_check, th.onAccent, 18f) else null)
                checks[i].background = if (on) ui.rounded(th.accent, 6) else ui.rounded(null, 6, th.dim, 1.5f)
                rowViews[i].isSelected = on
                rowViews[i].contentDescription = "${chapterName(i)}, about ${RenderManager.formatDuration(msFor(chapterEnd(i) - chapters[i].start))}" +
                    (when (prepared[i]) { "ready" -> ", already pre-loaded"; "some" -> ", partly pre-loaded"; else -> "" }) +
                    if (on) ", selected" else ""
            }
            lengthChips.visibility = if (byLength) View.VISIBLE else View.GONE
            hintLine.text = if (byLength) "Tap the chapter to start at." else "Tap the first and last chapter you want."
            val custom = lengthMin !in presets
            otherChip.text = if (custom) RenderManager.formatDuration(lengthMin * 60_000L) else "Other…"
            for (i in 0 until lengthChips.childCount) (lengthChips.getChildAt(i) as TextView).let { c ->
                val on = c.tag == lengthMin || (custom && c.tag == -1)
                c.setTextColor(if (on) th.onAccent else th.text)
                c.typeface = if (on) medium else Typeface.DEFAULT
                c.background = android.graphics.drawable.RippleDrawable(ui.ripple(th.accent),
                    if (on) ui.rounded(th.accent, 20) else ui.rounded(null, 20, th.outline), ui.rounded(-1, 20))
                c.isSelected = on
            }
            val pl = plan(from, to)
            val audio = msFor(pl.to - pl.from)
            val rtf = Narrator.instance?.currentRtf ?: 0.7
            val n = inRange.size
            summary.text = if (byLength) "About ${RenderManager.formatDuration(audio)}, from ${chapterName(fromIdx)}"
                else "${if (n == 1) "1 chapter" else "$n chapters"}" + if (pl.kind == "dup") "" else " · about ${RenderManager.formatDuration(audio)} to make"
            details.text = (if (pl.note.isNotEmpty()) pl.note + "\n" else "") + (if (pl.kind == "dup") "" else
                "${audio / 60_000 / 2 + 1} MB · takes about ${RenderManager.formatDuration((audio * rtf).toLong())} on this phone; " +
                "you can listen after a couple of minutes.\n") + "${voice.name} · ${preset.describe()} (change in Settings)"
            makeBtn.text = when (pl.kind) { "dup" -> "Already pre-loaded"; "extend" -> "Add chapters"; else -> "Pre-load" }
            makeBtn.contentDescription = makeBtn.text
            makeBtn.alpha = if (pl.kind == "dup") 0.5f else 1f
        }

        /** Checkbox-style range picking: outside extends, an end unchecks itself, the middle trims the end there. */
        fun tap(i: Int) {
            if (byLength) fromIdx = i
            else when {
                i < fromIdx -> fromIdx = i
                i > toIdx -> toIdx = i
                fromIdx == toIdx -> {}
                i == fromIdx -> fromIdx++
                i == toIdx -> toIdx--
                else -> toIdx = i
            }
            update()
        }

        for (i in chapters.indices) {
            val check = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            checks += check
            val minutes = RenderManager.formatDuration(msFor(chapterEnd(i) - chapters[i].start))
            rowViews += LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                addView(check, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                addView(TextView(this@RendersActivity).apply {
                    text = chapterName(i); textSize = 15f; setTextColor(th.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    if (i == hereChapter) typeface = medium
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@RendersActivity).apply {
                    textSize = 12.5f; gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    text = minutes
                    if (prepared[i] != null) {
                        setTextColor(th.accent)
                        Icons.leading(this, if (prepared[i] == "ready") R.drawable.ic_headphones else R.drawable.ic_graphic_eq, th.accent, 14f, 4f)
                    } else setTextColor(th.dim)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                setOnClickListener { tap(i) }
                rows.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            }
        }

        /** Any length: a number of minutes typed in; the "Other" chip then shows it. */
        fun pickLength() {
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "Minutes"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(th.text); setHintTextColor(th.dim)
                backgroundTintList = ColorStateList.valueOf(th.accent)
                if (lengthMin !in presets) { setText(lengthMin.toString()); setSelection(text.length) }
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }
            val holder = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            val dlg = Themes.dialog(this).setTitle("Length in minutes").setView(holder)
                .setPositiveButton("Use") { _, _ ->
                    input.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { lengthMin = it.coerceAtMost(1440); update() }
                }
                .setNegativeButton("Cancel", null).create()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dlg.show()
            input.requestFocus()
        }
        for (min in presets + -1) lengthChips.addView(TextView(this).apply {
            text = if (min < 0) "Other…" else RenderManager.formatDuration(min * 60_000L).replace(" 0 min", "")
            tag = min; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
            minHeight = dp(44)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            contentDescription = if (min < 0) "Other length, in minutes" else text
            setOnClickListener { if (min < 0) pickLength() else { lengthMin = min; update() } }
            if (min < 0) otherChip = this
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (min < 0) 0 else dp(6) })

        val modeChips = ui.chips(listOf("Chapters" to "ch", "Set length" to "len"), "ch") { byLength = it == "len"; update() }

        lateinit var sheet: Ui.Sheet
        fun make() {
            val (selFrom, selTo) = range()
            val pl = plan(selFrom, selTo)
            if (pl.kind == "dup") {
                Toast.makeText(this, "These chapters are already pre-loaded with the current settings.", Toast.LENGTH_SHORT).show()
                return
            }
            fun labelFor(from: Int, to: Int): String {
                val a = chapterAt(from); val b = chapterAt(to - 1)
                return if (a == b) chapterShort(a) else "${chapterShort(a)} – ${chapterShort(b)}"
            }
            // preparing something new turns pre-loaded audio back on in a book set to read live
            if (Renders.activeId(this, bookId) == Renders.LIVE) Renders.setActive(this, bookId, null)
            val ext = pl.extend
            val r = if (ext != null) {
                // the file that already covers the start grows: making resumes at its end, keeping its parts
                ext.copy(toChar = pl.to, label = labelFor(ext.fromChar, pl.to), status = "processing", doneChar = ext.coveredTo)
            } else {
                val label = if (byLength) "${chapterShort(fromIdx)} · ${RenderManager.formatDuration(lengthMin * 60_000L)}" else labelFor(pl.from, pl.to)
                Render("r" + System.currentTimeMillis(), bookId, pl.from, pl.to, label, voice.id, settings, "processing", 0, System.currentTimeMillis())
            }
            RenderManager.enqueue(this, r, bookText, specFrom(voice.id, settings))
            sheet.dismiss()
            render()
        }

        body.addView(modeChips, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
        body.addView(lengthChips, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(4); marginEnd = dp(4); topMargin = dp(4) })
        body.addView(hintLine)
        val tall = chapters.size > 6
        body.addView(scroll, if (tall) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2))
        body.addView(View(this).apply { setBackgroundColor(th.outline) }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)))
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), 0)
            addView(summary); addView(details)
            addView(LinearLayout(this@RendersActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(ui.pill("Cancel", null, filled = false) { sheet.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                addView(ui.pill("Pre-load", R.drawable.ic_download, filled = true) { make() }.also { makeBtn = it })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        })
        sheet = ui.sheet("Pre-load chapters", scroll = false, tall = tall) {
            addView(body, if (tall) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        update()
        sheet.show()
        // the chapter on screen in view, with a little of the one before
        scroll.post { rowViews.getOrNull(fromIdx)?.let { scroll.scrollTo(0, (it.top - dp(56)).coerceAtLeast(0)) } }
    }
}
