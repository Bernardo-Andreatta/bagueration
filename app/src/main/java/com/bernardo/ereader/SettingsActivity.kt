package com.bernardo.ereader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * All settings in one place: voice, speed, pauses, gap shortening, appearance.
 * Voice settings apply live to the running narrator; appearance is applied when you go back.
 */
class SettingsActivity : Activity() {

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_APPEARANCE = "appearance"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_BOOK_LANG = "book_lang"
    }

    private val prefs by lazy { BookStore.prefs(this) }
    private lateinit var th: ReaderTheme
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private val narrator get() = Narrator.instance
    private var appearanceCard: View? = null
    private var previewButton: TextView? = null
    private val previewIdle = "▶  Play sample"
    private val previewWatch = object : Runnable {
        override fun run() {
            if (narrator?.isPreviewing == true) previewButton?.postDelayed(this, 300) else previewButton?.text = previewIdle
        }
    }

    /**
     * A sound setting changed. If the book is reading, apply it there without stopping: pauses take effect
     * immediately, anything that changes the generated speech re-renders from the current sentence.
     * Otherwise play the sample so the change can be heard.
     */
    private fun soundChanged(regenerate: Boolean) {
        val n = narrator ?: return
        if (n.isPlaying && !n.isPreviewing) { if (regenerate) n.applyToPlaying() } else playPreview()
    }

    /** Play the sample with whatever the settings are right now. */
    private fun playPreview() {
        val v = currentVoice()
        narrator?.preview(Voices.sample(v.lang)) ?: return   // same standard line as the voice picker
        previewButton?.text = "■  Stop"
        previewButton?.removeCallbacks(previewWatch)
        previewButton?.postDelayed(previewWatch, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private var scrollView: ScrollView? = null
    private var holder: android.widget.FrameLayout? = null

    private fun build() {
        val keepY = scrollView?.scrollY
        th = Themes.current(this)
        Themes.applyChrome(this, th)
        title = Themes.tint("Settings", th.text)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.setHomeAsUpIndicator(getDrawable(R.drawable.ic_chevron_left)?.mutate()?.apply { setTint(th.text) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(28)) }
        col.addView(voiceCard())
        col.addView(pausesCard())
        if (bookId != null) col.addView(card("Audio files") {
            val n = Renders.list(this@SettingsActivity, bookId!!).count { it.ready }
            addView(row("Audio files for this book", if (n == 0) "none yet" else "$n ready") {
                startActivity(android.content.Intent(this@SettingsActivity, RendersActivity::class.java).putExtra(RendersActivity.EXTRA_BOOK, bookId))
            })
            addView(hintView("Make audio ahead of time for a few chapters: it plays with no loading, even on a warm phone."))
        })
        if (bookId != null) col.addView(pronunciationsCard())
        col.addView(appearanceCard().also { appearanceCard = it })
        col.addView(TextView(this).apply {
            text = "Reset to defaults"
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(th.accent)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), th.accent) }
            setOnClickListener { confirmResetAll() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        col.addView(aboutCard())
        val scroll = ScrollView(this).apply { setBackgroundColor(th.bg); addView(col) }
        val old = scrollView
        scrollView = scroll
        val root = holder ?: android.widget.FrameLayout(this).also { holder = it; setContentView(it) }
        root.setBackgroundColor(th.bg)
        if (old == null) root.addView(scroll)
        else {
            // fade the rebuilt screen in over the old one instead of a hard swap (no flash)
            scroll.alpha = 0f
            root.addView(scroll)
            scroll.animate().alpha(1f).setDuration(160).withEndAction { root.removeView(old) }.start()
        }
        if (keepY != null) {
            scroll.post { scroll.scrollTo(0, keepY) }   // rebuilt after a change: stay where you were
        } else if (intent.getStringExtra(EXTRA_SECTION) == SECTION_APPEARANCE) {
            scroll.post { appearanceCard?.let { scroll.scrollTo(0, it.top - dp(8)) } }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (narrator?.isPreviewing == true) narrator?.stop()
        super.onDestroy()
    }

    // ---------- sections ----------

    private fun voiceCard() = card("Voice") {
        val v = currentVoice()
        addView(row("Voice", "${v.name}  ·  ${Voices.LANGUAGES[v.lang]}") { pickVoice() })
        addView(presetRow(v))
        Voices.blendPartner(v)?.let { partner ->
            addView(label("Blend with ${partner.name}"))
            addView(chips(listOf("Off" to "0", "Light" to "1", "Half" to "2"), Voices.blendLevel(this@SettingsActivity, v).toString()) { lvl ->
                Voices.setBlendLevel(this@SettingsActivity, v, lvl.toInt())
                soundChanged(regenerate = true)
            })
            addView(hintView("Mixes in some of ${partner.name}, the steadiest ${v.note.substringBefore(" ·")} voice. Smooths out hesitations while keeping ${v.name}'s character."))
        }
        bookLang?.let { lang ->
            val hasVoice = Voices.defaultFor(this@SettingsActivity, lang) != null
            addView(hintView("This book is in ${Voices.LANGUAGES[lang] ?: lang}." +
                if (!hasVoice) " You don't have a voice for it yet — tap Voice to get one." else ""))
        }
        addView(TextView(this@SettingsActivity).apply {
            text = if (narrator?.isPreviewing == true) "■  Stop" else previewIdle
            textSize = 14f
            setTextColor(th.onAccent)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(th.accent)
            previewButton = this
            setOnClickListener {
                if (narrator?.isPreviewing == true) { narrator?.stop(); text = previewIdle } else playPreview()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        addView(hintView(if (narrator?.let { it.isPlaying && !it.isPreviewing } == true)
            "Changes apply to the book as it reads." else "The sample replays when you change speed or pauses."))

        slider("Speed", 0.5f, 2.0f, 0.05f, prefs.getFloat("speed", 1.0f), default = rec().speed,
            format = { "${"%.2f".format(Locale.US, it).trimEnd('0').trimEnd('.')}×" }) { v ->
            prefs.edit().putFloat("speed", v).apply()
            narrator?.speed = v
            soundChanged(regenerate = true)
        }
    }

    /** Recommended settings for the voice in use ("Default" on the sliders). */
    private fun rec() = Presets.recommended(currentVoice())

    /** "Settings for <voice>": use recommended / use mine / save current as mine. */
    private fun presetRow(v: VoiceInfo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, dp(8))
        val recommended = Presets.recommended(v)
        val mine = Presets.custom(this@SettingsActivity, v)
        val now = VoicePreset.current(this@SettingsActivity, v)
        val status = when (now) {
            recommended -> "Using ${v.name}'s recommended settings."
            mine -> "Using your saved settings for ${v.name}."
            else -> "Custom settings."
        }
        addView(hintView("$status\nRecommended: ${recommended.describe()}"))
        val chips = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(label: String, onClick: () -> Unit) = TextView(this@SettingsActivity).apply {
            text = label; textSize = 13f; setTextColor(th.accent); gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), th.accent) }
            setOnClickListener { onClick() }
        }.also { chips.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
        if (now != recommended) chip("Use recommended") { applyPreset(recommended) }
        if (mine != null && now != mine) chip("Use mine") { applyPreset(mine) }
        if (now != mine) chip("Save as mine") {
            Presets.saveCustom(this@SettingsActivity, v, now)
            Toast.makeText(this@SettingsActivity, "Saved as your settings for ${v.name}", Toast.LENGTH_SHORT).show()
            build()
        }
        if (chips.childCount > 0) addView(android.widget.HorizontalScrollView(this@SettingsActivity).apply {
            isHorizontalScrollBarEnabled = false; addView(chips)
        })
    }

    private fun applyPreset(p: VoicePreset) {
        p.apply(this, narrator, currentVoice())
        soundChanged(regenerate = true)
        build()
    }

    /** After picking a voice: offer its recommended settings (or your saved ones), or keep the current ones. */
    private fun offerPreset(v: VoiceInfo) {
        val recommended = Presets.recommended(v)
        val mine = Presets.custom(this, v)
        val now = VoicePreset.current(this, v)
        if (now == recommended && mine == null) return
        if (mine != null && now == mine) return
        val d = Themes.dialog(this).setTitle("Settings for ${v.name}")
            .setMessage(if (mine != null) "You saved settings for ${v.name}. Use them, or the recommended ones?\n\nRecommended: ${recommended.describe()}"
                        else "${v.name} sounds most natural with slightly different settings:\n${recommended.describe()}\n\nUse them?")
            .setPositiveButton(if (mine != null) "Use mine" else "Use recommended") { _, _ -> applyPreset(mine ?: recommended) }
            .setNegativeButton("Keep current", null)
        if (mine != null) d.setNeutralButton("Recommended") { _, _ -> applyPreset(recommended) }
        d.show()
    }

    private fun pausesCard() = card("Pauses & phrasing") {
        val phr = prefs.getInt("phrasing", 2)
        addView(label("Phrasing"))
        addView(chips(listOf("One sentence" to "1", "Natural" to "2", "Long" to "3"), phr.toString()) { v ->
            prefs.edit().putInt("phrasing", v.toInt()).apply()
            narrator?.phrasing = v.toInt()
            soundChanged(regenerate = true)
            build()
        })
        addView(hintView("How much the voice reads in one go. Longer flows more naturally between sentences but takes a moment longer to start."))
        slider("Between sentences", 100f, 1200f, 50f, prefs.getInt("pause", 400).toFloat(), default = rec().pauseMs.toFloat(),
            format = { "${it.toInt()} ms" },
            hint = "A bit longer after ? and !, and at the end of a paragraph.") { v ->
            prefs.edit().putInt("pause", v.toInt()).apply()
            narrator?.pauseMs = v.toInt()
            soundChanged(regenerate = false)
        }

        lateinit var commaSlider: View
        addView(switch("Pause at commas", prefs.getBoolean("comma_pauses", true),
            "A short breath at commas in long sentences.") { on ->
            prefs.edit().putBoolean("comma_pauses", on).apply()
            narrator?.commaPauses = on
            commaSlider.alpha = if (on) 1f else 0.4f
            soundChanged(regenerate = true)
        })
        commaSlider = slider("Comma pause length", 50f, 600f, 10f, prefs.getInt("comma_pause", 180).toFloat(), default = rec().commaPauseMs.toFloat(),
            format = { "${it.toInt()} ms" }) { v ->
            prefs.edit().putInt("comma_pause", v.toInt()).apply()
            narrator?.commaPauseMs = v.toInt()
            soundChanged(regenerate = false)
        }
        if (!prefs.getBoolean("comma_pauses", true)) commaSlider.alpha = 0.4f

        slider("Trim hesitations", 0f, 300f, 10f, prefs.getInt("max_gap", 90).toFloat(), default = rec().maxGapMs.toFloat(),
            format = { if (it < 40f) "Off" else "up to ${it.toInt()} ms" },
            hint = "Some voices stop between words mid-sentence. This cuts those stops down.") { v ->
            val ms = if (v < 40f) 0 else v.toInt()
            prefs.edit().putInt("max_gap", ms).apply()
            narrator?.maxGapMs = ms
            soundChanged(regenerate = true)
        }
    }

    private fun pronunciationsCard() = card("Pronunciations") {
        val list = Pronunciations.list(this@SettingsActivity, bookId)
        if (list.isEmpty()) {
            addView(hintView("Names the voice gets wrong? Long-press a word in the book and choose Fix pronunciation, or add one here."))
        }
        for (p in list) addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@SettingsActivity).apply {
                text = "${p.word}  →  ${p.sayAs}"; textSize = 15f; setTextColor(th.text)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (p.allBooks) addView(TextView(this@SettingsActivity).apply { text = "all books"; textSize = 11f; setTextColor(th.dim); setPadding(dp(8), 0, dp(8), 0) })
            addView(android.widget.ImageButton(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_play); imageTintList = ColorStateList.valueOf(th.accent)
                background = null; contentDescription = "Hear ${p.word}"
                setOnClickListener { narrator?.preview(p.sayAs) }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            setOnClickListener { Pronunciations.edit(this@SettingsActivity, bookId, p.word, p) { build() } }
        })
        addView(TextView(this@SettingsActivity).apply {
            text = "+  Add a word"; textSize = 14f; setTextColor(th.accent)
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { Pronunciations.edit(this@SettingsActivity, bookId, "") { build() } }
        })
    }

    private var sample: TextView? = null

    private fun appearanceCard() = card("Appearance") {
        // live preview of how the page will look
        addView(TextView(this@SettingsActivity).apply {
            setText(sampleText(), TextView.BufferType.SPANNABLE)
            setTextColor(th.text)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(th.bg); setStroke(dp(1), th.dim and 0x55FFFFFF) }
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            sample = this
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        refreshSample()

        addView(label("Theme"))
        val p = prefs
        // never customized yet? start from the theme in use
        val customBg = if (p.contains("custom_bg")) p.getInt("custom_bg", th.bg) else th.bg
        val customAccent = if (p.contains("custom_accent")) p.getInt("custom_accent", th.accent) else th.accent
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val custom = Themes.derive(customBg, customAccent)
            for (t in Themes.all + custom) addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Aa"; gravity = Gravity.CENTER; textSize = 14f; typeface = Typeface.SERIF
                    setTextColor(if (t.id == "custom") t.accent else t.text)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(t.bg)
                        setStroke(dp(if (t.id == th.id) 3 else 1), if (t.id == th.id) th.accent else th.dim)
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@SettingsActivity).apply {
                    text = t.name; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(th.dim); setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener {
                    if (t.id == "custom") Themes.setCustom(this@SettingsActivity, customBg, customAccent) else Themes.set(this@SettingsActivity, t)
                    rebuildAt(SECTION_APPEARANCE)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        if (th.id == "custom") {
        addView(label("Background"))
        addView(swatches(Themes.backgrounds, th.bg) { picked ->
            Themes.setCustom(this@SettingsActivity, bg = picked, accent = if (th.id == "custom") customAccent else th.accent)
            rebuildAt(SECTION_APPEARANCE)
        })
        val chosenAccent = if (th.id == "custom") customAccent else th.accent
        addView(label("Accent"))
        addView(swatches(Themes.accents, chosenAccent) { picked ->
            Themes.setCustom(this@SettingsActivity, bg = th.bg, accent = picked)
            rebuildAt(SECTION_APPEARANCE)
        })
        if (th.accent != customAccent) {
            addView(hintView("Adjusted slightly so it's readable on this background."))
        }
        }   // custom colors

        lateinit var sizeSlider: View
        sizeSlider = slider("Text size", 13f, 32f, 1f, prefs.getInt("font_size", 19).toFloat(), default = 19f,
            format = { "${it.toInt()} sp" }) { v ->
            // applied on release only; keep the slider where your finger is while the preview above resizes
            prefs.edit().putInt("font_size", v.toInt()).apply()
            keepInPlace(sizeSlider) { refreshSample() }
        }

        val font = prefs.getString("font", "serif")
        addView(label("Font"))
        lateinit var fontRow: View
        fontRow = chips(listOf("Serif" to "serif", "Sans" to "sans"), font) { v ->
            prefs.edit().putString("font", v).apply(); keepInPlace(fontRow) { refreshSample() }
        }
        addView(fontRow)

        val spacing = prefs.getFloat("spacing", 1.4f)
        addView(label("Line spacing"))
        lateinit var spacingRow: View
        spacingRow = chips(listOf("Compact" to "1.2", "Normal" to "1.4", "Relaxed" to "1.65"), spacing.toString()) { v ->
            prefs.edit().putFloat("spacing", v.toFloat()).apply(); keepInPlace(spacingRow) { refreshSample() }
        }
        addView(spacingRow)

        lateinit var justifyRow: View
        justifyRow = switch("Justify text", prefs.getBoolean("justify", true), null) { on ->
            prefs.edit().putBoolean("justify", on).apply(); keepInPlace(justifyRow) { refreshSample() }
        }
        addView(justifyRow)
    }

    /** Runs [change] and scrolls so [anchor] stays at the same spot on screen afterwards. */
    private fun keepInPlace(anchor: View, change: () -> Unit) {
        val sv = scrollView ?: return change()
        val before = IntArray(2).also { anchor.getLocationOnScreen(it) }[1]
        change()
        anchor.post {
            val after = IntArray(2).also { anchor.getLocationOnScreen(it) }[1]
            sv.scrollBy(0, after - before)
        }
    }

    private fun rebuildAt(@Suppress("UNUSED_PARAMETER") section: String) {
        build()
    }

    private fun sampleText(): CharSequence {
        val heading = "Chapter 1: A Quiet Morning"
        val body = "The morning was cool and still, and the town had not quite woken up. She opened the window " +
            "and listened to the birds for a while, in no hurry at all. Would today be different? She made a cup " +
            "of coffee, picked up her book and settled into the old armchair by the window."
        val sb = android.text.SpannableStringBuilder(heading).append("\n\n").append(body)
        val h = heading.length
        val flag = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        sb.setSpan(android.text.style.RelativeSizeSpan(1.35f), 0, h, flag)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, h, flag)
        sb.setSpan(android.text.style.ForegroundColorSpan(th.heading), 0, h, flag)
        sb.setSpan(android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER), 0, h + 1, android.text.Spanned.SPAN_PARAGRAPH)
        val it0 = sb.indexOf("in no hurry at all")
        sb.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), it0, it0 + "in no hurry at all".length, flag)
        val q = sb.indexOf("Would today be different?")
        sb.setSpan(android.text.style.BackgroundColorSpan(th.highlight), q, q + "Would today be different?".length, flag)
        return sb
    }

    private fun refreshSample(size: Float? = null) {
        val tv = sample ?: return
        tv.textSize = size ?: prefs.getInt("font_size", 19).toFloat()
        tv.typeface = if (prefs.getString("font", "serif") == "sans") Typeface.SANS_SERIF else Typeface.SERIF
        tv.setLineSpacing(0f, prefs.getFloat("spacing", 1.4f))
        tv.justificationMode = if (prefs.getBoolean("justify", true))
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
    }

    /** Color dots wrapped onto as many rows as needed (no hidden scrolling) + a "+" for the full picker. */
    private fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): View {
        val all = if (selected in colors) colors else listOf(selected) + colors
        val size = dp(36); val gap = dp(10)
        // card padding (18dp each side) + page padding (14dp each side)
        val usable = resources.displayMetrics.widthPixels - dp(2 * 18 + 2 * 14)
        val perRow = maxOf(4, (usable + gap) / (size + gap))
        val cells: List<View> = all.map { c ->
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(if (c == selected) 3 else 1), if (c == selected) th.accent else th.dim)
                }
                contentDescription = String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener { onPick(c) }
            }
        } + TextView(this).apply {
            text = "+"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(th.text)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), th.dim) }
            contentDescription = "Pick any color"
            setOnClickListener { pickColor(selected, onPick) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
            cells.chunked(perRow).forEach { rowCells ->
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowCells.forEach { addView(it, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }) }
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = gap })
            }
        }
    }

    /** Hue / saturation / brightness picker with live preview and hex input. */
    private fun pickColor(initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initial, hsv)
        val preview = View(this)
        val hex = android.widget.EditText(this).apply {
            setSingleLine(); textSize = 15f; setTextColor(th.text); gravity = Gravity.CENTER
            backgroundTintList = ColorStateList.valueOf(th.accent); highlightColor = th.selection
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        }
        var updatingHex = false
        fun current() = android.graphics.Color.HSVToColor(hsv)
        fun paint() {
            preview.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(current()); setStroke(dp(1), th.dim) }
            updatingHex = true
            hex.setText(String.format("#%06X", current() and 0xFFFFFF))
            updatingHex = false
        }
        fun bar(max: Int, value: Int, bgColors: IntArray?, onMove: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max; progress = value
            thumbTintList = ColorStateList.valueOf(th.text)
            if (bgColors != null) {
                progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, bgColors).apply { cornerRadius = dp(6).toFloat() }
                minimumHeight = dp(12)
            } else {
                progressTintList = ColorStateList.valueOf(th.accent)
                progressBackgroundTintList = ColorStateList.valueOf(th.dim)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { onMove(p); paint() } }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val hues = IntArray(7) { android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        val hueBar = bar(360, hsv[0].toInt(), hues) { hsv[0] = it.toFloat() }
        val satBar = bar(100, (hsv[1] * 100).toInt(), null) { hsv[1] = it / 100f }
        val valBar = bar(100, (hsv[2] * 100).toInt(), null) { hsv[2] = it / 100f }
        hex.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (updatingHex) return
                val t = e.toString().trim().removePrefix("#")
                if (t.length == 6) runCatching { android.graphics.Color.parseColor("#$t") }.getOrNull()?.let { c ->
                    android.graphics.Color.colorToHSV(c, hsv)
                    hueBar.progress = hsv[0].toInt(); satBar.progress = (hsv[1] * 100).toInt(); valBar.progress = (hsv[2] * 100).toInt()
                    preview.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(c); setStroke(dp(1), th.dim) }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        paint()
        fun cap(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(th.dim); setPadding(0, dp(12), 0, dp(4)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(12), dp(22), dp(4))
            addView(preview, LinearLayout.LayoutParams(-1, dp(64)))
            addView(hex, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(cap("Hue")); addView(hueBar, LinearLayout.LayoutParams(-1, dp(28)))
            addView(cap("Saturation")); addView(satBar)
            addView(cap("Brightness")); addView(valBar)
        }
        Themes.dialog(this).setTitle("Pick a color").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Use color") { _, _ -> onPick(current()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun aboutCard() = card("About") {
        addView(TextView(this@SettingsActivity).apply {
            text = "Send debug log"; textSize = 14f; setTextColor(th.accent)
            setPadding(0, dp(4), 0, dp(10))
            setOnClickListener { AppLog.share(this@SettingsActivity) }
        })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        addView(TextView(this@SettingsActivity).apply {
            text = "bagueration $version\nWorks offline. Voices by Kokoro (built in) and Piper (downloads), running on sherpa-onnx."
            textSize = 13f; setTextColor(th.dim); setLineSpacing(0f, 1.3f)
        })
    }

    /** Back to defaults for every slider and option (keeps your voice, theme and library). */
    private fun confirmResetAll() {
        Themes.dialog(this).setTitle("Reset settings?")
            .setMessage("Speed and pauses go back to the recommended settings for your voice, and text settings to how they started. Your voice, colors and books stay as they are.")
            .setPositiveButton("Reset") { _, _ ->
                prefs.edit().apply { for (k in listOf("font_size", "font", "spacing", "justify")) remove(k) }.apply()
                rec().apply(this, narrator, currentVoice())
                soundChanged(regenerate = true)
                build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val bookId get() = intent.getStringExtra(EXTRA_BOOK_ID)
    private val bookLang get() = intent.getStringExtra(EXTRA_BOOK_LANG)
    private fun currentVoice() = Voices.byId(narrator?.voiceId) ?: Voices.resolve(this, bookId, bookLang)

    private fun selectVoice(v: VoiceInfo) {
        val changed = narrator?.voiceId != v.id
        Voices.remember(this, bookId, v)
        narrator?.voiceId = v.id
        narrator?.applyToPlaying()   // no-op unless the book is reading
        if (changed) window.decorView.post { offerPreset(v) }
    }

    /**
     * Voice list: the book's detected language first, then every other language (so you can still
     * pick a "wrong" one). Built-in voices preview instantly; others show their download size.
     */
    private fun pickVoice() {
        val current = currentVoice()
        val detected = bookLang
        lateinit var dialog: android.app.AlertDialog
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }

        // one preview at a time; the playing row shows a pause button
        val previewButtons = HashMap<String, android.widget.ImageButton>()
        var previewingId: String? = null
        fun refreshButtons() {
            val playing = if (narrator?.isPreviewing == true) previewingId else null
            for ((id, b) in previewButtons) {
                val v = Voices.byId(id) ?: continue
                b.setImageResource(when {
                    id == playing -> R.drawable.ic_pause
                    Voices.isInstalled(this, v) -> R.drawable.ic_play
                    else -> R.drawable.ic_download
                })
            }
        }
        val watcher = object : Runnable {
            override fun run() {
                refreshButtons()
                if (narrator?.isPreviewing == true) list.postDelayed(this, 300) else previewingId = null
            }
        }
        fun togglePreview(v: VoiceInfo) {
            if (previewingId == v.id && narrator?.isPreviewing == true) {
                narrator?.stop(); previewingId = null
            } else {
                narrator?.preview(Voices.sample(v.lang), v.id)   // stops any other preview first
                previewingId = v.id
                if (narrator?.loadingEngine == true || Voices.byId(narrator?.voiceId)?.lang != v.lang) {
                    Toast.makeText(this, "Loading ${Voices.LANGUAGES[v.lang]} voice…", Toast.LENGTH_SHORT).show()
                }
                list.removeCallbacks(watcher)
                list.postDelayed(watcher, 300)
            }
            refreshButtons()
        }

        fun header(text: String, accent: Boolean = true) = TextView(this).apply {
            this.text = text.uppercase(); textSize = 11f; letterSpacing = 0.1f
            setTextColor(if (accent) th.accent else th.dim); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(16), dp(12), dp(4))
        }

        fun voiceRow(v: VoiceInfo): View = LinearLayout(this).apply {
            val installed = Voices.isInstalled(this@SettingsActivity, v)
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            if (v.id == current.id) background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(th.highlight) }
            addView(TextView(this@SettingsActivity).apply {
                text = if (v.id == current.id) "●" else "○"; textSize = 14f; setTextColor(th.accent); setPadding(0, 0, dp(12), 0)
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = v.name; textSize = 16f; setTextColor(th.text) })
                addView(TextView(this@SettingsActivity).apply {
                    text = listOf(v.note, if (!installed) "${v.downloadMb} MB download" else if (!v.builtIn) "downloaded" else "")
                        .filter { it.isNotEmpty() }.joinToString("  ·  ")
                    textSize = 12f; setTextColor(th.dim)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ImageButton(this@SettingsActivity).apply {
                previewButtons[v.id] = this
                setImageResource(if (installed) R.drawable.ic_play else R.drawable.ic_download)
                imageTintList = ColorStateList.valueOf(th.accent)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), th.accent) }
                contentDescription = if (installed) "Preview ${v.name}" else "Download ${v.name}"
                setOnClickListener {
                    if (installed) togglePreview(v)
                    else VoiceDownloader.withDialog(this@SettingsActivity, v) {
                        dialog.dismiss(); pickVoice(); narrator?.preview(Voices.sample(v.lang), v.id)
                    }
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            setOnClickListener {
                if (installed) { selectVoice(v); dialog.dismiss() }
                else VoiceDownloader.withDialog(this@SettingsActivity, v) { selectVoice(v); dialog.dismiss() }
            }
            if (!v.builtIn && installed) setOnLongClickListener {
                Themes.dialog(this@SettingsActivity).setTitle("Delete ${v.name}?")
                    .setMessage("Frees about ${v.downloadMb} MB. You can download it again anytime.")
                    .setPositiveButton("Delete") { _, _ ->
                        if (narrator?.voiceId == v.id) narrator?.voiceId = Voices.resolve(this@SettingsActivity, null, null).id
                        Voices.delete(this@SettingsActivity, v); dialog.dismiss(); pickVoice()
                    }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }

        // detected language first
        if (detected != null) {
            val name = Voices.LANGUAGES[detected] ?: detected
            val voices = Voices.all.filter { it.lang == detected }
            list.addView(TextView(this).apply {
                text = "This book is in $name"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(th.text)
                setPadding(dp(12), dp(6), dp(12), 0)
            })
            if (voices.isEmpty()) {
                list.addView(hintView("There's no $name voice yet — pick any voice below.").apply { setPadding(dp(12), dp(4), dp(12), 0) })
            } else if (voices.none { Voices.isInstalled(this, it) } ||
                (detected == "pt" && voices.none { !it.builtIn && Voices.isInstalled(this, it) })) {
                // suggest downloading a voice in the book's language (for Portuguese: a Brazilian-trained one)
                val first = voices.first { !it.builtIn }
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(th.highlight) }
                    addView(TextView(this@SettingsActivity).apply {
                        text = if (voices.any { Voices.isInstalled(this@SettingsActivity, it) })
                            "The built-in voices can sound European. For a Brazilian accent, get this one:"
                        else "You don't have a $name voice yet."
                        textSize = 13f; setTextColor(th.text)
                    })
                    addView(TextView(this@SettingsActivity).apply {
                        text = "⬇  Download ${first.name} · ${first.downloadMb} MB"
                        textSize = 14f; setTextColor(th.onAccent); gravity = Gravity.CENTER
                        setPadding(dp(14), dp(9), dp(14), dp(9)); background = pill(th.accent)
                        setOnClickListener {
                            VoiceDownloader.withDialog(this@SettingsActivity, first) {
                                selectVoice(first); dialog.dismiss(); narrator?.preview(Voices.sample(first.lang), first.id)
                            }
                        }
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
                }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(10), dp(4), dp(4)) })
            }
            voices.forEach { list.addView(voiceRow(it)) }
        }
        // then everything else, grouped by language
        for ((lang, name) in Voices.LANGUAGES) {
            if (lang == detected) continue
            val voices = Voices.all.filter { it.lang == lang }
            if (voices.isEmpty()) continue
            val builtIn = voices.any { it.builtIn }
            list.addView(header(if (builtIn) name else "$name  ·  download", accent = builtIn))
            voices.forEach { list.addView(voiceRow(it)) }
        }
        list.addView(hintView("Hold a downloaded voice to delete it.").apply { setPadding(dp(12), dp(14), dp(12), 0) })

        dialog = Themes.dialog(this).setTitle("Choose a voice")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            if (narrator?.isPreviewing == true) narrator?.stop()
            intent.removeExtra(EXTRA_SECTION)
            build()
        }
        dialog.show()
    }

    // ---------- building blocks ----------

    private fun card(title: String, content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(th.bar) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        addView(TextView(this@SettingsActivity).apply {
            text = title.uppercase(); textSize = 12f; letterSpacing = 0.12f
            setTextColor(th.accent); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        content()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(th.text); setPadding(0, dp(10), 0, dp(6))
    }

    private fun hintView(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(th.dim); setLineSpacing(0f, 1.2f); setPadding(0, 0, 0, dp(6))
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color) }

    private fun row(title: String, value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(this@SettingsActivity).apply { text = title; textSize = 15f; setTextColor(th.text) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@SettingsActivity).apply { text = "$value  ›"; textSize = 14f; setTextColor(th.accent) })
        setOnClickListener { onClick() }
    }

    /** Labeled slider with live value; [onChange] fires on release. */
    private fun LinearLayout.slider(
        title: String, min: Float, max: Float, step: Float, value: Float, default: Float,
        format: (Float) -> String, hint: String? = null, live: ((Float) -> Unit)? = null, onChange: (Float) -> Unit,
    ): View {
        val box = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        val valueView = TextView(this@SettingsActivity).apply { textSize = 14f; setTextColor(th.accent); typeface = Typeface.DEFAULT_BOLD }
        box.addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = title; textSize = 15f; setTextColor(th.text) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(valueView)
        })
        val steps = ((max - min) / step).toInt()
        fun at(p: Int) = min + p * step
        valueView.text = format(value)
        fun idx(v: Float) = Math.round((v - min) / step).coerceIn(0, steps)
        val reset = TextView(this@SettingsActivity).apply {
            text = "↺ Reset"; textSize = 13f; setTextColor(th.accent); setPadding(dp(12), dp(4), 0, dp(4))
        }
        val bar = SeekBar(this@SettingsActivity).apply {
            this.max = steps
            progress = idx(value)
            progressTintList = ColorStateList.valueOf(th.accent)
            thumbTintList = ColorStateList.valueOf(th.accent)
            progressBackgroundTintList = ColorStateList.valueOf(th.dim)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueView.text = format(at(p))
                    live?.invoke(at(p))
                    reset.visibility = if (p == idx(default)) View.INVISIBLE else View.VISIBLE
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = onChange(at(sb.progress))
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        reset.visibility = if (idx(value) == idx(default)) View.INVISIBLE else View.VISIBLE
        reset.setOnClickListener { bar.progress = idx(default); onChange(default) }
        box.addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = "Default: ${format(default)}"; textSize = 12f; setTextColor(th.dim) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(reset)
        })
        hint?.let { box.addView(hintView(it)) }
        addView(box)
        return box
    }

    private fun switch(title: String, on: Boolean, hint: String?, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4))
            addView(Switch(this@SettingsActivity).apply {
                text = title; textSize = 15f; setTextColor(th.text); isChecked = on
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.accent, th.dim))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.highlight, th.bg))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
            hint?.let { addView(hintView(it)) }
        }

    private fun chips(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val views = ArrayList<TextView>()
            fun paint(sel: String) = views.forEach { v ->
                val on = v.tag == sel
                v.setTextColor(if (on) th.onAccent else th.text)
                v.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    if (on) setColor(th.accent) else setStroke(dp(1), th.dim)
                }
            }
            for ((name, value) in options) addView(TextView(this@SettingsActivity).apply {
                text = name; tag = value; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                if (value == "serif") typeface = Typeface.SERIF
                setOnClickListener { onPick(value); paint(value) }
                views += this
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            paint(selected ?: "")
        }
}
