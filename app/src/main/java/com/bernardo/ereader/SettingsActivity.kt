package com.bernardo.ereader

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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

        // layout, in dp: page margin, card padding, and how far rows reach into the card padding
        private const val PAGE_PAD = 16
        private const val CARD_PAD = Ui.CARD_PAD
        private const val ROW_INSET = Ui.ROW_INSET
    }

    private val prefs by lazy { BookStore.prefs(this) }
    private lateinit var th: ReaderTheme
    private lateinit var ui: Ui
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private val narrator get() = Narrator.instance
    private var appearanceCard: View? = null
    private var previewButton: TextView? = null
    private val previewWatch = object : Runnable {
        override fun run() {
            if (narrator?.isPreviewing == true) { showPreviewState(true); previewButton?.postDelayed(this, 300) } else showPreviewState(false)
        }
    }

    /** Bouncing dots on the sample button while the voice is still being prepared. */
    private var sampleDots: BouncingDots? = null
    private var sampleState = ""

    /** The sample button reads "Play sample" or "Stop" (with the dots while loading), with the matching icon. */
    private fun showPreviewState(playing: Boolean) {
        val b = previewButton ?: return
        val loading = playing && narrator?.previewLoading == true
        val state = if (loading) "loading" else if (playing) "stop" else "play"
        if (state == sampleState) return
        sampleState = state
        b.text = if (playing) "Stop" else "Play sample"
        if (loading) {
            val dots = sampleDots ?: BouncingDots(th.onAccent, d).also { sampleDots = it }
            dots.setBounds(0, 0, dp(18), dp(18))
            b.setCompoundDrawablesRelative(dots, null, null, null)
            b.compoundDrawablePadding = dp(8)
            dots.start()
        } else {
            sampleDots?.stop()
            Icons.leading(b, if (playing) R.drawable.ic_stop else R.drawable.ic_play, th.onAccent)
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
        showPreviewState(true)
        previewButton?.removeCallbacks(previewWatch)
        previewButton?.postDelayed(previewWatch, 300)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        build()
    }

    private var scrollView: ScrollView? = null
    private var holder: android.widget.FrameLayout? = null
    private var topBar: Ui.TopBar? = null
    private var bars = Ui.Bars(0, 0, 0, 0)

    /** Top bar below the status bar, the list's end above the navigation bar. */
    private fun padForBars() {
        topBar?.view?.setPadding(bars.left, bars.top, bars.right, 0)
        scrollView?.setPadding(bars.left, 0, bars.right, bars.bottom)
    }

    private fun build() {
        val keepY = scrollView?.scrollY
        th = Themes.current(this)
        ui = Ui(this, th).apply { onTouched = { touched = it } }
        Themes.applyChrome(this, th)
        val bar = ui.topBar("Settings", nav = Ui.Action(R.drawable.ic_chevron_left, "Back") { finish() })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(PAGE_PAD), dp(4), dp(PAGE_PAD), dp(32)) }
        col.addView(voiceCard())
        col.addView(presetCard().also { presetCardView = it })
        col.addView(pausesCard())
        if (bookId != null) col.addView(card("Prepared audio", R.drawable.ic_headphones) {
            val all = Renders.list(this@SettingsActivity, bookId!!)
            val n = all.count { it.ready }
            val preparing = if (RenderManager.current?.render?.bookId == bookId) " · 1 preparing" else ""
            addView(row("Prepared audio for this book", (if (all.isEmpty()) "None yet" else "$n ready") + preparing) {
                startActivity(android.content.Intent(this@SettingsActivity, RendersActivity::class.java).putExtra(RendersActivity.EXTRA_BOOK, bookId))
            })
            addView(hintView("Prepare chapters ahead of time: they play with no loading, even on a warm phone."))
        })
        if (bookId != null) col.addView(pronunciationsCard())
        col.addView(appearanceCard().also { appearanceCard = it })
        col.addView(aboutCard())
        col.addView(button("Reset to defaults", R.drawable.ic_reset, filled = false) { confirmResetAll() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20) })
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(col) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(th.bg)
            addView(bar.view)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val old = (scrollView?.parent as? View)
        scrollView = scroll
        topBar = bar
        val root = holder ?: android.widget.FrameLayout(this).also { holder = it; setContentView(it) }
        root.setBackgroundColor(th.bg)
        if (old == null) {
            root.addView(screen)
            Ui.onInsets(root) { bars = it; padForBars() }
        } else {
            // fade the rebuilt screen in over the old one instead of a hard swap (no flash)
            screen.alpha = 0f
            root.addView(screen)
            screen.animate().alpha(1f).setDuration(160).withEndAction { root.removeView(old) }.start()
        }
        padForBars()
        if (keepY != null) {
            scroll.post { scroll.scrollTo(0, keepY) }   // rebuilt after a change: stay where you were
        } else if (intent.getStringExtra(EXTRA_SECTION) == SECTION_APPEARANCE) {
            scroll.post { appearanceCard?.let { scroll.scrollTo(0, it.top - dp(8)) } }
        }
    }

    override fun onDestroy() {
        if (narrator?.isPreviewing == true) narrator?.stop()
        super.onDestroy()
    }

    // ---------- sections ----------

    private fun voiceCard() = card("Voice", R.drawable.ic_voice) {
        val v = currentVoice()
        addView(row("Voice", "${v.name}  ·  ${Voices.LANGUAGES[v.lang]}") { pickVoice() })
        bookLang?.let { lang ->
            val hasVoice = Voices.defaultFor(this@SettingsActivity, lang) != null
            addView(hintView("This book is in ${Voices.LANGUAGES[lang] ?: lang}." +
                if (!hasVoice) " You don't have a voice for it yet. Tap Voice to get one." else ""))
        }
        val reading = narrator?.let { it.isPlaying && !it.isPreviewing } == true
        addView(button("Play sample", R.drawable.ic_play, filled = true) {
            if (narrator?.isPreviewing == true) { narrator?.stop(); showPreviewState(false) } else playPreview()
        }.also {
            previewButton = it
            showPreviewState(narrator?.isPreviewing == true)
            if (narrator?.isPreviewing == true) it.postDelayed(previewWatch, 300)   // rebuilt mid-sample: keep watching
        },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
        addView(hintView(if (reading) "Changes apply to the book as it reads." else "The sample replays when you change speed or pauses."))

        addView(divider())
        slider("Speed", 0.5f, 2.0f, 0.05f, prefs.getFloat("speed", 1.0f), default = rec().speed,
            format = { "${"%.2f".format(Locale.US, it).trimEnd('0').trimEnd('.')}×" }) { speed ->
            prefs.edit().putFloat("speed", speed).apply()
            narrator?.speed = speed
            soundChanged(regenerate = true)
            refreshPresets()
        }
        Voices.blendPartner(v)?.let { partner ->
            addView(label("Blend with ${partner.name}"))
            addView(chips(listOf("Off" to "0", "Light" to "1", "Half" to "2"), Voices.blendLevel(this@SettingsActivity, v).toString()) { lvl ->
                Voices.setBlendLevel(this@SettingsActivity, v, lvl.toInt())
                soundChanged(regenerate = true)
                refreshPresets()
            })
            addView(hintView("Mixes in some of ${partner.name}, the steadiest ${v.note.substringBefore(" ·")} voice. Smooths out hesitations while keeping ${v.name}'s character."))
        }
    }

    /** Recommended settings for the voice in use ("Default" on the sliders). */
    private fun rec() = Presets.recommended(currentVoice())

    /**
     * Presets for the voice in use, as a choice list: its recommended settings, the ones you saved for it,
     * and (when the sliders match neither) your unsaved changes, with a button to keep those as yours.
     */
    private fun presetCard() = card("Presets for ${currentVoice().name}", R.drawable.ic_graphic_eq) {
        val v = currentVoice()
        val recommended = Presets.recommended(v)
        val mine = Presets.custom(this@SettingsActivity, v)
        val now = VoicePreset.current(this@SettingsActivity, v)
        val selected = when (now) { recommended -> 0; mine -> 1; else -> 2 }
        addView(hintView("Speed, pauses and blend together. Pick one, or adjust the sliders and save the result as yours."))

        val list = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        list.addView(choiceRow("Recommended", recommended.describe(), selected == 0) { applyPreset(recommended) })
        list.addView(if (mine != null) choiceRow("Yours", mine.describe(), selected == 1) { applyPreset(mine) }.apply {
            setOnLongClickListener { confirmForgetPreset(v); true }
        } else choiceRow("Yours", "Not saved yet.", false, enabled = false) {})
        if (selected == 2) list.addView(choiceRow("Unsaved changes", now.describe(), true) {})
        addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); marginStart = -dp(ROW_INSET); marginEnd = -dp(ROW_INSET) })

        if (selected == 2) addView(button(if (mine == null) "Save as mine" else "Replace mine with these", R.drawable.ic_save, filled = false) {
            Presets.saveCustom(this@SettingsActivity, v, now)
            Toast.makeText(this@SettingsActivity, "Saved as your settings for ${v.name}", Toast.LENGTH_SHORT).show()
            build()
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
        if (mine != null) addView(hintView("Hold Yours to forget it."))
    }

    private var presetCardView: View? = null
    /** The control just changed, kept still on screen when the preset card above it changes height. */
    private var touched: View? = null

    /** Redraw just the preset card after a sound setting changed (no full rebuild, so nothing flickers). */
    private fun refreshPresets() {
        val old = presetCardView ?: return
        val parent = old.parent as? LinearLayout ?: return
        val swap = {
            val i = parent.indexOfChild(old)
            val fresh = presetCard().also { presetCardView = it }
            parent.removeViewAt(i)
            parent.addView(fresh, i)
        }
        touched?.takeIf { it.isAttachedToWindow }?.let { keepInPlace(it, swap) } ?: swap()
    }

    private fun confirmForgetPreset(v: VoiceInfo) {
        Themes.dialog(this).setTitle("Forget your settings for ${v.name}?")
            .setMessage("The sliders stay as they are now; only the saved preset goes.")
            .setPositiveButton("Forget") { _, _ -> Presets.deleteCustom(this, v); build() }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun pausesCard() = card("Pauses & phrasing", R.drawable.ic_timer) {
        val phr = prefs.getInt("phrasing", 2)
        addView(label("Phrasing"))
        addView(chips(listOf("One sentence" to "1", "Natural" to "2", "Long" to "3"), phr.toString()) { v ->
            prefs.edit().putInt("phrasing", v.toInt()).apply()
            narrator?.phrasing = v.toInt()
            soundChanged(regenerate = true)
            build()
        })
        addView(hintView("How much the voice reads in one go when \"Pause at commas\" is off. With it on, every sentence gets its own exact pause instead."))
        slider("Between sentences", 100f, 1200f, 50f, prefs.getInt("pause", 400).toFloat(), default = rec().pauseMs.toFloat(),
            format = { "${it.toInt()} ms" },
            hint = "A bit longer after ? and !, and at the end of a paragraph.") { v ->
            prefs.edit().putInt("pause", v.toInt()).apply()
            narrator?.pauseMs = v.toInt()
            soundChanged(regenerate = false)
            refreshPresets()
        }

        lateinit var commaSlider: View
        addView(switch("Pause at commas", prefs.getBoolean("comma_pauses", true),
            "Exact pauses at commas, dashes, \"...\" and between sentences. Off: the voice keeps its own timing, which often runs straight through them.") { on ->
            prefs.edit().putBoolean("comma_pauses", on).apply()
            narrator?.commaPauses = on
            commaSlider.alpha = if (on) 1f else 0.4f
            soundChanged(regenerate = true)
            refreshPresets()
        })
        commaSlider = slider("Comma pause length", 50f, 600f, 10f, prefs.getInt("comma_pause", 180).toFloat(), default = rec().commaPauseMs.toFloat(),
            format = { "${it.toInt()} ms" }) { v ->
            prefs.edit().putInt("comma_pause", v.toInt()).apply()
            narrator?.commaPauseMs = v.toInt()
            soundChanged(regenerate = false)
            refreshPresets()
        }
        if (!prefs.getBoolean("comma_pauses", true)) commaSlider.alpha = 0.4f

        slider("Trim hesitations", 0f, 300f, 10f, prefs.getInt("max_gap", 90).toFloat(), default = rec().maxGapMs.toFloat(),
            format = { if (it < 40f) "Off" else "up to ${it.toInt()} ms" },
            hint = "Some voices stop between words mid-sentence. This cuts those stops down.") { v ->
            val ms = if (v < 40f) 0 else v.toInt()
            prefs.edit().putInt("max_gap", ms).apply()
            narrator?.maxGapMs = ms
            soundChanged(regenerate = true)
            refreshPresets()
        }
    }

    private fun pronunciationsCard() = card("Pronunciations", R.drawable.ic_spellcheck) {
        val list = Pronunciations.list(this@SettingsActivity, bookId)
        if (list.isEmpty()) {
            addView(hintView("Names the voice gets wrong? Long-press a word in the book and choose Fix pronunciation, or add one here."))
        }
        for (p in list) addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ROW_INSET), dp(8), dp(4), dp(8))
            background = pressable()
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = p.word; textSize = 15f; setTextColor(th.text) })
                addView(TextView(this@SettingsActivity).apply {
                    text = "Says \u201C${p.sayAs}\u201D" + if (p.allBooks) "  ·  all books" else ""
                    textSize = 13f; setTextColor(th.dim)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_play, "Hear ${p.word}") { narrator?.preview(p.sayAs) })
            setOnClickListener { Pronunciations.edit(this@SettingsActivity, bookId, p.word, p) { build() } }
        }, insetParams())
        addView(button("Add a word", R.drawable.ic_add, filled = false) {
            Pronunciations.edit(this@SettingsActivity, bookId, "") { build() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
    }

    private var sample: TextView? = null

    private fun appearanceCard() = card("Appearance", R.drawable.ic_palette) {
        // live preview of how the page will look
        addView(PageView(this@SettingsActivity, null).apply {
            setText(sampleText(), TextView.BufferType.SPANNABLE)
            setTextColor(th.text)
            setPadding(dp(18), dp(16), dp(18), dp(16))
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
        val themeRow = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val custom = Themes.derive(customBg, customAccent)
            for (t in Themes.all + custom) addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = pressable()
                contentDescription = t.name + if (t.id == th.id) ", selected" else ""
                addView(TextView(this@SettingsActivity).apply {
                    text = "Aa"; gravity = Gravity.CENTER; textSize = 15f; typeface = Ui.bookFont(this@SettingsActivity)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setTextColor(if (t.id == "custom") t.accent else t.text)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(t.bg)
                        setStroke(dp(if (t.id == th.id) 3 else 1), if (t.id == th.id) th.accent else th.dim)
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@SettingsActivity).apply {
                    text = t.name; textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(if (t.id == th.id) th.text else th.dim)
                    setPadding(0, dp(4), 0, 0); maxLines = 1
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                setOnClickListener {
                    if (t.id == "custom") Themes.setCustom(this@SettingsActivity, customBg, customAccent) else Themes.set(this@SettingsActivity, t)
                    rebuildAt(SECTION_APPEARANCE)
                }
            }, LinearLayout.LayoutParams(dp(64), -2).apply { marginEnd = dp(2) })
        }
        // seven presets and Custom no longer fit side by side: a row that scrolls, kept on the selected one
        addView(android.widget.HorizontalScrollView(this@SettingsActivity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            addView(themeRow)
            post {
                // size the circles so that four and a half fit: the half one at the edge says there's more to scroll
                val itemW = (width / 4.5f).toInt()
                for (i in 0 until themeRow.childCount) themeRow.getChildAt(i).layoutParams = LinearLayout.LayoutParams(itemW, -2)
                themeRow.requestLayout()
                post {
                    val sel = Themes.all.indexOfFirst { it.id == th.id }.let { if (it < 0) Themes.all.size else it }
                    themeRow.getChildAt(sel)?.let { v -> scrollTo((v.left - width / 2 + v.width / 2).coerceAtLeast(0), 0) }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6); marginStart = -dp(ROW_INSET); marginEnd = -dp(ROW_INSET) })

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

        val font = prefs.getString("font", "literata")
        addView(label("Font"))
        lateinit var fontRow: View
        fontRow = ui.chips(listOf("Literata" to "literata", "Serif" to "serif", "Sans" to "sans"), font,
            fonts = listOf("literata", "serif", "sans").associateWith { Ui.bookFont(this@SettingsActivity, it) }) { v ->
            prefs.edit().putString("font", v).apply(); keepInPlace(fontRow) { refreshSample() }
        }
        addView(fontRow)

        addView(label("Margins"))
        lateinit var marginRow: View
        marginRow = chips(listOf("Narrow" to "16", "Normal" to "24", "Wide" to "32"), prefs.getInt("margins", 24).toString()) { v ->
            prefs.edit().putInt("margins", v.toInt()).apply(); keepInPlace(marginRow) { refreshSample() }
        }
        addView(marginRow)

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
        lateinit var paperRow: View
        paperRow = switch("Paper texture", Themes.paperTexture(this@SettingsActivity), "Lightly crumpled paper behind the page: soft grain and faint creases.") { on ->
            prefs.edit().putBoolean("paper_texture", on).apply(); keepInPlace(paperRow) { refreshSample() }
        }
        addView(paperRow)
        addView(switch("Highlight each word", prefs.getBoolean("word_highlight", true),
            "While reading aloud, the word being spoken lights up inside the sentence.") { on ->
            prefs.edit().putBoolean("word_highlight", on).apply()
        })
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
        sb.setSpan(Mark(th.highlight, 2), q, q + "Would today be different?".length, flag)
        return sb
    }

    private fun refreshSample(size: Float? = null) {
        val tv = sample ?: return
        tv.textSize = size ?: prefs.getInt("font_size", 19).toFloat()
        tv.typeface = Ui.bookFont(this)
        tv.setLineSpacing(0f, prefs.getFloat("spacing", 1.4f))
        val side = dp(prefs.getInt("margins", 24) * 3 / 4)   // the preview is narrower than the page: scaled down
        tv.setPadding(side, dp(16), side, dp(16))
        tv.justificationMode = if (prefs.getBoolean("justify", true))
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
        // the page background (color, or paper), inside the preview's rounded border
        tv.background = android.graphics.drawable.LayerDrawable(arrayOf(Themes.pageBackground(this, th),
            GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), th.outline) }))
        tv.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat())
        }
        tv.clipToOutline = true
    }

    /** Color dots wrapped onto as many rows as needed (no hidden scrolling) + a "+" for the full picker. */
    private fun swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): View {
        val all = if (selected in colors) colors else listOf(selected) + colors
        val size = dp(36); val gap = dp(10)
        val usable = resources.displayMetrics.widthPixels - dp(2 * CARD_PAD + 2 * PAGE_PAD)
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
        } + android.widget.ImageView(this).apply {
            setImageDrawable(Icons.get(this@SettingsActivity, R.drawable.ic_add, th.text, 20f))
            scaleType = android.widget.ImageView.ScaleType.CENTER
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

    private fun aboutCard() = card("About", R.drawable.ic_info) {
        addView(row("Send debug log", "Share it to report a problem") { AppLog.share(this@SettingsActivity) })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        addView(hintView("bagueration $version\nWorks offline. Voices by Kokoro (built in) and Piper (downloads), running on sherpa-onnx.").apply {
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    /** Back to defaults for every slider and option (keeps your voice, theme and library). */
    private fun confirmResetAll() {
        Themes.dialog(this).setTitle("Reset settings?")
            .setMessage("Speed and pauses go back to the recommended settings for your voice, and text settings to how they started. Your voice, colors and books stay as they are.")
            .setPositiveButton("Reset") { _, _ ->
                prefs.edit().apply { for (k in listOf("font_size", "font", "spacing", "justify", "paper_texture", "margins")) remove(k) }.apply()
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
        lateinit var dialog: Ui.Sheet
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // one preview at a time; the playing row shows a pause button
        val previewButtons = HashMap<String, android.widget.ImageButton>()
        var previewingId: String? = null
        // the row whose sample is being prepared shows three bouncing dots, then a stop button once it plays
        val dots = BouncingDots(th.accent, d)
        val buttonState = HashMap<String, String>()
        fun refreshButtons() {
            val playing = if (narrator?.isPreviewing == true) previewingId else null
            val loading = narrator?.previewLoading == true
            for ((id, b) in previewButtons) {
                val v = Voices.byId(id) ?: continue
                val state = when {
                    id == playing && loading -> "loading"
                    id == playing -> "stop"
                    Voices.isInstalled(this, v) -> "play"
                    else -> "download"
                }
                if (buttonState[id] == state) continue
                buttonState[id] = state
                when (state) {
                    "loading" -> { b.setImageDrawable(dots); dots.start() }
                    "stop" -> b.setImageDrawable(Icons.get(this, R.drawable.ic_stop, th.accent, 24f))
                    "play" -> b.setImageDrawable(Icons.get(this, R.drawable.ic_play, th.accent, 24f))
                    else -> b.setImageDrawable(Icons.get(this, R.drawable.ic_download, th.accent, 24f))
                }
            }
            if (playing == null || !loading) dots.stop()
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

        fun header(text: String) = TextView(this).apply {
            this.text = text.uppercase(); textSize = 12f; letterSpacing = 0.1f
            setTextColor(th.accent); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(18), dp(12), dp(6))
        }

        fun voiceRow(v: VoiceInfo): View = LinearLayout(this).apply {
            val installed = Voices.isInstalled(this@SettingsActivity, v)
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
            background = pressable(if (v.id == current.id) selectedFill() else null)
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                val on = v.id == current.id
                setImageDrawable(Icons.get(this@SettingsActivity, if (on) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked,
                    if (on) th.accent else th.dim, 20f))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply { text = v.name; textSize = 15f; setTextColor(th.text) })
                addView(TextView(this@SettingsActivity).apply {
                    text = listOf(v.note, if (!installed) "${v.downloadMb} MB download" else if (!v.builtIn) "downloaded" else "")
                        .filter { it.isNotEmpty() }.joinToString("  ·  ")
                    textSize = 13f; setTextColor(th.dim)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(if (installed) R.drawable.ic_play else R.drawable.ic_download,
                if (installed) "Preview ${v.name}" else "Download ${v.name}", outlined = true) {
                if (installed) togglePreview(v)
                else VoiceDownloader.withDialog(this@SettingsActivity, v) {
                    dialog.dismiss(); pickVoice(); narrator?.preview(Voices.sample(v.lang), v.id)
                }
            }.also { previewButtons[v.id] = it })
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
            list.addView(header("This book is in $name"))
            if (voices.isEmpty()) {
                list.addView(hintView("There's no $name voice yet. Pick any voice below.").apply { setPadding(dp(12), dp(4), dp(12), 0) })
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
                    addView(button("Download ${first.name} · ${first.downloadMb} MB", R.drawable.ic_download, filled = true) {
                        VoiceDownloader.withDialog(this@SettingsActivity, first) {
                            selectVoice(first); dialog.dismiss(); narrator?.preview(Voices.sample(first.lang), first.id)
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
            list.addView(header(if (builtIn) name else "$name  ·  download"))
            voices.forEach { list.addView(voiceRow(it)) }
        }
        list.addView(hintView("Hold a downloaded voice to delete it.").apply { setPadding(dp(12), dp(14), dp(12), 0) })

        dialog = ui.sheet("Choose a voice") { addView(list) }
        dialog.setOnDismissListener {
            if (narrator?.isPreviewing == true) narrator?.stop()
            intent.removeExtra(EXTRA_SECTION)
            build()
        }
        dialog.show()
    }

    // ---------- building blocks ----------
    // The shared vocabulary in Ui; these keep the call sites short.

    private val medium get() = ui.medium
    private fun card(title: String, icon: Int, content: LinearLayout.() -> Unit) = ui.card(title, icon, content = content)
    private fun insetParams() = ui.insetParams()
    private fun divider() = ui.divider()
    private fun label(t: String) = ui.label(t)
    private fun hintView(t: String) = ui.hint(t)
    private fun pressable(fill: Int? = null) = ui.pressable(fill)
    private fun selectedFill() = ui.selectedFill()
    private fun row(title: String, value: String, onClick: () -> Unit) = ui.row(title, value, onClick = onClick)
    private fun choiceRow(title: String, summary: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
        ui.choiceRow(title, summary, selected, enabled, onClick)
    private fun button(text: String, icon: Int?, filled: Boolean, onClick: () -> Unit) = ui.pill(text, icon, filled, onClick = onClick)
    private fun iconButton(icon: Int, description: String, outlined: Boolean = false, onClick: () -> Unit) =
        ui.iconButton(icon, description, outlined, onClick = onClick)
    private fun switch(title: String, on: Boolean, hint: String?, onChange: (Boolean) -> Unit) = ui.switchRow(title, on, hint, onChange)
    private fun chips(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) =
        ui.chips(options, selected, onPick = onPick)

    /** Labeled slider with live value and its default, added to this card; [onChange] fires on release. */
    private fun LinearLayout.slider(
        title: String, min: Float, max: Float, step: Float, value: Float, default: Float,
        format: (Float) -> String, hint: String? = null, live: ((Float) -> Unit)? = null, onChange: (Float) -> Unit,
    ): View = ui.slider(title, min, max, step, value, default, format, hint, live, onChange).also { addView(it) }
}
