package com.bernardo.ereader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pre-made audio for a book: make files for a range of chapters (or a length) with the current voice and
 * settings, follow their progress, and pick which one plays when reading. A file can be played while it's
 * still being made (the parts finished so far), and "Continue later" stops making it, keeping what's done.
 */
class RendersActivity : Activity() {

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_CHAPTER = "chapter"
        /** "Play" was pressed on a file: the reader starts (book id, position) when it comes back. */
        var playRequest: Pair<String, Int>? = null
    }

    private lateinit var th: ReaderTheme
    private lateinit var ui: Ui
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dp(v: Float) = (v * d).toInt()
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK)!! }
    private val book by lazy { BookParser.load(BookStore.dir(this), bookId) }
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
        val bar = ui.topBar("Audio files", BookStore.prefs(this).getString("title_$bookId", bookId),
            nav = Ui.Action(R.drawable.ic_chevron_left, "Back") { finish() },
            actions = listOf(Ui.Action(R.drawable.ic_add, "New audio file") { newFile() }))
        RenderManager.markInterrupted(this, bookId)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
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

    /** Making waits while the voice reads live (both use the same engine). */
    private fun onHold() = Narrator.instance?.let { it.isPlaying && !it.isPreviewing && !it.isPlayingFile } == true

    private fun signature() = Renders.list(this, bookId).joinToString(";") { "${it.id}:${kindOf(it)}:${it.parts.size}" } +
        "|${Renders.activeId(this, bookId)}|${RenderManager.pausing}|${onHold()}|${Background.unrestricted(this)}"

    // ---------- list ----------

    private fun render() {
        shown = signature()
        tickers.clear()
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "Audio made ahead of time plays straight away, with no loading, and keeps the phone cool. " +
                "Each file keeps the voice and settings it was made with."
            textSize = 13f; setTextColor(th.dim); setLineSpacing(0f, 1.25f); setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        if (!Background.unrestricted(this)) list.addView(batteryCard())

        val renders = Renders.list(this, bookId)
        if (renders.isEmpty()) { list.addView(emptyState()); return }

        list.addView(header(if (renders.size == 1) "1 file" else "${renders.size} files"))
        val active = Renders.activeId(this, bookId)
        for (r in renders) list.addView(card(r, r.id == active))
        list.addView(pill("New audio file", R.drawable.ic_add, filled = false, wide = true) { newFile() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    private fun header(text: String) = ui.header(text).apply { setPadding(dp(4), dp(18), dp(4), dp(2)) }

    private fun surface(stroke: Int? = null) = ui.surface(stroke)

    private fun batteryCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(14))
        background = surface()
        addView(TextView(this@RendersActivity).apply {
            text = "Faster with the screen off"; textSize = 15f; setTextColor(th.text); typeface = medium; gravity = Gravity.CENTER_VERTICAL
            Icons.leading(this, R.drawable.ic_bolt, th.accent, sizeDp = 20f, gapDp = 10f)
        })
        addView(TextView(this@RendersActivity).apply {
            text = "While this app is battery-optimized, your phone makes audio about 3× slower with the screen off. " +
                "Keep the app open, or allow unrestricted battery use."
            textSize = 13f; setTextColor(th.dim); setLineSpacing(0f, 1.25f); setPadding(0, dp(6), 0, dp(12))
        })
        addView(pill("Allow unrestricted battery use", null, filled = false) { Background.request(this@RendersActivity) },
            LinearLayout.LayoutParams(-2, -2))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun emptyState() = ui.emptyState(R.drawable.ic_audio_file, "No audio files yet",
        "Pick some chapters or a length, and the audio is made in the background. " +
            "You can start listening after the first few minutes, while the rest is still being made.",
        "New audio file") { newFile() }

    private fun card(r: Render, active: Boolean): View {
        val kind = kindOf(r)
        val p = RenderManager.current?.takeIf { it.render.id == r.id }
        val pausing = kind == Kind.MAKING && RenderManager.pausing == r.id
        val hold = kind == Kind.MAKING && onHold()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = surface(if (active) Themes.mix(th.surface, th.accent, 0.75f) else null)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }

        // title row: status badge, name and voice, status pill
        val badgeIcon = when (kind) {
            Kind.MAKING -> R.drawable.ic_graphic_eq
            Kind.QUEUED -> R.drawable.ic_hourglass
            Kind.READY -> R.drawable.ic_audio_file
            Kind.PAUSED, Kind.STOPPED -> R.drawable.ic_pause
            Kind.FAILED -> R.drawable.ic_error
        }
        val strong = kind == Kind.MAKING || kind == Kind.READY
        val pillText = when (kind) {
            Kind.MAKING -> if (pausing) "Stopping" else if (hold) "On hold" else "Making"
            Kind.QUEUED -> "Queued"
            Kind.READY -> "Ready"
            Kind.PAUSED -> "Paused"
            Kind.STOPPED -> "Stopped"
            Kind.FAILED -> "Failed"
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@RendersActivity).apply {
                setImageDrawable(Icons.get(this@RendersActivity, badgeIcon, if (strong) th.accent else th.dim, 22f))
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (strong) tinted(0.16f) else neutral(0.08f)) }
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@RendersActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@RendersActivity).apply {
                    text = r.label; textSize = 16f; setTextColor(th.text); typeface = medium
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@RendersActivity).apply {
                    text = if (kind == Kind.READY) "${RenderManager.formatDuration(r.durationMs)} · ${sizeMb(r)} · ${r.describe()}" else r.describe()
                    textSize = 13f; setTextColor(th.dim); setPadding(0, dp(2), 0, 0)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@RendersActivity).apply {
                text = pillText; textSize = 11.5f; typeface = medium; letterSpacing = 0.02f
                setTextColor(if (strong) th.accent else th.dim)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (strong) tinted(0.16f) else neutral(0.08f)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        // progress: how much is made, and what's going on
        if (kind != Kind.READY) {
            val bar = progressBar(if (kind == Kind.MAKING && !pausing && !hold) th.accent else Themes.mix(th.surface, th.dim, 0.8f))
            val line = TextView(this).apply { textSize = 13f; setTextColor(th.text); setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, 0) }
            fun update() {
                val fraction = p?.fraction ?: r.fraction
                val made = p?.audioMs ?: r.durationMs
                bar.progress = (fraction * 1000).toInt()
                val pct = "${(fraction * 100).toInt()}%"
                line.text = when (kind) {
                    Kind.MAKING -> "$pct · ${RenderManager.formatDuration(made)} made" + when {
                        pausing -> " · stopping after this sentence"
                        hold -> " · waits while you listen live"
                        else -> p?.etaMs?.let { " · about ${RenderManager.formatDuration(it)} left" } ?: ""
                    }
                    Kind.QUEUED -> "$pct made · starts when the file being made now is done"
                    Kind.PAUSED -> "$pct made · ${RenderManager.formatDuration(r.durationMs)} of audio"
                    Kind.STOPPED -> "$pct made · stopped when the app was closed"
                    else -> if (r.playable) "Stopped at $pct by an error · ${RenderManager.formatDuration(r.durationMs)} kept" else "Couldn't be made"
                }
            }
            update()
            if (kind == Kind.MAKING) tickers += ::update
            box.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(14) })
            box.addView(line)
        }

        // what you can do with it now
        val note: Pair<Int, String>? = when {
            kind == Kind.MAKING || kind == Kind.QUEUED ->
                if (r.playable) R.drawable.ic_info to "You can play the ${RenderManager.formatDuration(r.durationMs)} made so far. " +
                    "When it runs out, reading carries on live."
                else R.drawable.ic_info to "You can start playing as soon as the first couple of minutes are made."
            active && r.playable -> R.drawable.ic_headphones to
                if (r.ready) "Plays when you read these chapters" else "Plays when you read the part that's made"
            else -> null
        }
        note?.let { (icon, t) ->
            box.addView(TextView(this).apply {
                text = t; textSize = 12.5f; setLineSpacing(0f, 1.2f)
                val c = if (icon == R.drawable.ic_headphones) th.accent else th.dim
                setTextColor(c)
                Icons.leading(this, icon, c, sizeDp = 16f, gapDp = 8f)
                setPadding(0, dp(10), 0, 0)
            })
        }

        // actions: main ones on the left, delete at the end
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(v: View) = actions.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        when (kind) {
            Kind.MAKING, Kind.QUEUED -> {
                if (r.playable) add(pill("Play", R.drawable.ic_play, filled = true) { play(r) })
                if (!pausing) add(pill("Continue later", R.drawable.ic_pause, filled = !r.playable) {
                    RenderManager.pause(this, r.id); render()
                })
            }
            Kind.READY -> {
                add(pill("Play", R.drawable.ic_play, filled = true) { play(r) })
                if (active) add(pill("Stop using", null, filled = false) { Renders.setActive(this, bookId, null); render() })
                else add(pill("Use when reading", R.drawable.ic_headphones, filled = false) { Renders.setActive(this, bookId, r.id); render() })
            }
            else -> {
                if (r.playable) add(pill("Play", R.drawable.ic_play, filled = true) { play(r) })
                add(pill(if (kind == Kind.FAILED && !r.playable) "Try again" else "Continue making",
                    if (kind == Kind.FAILED) R.drawable.ic_refresh else R.drawable.ic_download, filled = !r.playable) { resume(r) })
            }
        }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(iconButton(R.drawable.ic_delete, if (kind == Kind.MAKING || kind == Kind.QUEUED) "Stop and delete" else "Delete") {
            confirmDelete(r, kind)
        })
        box.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        return box
    }

    private fun sizeMb(r: Render): String {
        val mb = Renders.sizeBytes(this, r) / 1_048_576.0
        return if (mb < 10) "%.1f MB".format(java.util.Locale.US, mb) else "${mb.toInt()} MB"
    }

    /** Use [r] for playback and go back to the book, reading from where you are if the file covers it, else its start. */
    private fun play(r: Render) {
        Renders.setActive(this, bookId, r.id)
        val heard = BookStore.prefs(this).getInt("pos_$bookId", -1)
        RendersActivity.playRequest = bookId to (if (heard >= r.fromChar && heard < r.coveredTo) heard else r.fromChar)
        finish()
    }

    private fun confirmDelete(r: Render, kind: Kind) {
        val making = kind == Kind.MAKING || kind == Kind.QUEUED
        val what = if (r.durationMs > 0) "The ${RenderManager.formatDuration(r.durationMs)} of audio in it will be deleted." else ""
        Themes.dialog(this).setTitle(if (making) "Stop and delete this file?" else "Delete this audio file?")
            .setMessage("${r.label} · ${r.describe()}" + if (what.isNotEmpty()) "\n\n$what" else "")
            .setPositiveButton("Delete") { _, _ ->
                if (making) RenderManager.cancel(this, r.id) else Renders.delete(this, r)
                render()
            }
            .setNegativeButton("Keep", null).show()
    }

    /** Make it again from where it stopped, keeping the parts already made. */
    private fun resume(r: Render) {
        val b = book ?: return
        RenderManager.enqueue(this, r, b.text, specFrom(r.voiceId, r.settings))
        render()
    }

    // ---------- building blocks (the shared ones in Ui) ----------

    private fun tinted(t: Float) = ui.tinted(t)
    private fun neutral(t: Float) = Themes.mix(th.surface, th.text, t)
    private fun progressBar(color: Int) = ui.progressBar(color)
    private fun pill(text: String, icon: Int?, filled: Boolean, wide: Boolean = false, onClick: () -> Unit) = ui.pill(text, icon, filled, wide, onClick)
    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ui.iconButton(icon, description, tint = th.dim, onClick = onClick)

    // ---------- new file ----------

    private fun specFrom(voiceId: String, settings: String): Narrator.RenderSpec {
        val p = settings.split('|')
        val preset = VoicePreset.parse(p.take(6).joinToString("|")) ?: VoicePreset.current(this, Voices.byId(voiceId)!!)
        return Narrator.RenderSpec(voiceId, preset.speed, preset.pauseMs, preset.commaPauses, preset.commaPauseMs,
            preset.maxGapMs, p.getOrNull(6)?.toIntOrNull() ?: 2)
    }

    private fun newFile() {
        val b = book ?: return
        val bookText = b.text
        val chapters = b.chapters.ifEmpty { listOf(Chapter("Whole book", 0, 0)) }
        val voice = Voices.byId(Narrator.instance?.voiceId) ?: Voices.resolve(this, bookId, BookStore.prefs(this).getString("lang2_$bookId", null))
        val preset = VoicePreset.current(this, voice)
        val phrasing = BookStore.prefs(this).getInt("phrasing", 2)
        var fromIdx = intent.getIntExtra(EXTRA_CHAPTER, 0).coerceIn(0, chapters.size - 1)
        var toIdx = fromIdx
        var lengthMin = 0   // 0 = until a chapter

        fun chapterEnd(i: Int) = if (i + 1 < chapters.size) chapters[i + 1].start else bookText.length
        fun msFor(chars: Int) = RenderManager.estimateMs(chars, preset.speed, preset.pauseMs)
        fun range(): Pair<Int, Int> {
            val from = chapters[fromIdx].start
            if (lengthMin == 0) return from to chapterEnd(toIdx)
            val wantMs = lengthMin * 60_000L
            var to = from
            while (to < bookText.length && msFor(to - from) < wantMs) to = (bookText.indexOf("\n\n", to + 1).takeIf { it > 0 } ?: bookText.length)
            return from to minOf(bookText.length, to)
        }
        fun chapterName(i: Int) = chapters[i].displayName(i)
        /** Short form for the file's name: the chapter's own number if it has one, else its place in the book. */
        fun chapterShort(i: Int): String {
            val t = chapters[i].title.trim()
            return if (t.length <= 24) t else Regex("^\\S+(?:\\s+\\S+)?").find(t)?.value ?: "Chapter ${i + 1}"
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        val fromRow = pickerRow("Start at")
        val toRow = pickerRow("Until the end of")
        val audioLine = infoLine(R.drawable.ic_timer, th.text)
        val makeLine = infoLine(R.drawable.ic_hourglass, th.dim)
        val lengthChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val presets = listOf("Chapters" to 0, "30 min" to 30, "1 h" to 60, "2 h" to 120, "4 h" to 240)
        lateinit var otherChip: TextView
        fun update() {
            if (toIdx < fromIdx) toIdx = fromIdx
            fromRow.value.text = chapterName(fromIdx)
            toRow.label.text = if (lengthMin > 0) "Length" else "Until the end of"
            toRow.value.text = if (lengthMin > 0) "About ${RenderManager.formatDuration(lengthMin * 60_000L)}" else chapterName(toIdx)
            val (from, to) = range()
            val audio = msFor(to - from)
            val rtf = Narrator.instance?.currentRtf ?: 0.7
            audioLine.text = "About ${RenderManager.formatDuration(audio)} of audio · ${audio / 60_000 / 2 + 1} MB"
            makeLine.text = "Takes about ${RenderManager.formatDuration((audio * rtf).toLong())} to make on this phone"
            // a typed-in length lights the "Other" chip and shows there
            val custom = lengthMin > 0 && presets.none { it.second == lengthMin }
            otherChip.text = if (custom) RenderManager.formatDuration(lengthMin * 60_000L) else "Other…"
            for (i in 0 until lengthChips.childCount) (lengthChips.getChildAt(i) as TextView).let { c ->
                val on = c.tag == lengthMin || (custom && c.tag == -1)
                c.setTextColor(if (on) th.onAccent else th.text)
                c.typeface = if (on) medium else Typeface.DEFAULT
                c.background = RippleDrawable(ui.ripple(th.accent), if (on) ui.rounded(th.accent, 20) else ui.rounded(null, 20, th.outline),
                    ui.rounded(-1, 20))
                c.isSelected = on
            }
        }
        fromRow.view.setOnClickListener {
            Themes.dialog(this).setTitle("Start at chapter")
                .setSingleChoiceItems(chapters.indices.map(::chapterName).toTypedArray(), fromIdx) { dd, i -> fromIdx = i; dd.dismiss(); update() }
                .setNegativeButton("Close", null).show()
        }
        toRow.view.setOnClickListener {
            lengthMin = 0
            update()
            Themes.dialog(this).setTitle("Until the end of chapter")
                .setSingleChoiceItems(chapters.indices.drop(fromIdx).map(::chapterName).toTypedArray(), toIdx - fromIdx) { dd, i -> toIdx = fromIdx + i; dd.dismiss(); update() }
                .setNegativeButton("Close", null).show()
        }
        /** Any length: a number of minutes typed in; the "Other" chip then shows it. */
        fun pickLength() {
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "Minutes"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(th.text); setHintTextColor(th.dim)
                backgroundTintList = ColorStateList.valueOf(th.accent)
                if (lengthMin > 0 && presets.none { it.second == lengthMin }) { setText(lengthMin.toString()); setSelection(text.length) }
                filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            }
            val holder = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            val dlg = Themes.dialog(this).setTitle("Length of audio").setView(holder)
                .setPositiveButton("Use") { _, _ ->
                    input.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { lengthMin = it.coerceAtMost(1440); update() }
                }
                .setNegativeButton("Cancel", null).create()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dlg.show()
            input.requestFocus()
        }
        for ((label, min) in presets + ("Other…" to -1)) lengthChips.addView(TextView(this).apply {
            text = label; tag = min; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
            minHeight = dp(44)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { if (min < 0) pickLength() else { lengthMin = min; update() } }
            if (min < 0) otherChip = this
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (min < 0) 0 else dp(6) })

        box.addView(fromRow.view)
        box.addView(toRow.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        box.addView(TextView(this).apply {
            text = "Or make a set length"; textSize = 12.5f; setTextColor(th.dim); setPadding(dp(2), dp(14), 0, dp(8))
        })
        box.addView(lengthChips)
        box.addView(View(this).apply { setBackgroundColor(th.outline) },
            LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { topMargin = dp(16); bottomMargin = dp(6) })
        box.addView(audioLine)
        box.addView(makeLine)
        box.addView(infoLine(R.drawable.ic_play, th.dim).apply {
            text = "You can start listening after the first couple of minutes, while the rest is made."
        })
        box.addView(infoLine(R.drawable.ic_voice, th.dim).apply {
            text = "${voice.name} · ${preset.describe()}. To use other settings, change them in Settings first."
        })
        update()

        lateinit var sheet: Ui.Sheet
        fun make() {
            val (from, to) = range()
            val label = when {
                lengthMin > 0 -> "${chapterShort(fromIdx)} · ${RenderManager.formatDuration(lengthMin * 60_000L)}"
                fromIdx == toIdx -> chapterShort(fromIdx)
                else -> "${chapterShort(fromIdx)} – ${chapterShort(toIdx)}"
            }
            val settings = preset.serialize() + "|" + phrasing
            val r = Render("r" + System.currentTimeMillis(), bookId, from, to, label, voice.id, settings, "processing", 0, System.currentTimeMillis())
            RenderManager.enqueue(this, r, bookText, specFrom(voice.id, settings))
            sheet.dismiss()
            render()
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(pill("Cancel", null, filled = false) { sheet.dismiss() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(pill("Make it", R.drawable.ic_audio_file, filled = true) { make() })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        sheet = ui.sheet("New audio file") { addView(box) }
        sheet.show()
    }

    private class Picker(val view: View, val label: TextView, val value: TextView)

    /** A tappable field in the new-file dialog: small label, the current choice, and a chevron. */
    private fun pickerRow(label: String): Picker {
        val labelView = TextView(this).apply { text = label; textSize = 12f; setTextColor(th.dim) }
        val valueView = TextView(this).apply {
            textSize = 15f; setTextColor(th.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = RippleDrawable(ui.ripple(th.accent), ui.rounded(th.surface, 14, th.outline), ui.rounded(-1, 14))
            addView(LinearLayout(this@RendersActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelView); addView(valueView)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(FrameLayout(this@RendersActivity).apply {
                addView(ImageView(this@RendersActivity).apply {
                    setImageDrawable(Icons.get(this@RendersActivity, R.drawable.ic_expand_more, th.dim, 22f))
                })
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        return Picker(row, labelView, valueView)
    }

    private fun infoLine(icon: Int, color: Int) = TextView(this).apply {
        textSize = 13f; setTextColor(color); setLineSpacing(0f, 1.2f); gravity = Gravity.CENTER_VERTICAL
        Icons.leading(this, icon, if (color == th.text) th.accent else th.dim, sizeDp = 18f, gapDp = 10f)
        setPadding(0, dp(8), 0, 0)
    }
}
