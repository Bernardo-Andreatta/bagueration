package com.bernardo.ereader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pre-made audio for a book: make files for a range of chapters (or a length) with the current voice and
 * settings, follow their progress, and pick which one plays when reading.
 */
class RendersActivity : Activity() {

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_CHAPTER = "chapter"
        /** "Play" was pressed on a file: the reader starts (book id, position) when it comes back. */
        var playRequest: Pair<String, Int>? = null
    }

    private lateinit var th: ReaderTheme
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK)!! }
    private val book by lazy { BookParser.load(BookStore.dir(this), bookId) }
    private lateinit var list: LinearLayout
    private val refresh = object : Runnable {
        override fun run() { render(); list.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        th = Themes.current(this)
        Themes.applyChrome(this, th)
        title = Themes.tint("Audio files", th.text)
        actionBar?.subtitle = Themes.tint(BookStore.prefs(this).getString("title_$bookId", bookId), th.dim)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setHomeAsUpIndicator(getDrawable(R.drawable.ic_chevron_left)?.mutate()?.apply { setTint(th.text) })
        RenderManager.markInterrupted(this, bookId)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(th.bg); addView(list) })
        render()
    }

    override fun onResume() { super.onResume(); list.post(refresh) }
    override fun onPause() { super.onPause(); list.removeCallbacks(refresh) }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val add = menu.add(0, 1, 0, "New audio file").setIcon(R.drawable.ic_add)
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        add.icon?.mutate()?.setTint(th.text)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> newFile()
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- list ----------

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "Make audio ahead of time and it plays with no loading, no matter how warm the phone gets. " +
                "Files use the voice and settings you have when you create them."
            textSize = 13f; setTextColor(th.dim); setLineSpacing(0f, 1.2f); setPadding(dp(4), 0, dp(4), dp(10))
        })
        if (!Background.unrestricted(this)) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(th.highlight) }
            addView(TextView(this@RendersActivity).apply {
                text = "With the screen off, your phone makes audio about 3× slower while this app is battery-optimized. " +
                    "Keep the app open for the fastest pre-load, or allow unrestricted battery use."
                textSize = 13f; setTextColor(th.text); setLineSpacing(0f, 1.2f)
            })
            addView(TextView(this@RendersActivity).apply {
                text = "Allow unrestricted battery use  ›"; textSize = 14f; setTextColor(th.accent); setPadding(0, dp(8), 0, 0)
                setOnClickListener { Background.request(this@RendersActivity) }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val renders = Renders.list(this, bookId)
        if (renders.isEmpty()) {
            list.addView(button("+  New audio file") { newFile() })
            return
        }
        val active = Renders.activeId(this, bookId)
        for (r in renders) list.addView(card(r, r.id == active))
        list.addView(button("+  New audio file") { newFile() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun card(r: Render, active: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(th.bar)
            if (active) setStroke(dp(2), th.accent)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        addView(TextView(this@RendersActivity).apply { text = r.label; textSize = 16f; setTextColor(th.text); typeface = Typeface.DEFAULT_BOLD })
        addView(TextView(this@RendersActivity).apply { text = r.describe(); textSize = 13f; setTextColor(th.dim) })

        val p = RenderManager.current?.takeIf { it.render.id == r.id }
        val waiting = RenderManager.isWaiting(r.id)
        val pausing = p != null && RenderManager.pausing == r.id
        val pct = "${(r.fraction * 100).toInt()}%"
        // parts finished so far: playable while the rest is made (or after making stopped)
        val canPlay = if (r.playable && !r.ready) "${RenderManager.formatDuration(r.durationMs)} ready to play" else null
        val status = when {
            p != null -> {
                addView(ProgressBar(this@RendersActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (p.fraction * 1000).toInt()
                    progressTintList = ColorStateList.valueOf(th.accent)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                val paused = Narrator.instance?.let { it.isPlaying && !it.isPreviewing && !it.isPlayingFile } == true
                "${(p.fraction * 100).toInt()}% · ${RenderManager.formatDuration(p.audioMs)} made" +
                    when {
                        pausing -> " · stopping at the end of this sentence"
                        paused -> " · paused while you listen"
                        else -> p.etaMs?.let { " · about ${RenderManager.formatDuration(it)} left" } ?: ""
                    } +
                    (if (canPlay != null) "\n$canPlay now · after that, reading continues live" else "")
            }
            waiting -> "Waiting for the current file to finish" + (canPlay?.let { "\n$it" } ?: "")
            r.ready -> "${RenderManager.formatDuration(r.durationMs)} · ${Renders.sizeBytes(this@RendersActivity, r) / 1_048_576} MB" +
                if (active) " · plays when you read these chapters" else ""
            r.status == "paused" -> "Paused at $pct" + (canPlay?.let { " · $it" } ?: "")
            r.status == "interrupted" -> "Stopped at $pct (the app was closed)" + (canPlay?.let { " · $it" } ?: "")
            else -> if (canPlay != null) "Stopped at $pct by an error · $canPlay" else "Couldn't be made"
        }
        addView(TextView(this@RendersActivity).apply {
            text = status; textSize = 13f; setLineSpacing(0f, 1.2f)
            setTextColor(if (r.playable || p != null) th.text else th.dim); setPadding(0, dp(6), 0, dp(4))
        })

        val actions = LinearLayout(this@RendersActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        fun action(label: String, onClick: () -> Unit) = actions.addView(TextView(this@RendersActivity).apply {
            text = label; textSize = 13f; setTextColor(th.accent); setPadding(0, dp(6), dp(18), dp(6))
            setOnClickListener { onClick() }
        })
        when {
            p != null || waiting -> {
                if (r.playable) action("Play") { play(r) }
                if (!pausing) action("Continue later") { RenderManager.pause(this@RendersActivity, r.id); render() }
                action("Cancel") { RenderManager.cancel(this@RendersActivity, r.id); render() }
            }
            r.ready -> {
                if (active) action("Don't use for playback") { Renders.setActive(this@RendersActivity, bookId, null); render() }
                else action("Use for playback") { Renders.setActive(this@RendersActivity, bookId, r.id); render() }
                action("Delete") { confirmDelete(r) }
            }
            else -> {
                if (r.playable) action("Play") { play(r) }
                action(if (r.playable) "Continue making" else "Try again") { resume(r) }
                action("Delete") { confirmDelete(r) }
            }
        }
        addView(actions)
    }

    /** Use [r] for playback and go back to the book, reading from where you are if the file covers it, else its start. */
    private fun play(r: Render) {
        Renders.setActive(this, bookId, r.id)
        val heard = BookStore.prefs(this).getInt("pos_$bookId", -1)
        RendersActivity.playRequest = bookId to (if (heard >= r.fromChar && heard < r.coveredTo) heard else r.fromChar)
        finish()
    }

    private fun confirmDelete(r: Render) {
        Themes.dialog(this).setTitle("Delete this audio file?")
            .setMessage("${r.label} · ${r.describe()}")
            .setPositiveButton("Delete") { _, _ -> Renders.delete(this, r); render() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Make it again from where it stopped, keeping the parts already made. */
    private fun resume(r: Render) {
        val b = book ?: return
        RenderManager.enqueue(this, r, b.text, specFrom(r.voiceId, r.settings))
        render()
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(th.onAccent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(th.accent) }
        setOnClickListener { onClick() }
    }

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
        fun msFor(chars: Int) = (chars / (14.5 * preset.speed) * 1000).toLong() + chars / 90 * preset.pauseMs  // ~1 sentence per 90 chars
        fun range(): Pair<Int, Int> {
            val from = chapters[fromIdx].start
            if (lengthMin == 0) return from to chapterEnd(toIdx)
            val wantMs = lengthMin * 60_000L
            var to = from
            while (to < bookText.length && msFor(to - from) < wantMs) to = (bookText.indexOf("\n\n", to + 1).takeIf { it > 0 } ?: bookText.length)
            return from to minOf(bookText.length, to)
        }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(6), dp(22), 0) }
        val fromRow = pickerRow("From")
        val toRow = pickerRow("To")
        val estimate = TextView(this).apply { textSize = 13f; setTextColor(th.text); setPadding(0, dp(12), 0, dp(4)) }
        val lengthChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun update() {
            if (toIdx < fromIdx) toIdx = fromIdx
            (fromRow.getChildAt(1) as TextView).text = "${fromIdx + 1}. ${chapters[fromIdx].title}  ›"
            (toRow.getChildAt(1) as TextView).text = if (lengthMin > 0) "about ${RenderManager.formatDuration(lengthMin * 60_000L)}  ›"
                else "${toIdx + 1}. ${chapters[toIdx].title}  ›"
            val (from, to) = range()
            val audio = msFor(to - from)
            val rtf = Narrator.instance?.currentRtf ?: 0.7
            estimate.text = "≈ ${RenderManager.formatDuration(audio)} of audio · about ${RenderManager.formatDuration((audio * rtf).toLong())} to make on this phone" +
                "\n≈ ${audio / 60_000 / 2 + 1} MB"
            for (i in 0 until lengthChips.childCount) (lengthChips.getChildAt(i) as TextView).let { c ->
                val on = c.tag == lengthMin
                c.setTextColor(if (on) th.onAccent else th.text)
                c.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); if (on) setColor(th.accent) else setStroke(dp(1), th.dim) }
            }
        }
        fromRow.setOnClickListener {
            Themes.dialog(this).setTitle("From chapter")
                .setSingleChoiceItems(chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.toTypedArray(), fromIdx) { dd, i -> fromIdx = i; dd.dismiss(); update() }
                .setNegativeButton("Close", null).show()
        }
        toRow.setOnClickListener {
            lengthMin = 0
            Themes.dialog(this).setTitle("Until the end of chapter")
                .setSingleChoiceItems(chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.drop(fromIdx).toTypedArray(), toIdx - fromIdx) { dd, i -> toIdx = fromIdx + i; dd.dismiss(); update() }
                .setNegativeButton("Close", null).show()
            update()
        }
        for ((label, min) in listOf("Chapters" to 0, "30 min" to 30, "1 h" to 60, "2 h" to 120, "4 h" to 240)) lengthChips.addView(TextView(this).apply {
            text = label; tag = min; textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { lengthMin = min; update() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        box.addView(fromRow); box.addView(toRow)
        box.addView(TextView(this).apply { text = "Or a length:"; textSize = 12f; setTextColor(th.dim); setPadding(0, dp(8), 0, dp(6)) })
        box.addView(lengthChips)
        box.addView(estimate)
        box.addView(TextView(this).apply {
            text = "Voice: ${voice.name} · ${preset.describe()}\nTo use other settings, change them in Settings first."
            textSize = 12f; setTextColor(th.dim); setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, 0)
        })
        update()

        Themes.dialog(this).setTitle("New audio file").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Make it") { _, _ ->
                val (from, to) = range()
                val label = when {
                    lengthMin > 0 -> "Chapter ${fromIdx + 1} · ${RenderManager.formatDuration(lengthMin * 60_000L)}"
                    fromIdx == toIdx -> "Chapter ${fromIdx + 1}"
                    else -> "Chapters ${fromIdx + 1}–${toIdx + 1}"
                }
                val settings = preset.serialize() + "|" + phrasing
                val r = Render("r" + System.currentTimeMillis(), bookId, from, to, label, voice.id, settings, "processing", 0, System.currentTimeMillis())
                RenderManager.enqueue(this, r, bookText, specFrom(voice.id, settings))
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickerRow(label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@RendersActivity).apply { text = label; textSize = 14f; setTextColor(th.dim); minWidth = dp(56) })
        addView(TextView(this@RendersActivity).apply { textSize = 15f; setTextColor(th.accent); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
    }
}
