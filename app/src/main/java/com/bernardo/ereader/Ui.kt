package com.bernardo.ereader

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The app's one visual vocabulary, shared by every screen: cards, rows, pills, chips, sliders, the top bar and
 * bottom sheets. Everything takes its colors from [th], so a theme change is a rebuild away.
 */
class Ui(val ctx: Context, val th: ReaderTheme) {

    class Action(val icon: Int, val label: String, val onClick: () -> Unit)

    /** System bar sizes, in px. */
    class Bars(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        /** How far rows reach into a card's padding, so their pressed highlight has room around the text. */
        const val ROW_INSET = 10
        const val CARD_PAD = 20

        fun rippleOf(c: Int): ColorStateList = ColorStateList.valueOf((c and 0xFFFFFF) or 0x33000000)

        // ---------- fonts ----------

        private var literata: Typeface? = null

        /** Book font from the "font" setting: Literata (bundled, the default), the system serif or sans. */
        fun bookFont(ctx: Context, id: String? = BookStore.prefs(ctx).getString("font", "literata")): Typeface = when (id) {
            "sans" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            else -> literata(ctx)
        }

        /** Literata, with its real bold and italic (a variable font: one file per slant, weight set by axis). */
        fun literata(ctx: Context): Typeface {
            literata?.let { return it }
            val am = ctx.applicationContext.assets
            val tf = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    fun font(file: String, weight: Int, italic: Boolean) = android.graphics.fonts.Font.Builder(am, "fonts/$file")
                        .setWeight(weight)
                        .setSlant(if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                    val family = android.graphics.fonts.FontFamily.Builder(font("Literata.ttf", 400, false))
                        .addFont(font("Literata.ttf", 700, false))
                        .addFont(font("Literata-Italic.ttf", 400, true))
                        .addFont(font("Literata-Italic.ttf", 700, true))
                        .build()
                    Typeface.CustomFallbackBuilder(family).setSystemFallback("serif").build()
                } else Typeface.createFromAsset(am, "fonts/Literata.ttf")
            }.getOrNull() ?: Typeface.SERIF
            literata = tf
            return tf
        }

        // ---------- window ----------

        /** Draw under the status and navigation bars; views pad themselves with [onInsets]. */
        @Suppress("DEPRECATION")
        fun edgeToEdge(w: android.view.Window) {
            if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
            else w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
            if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        fun edgeToEdge(a: Activity) = edgeToEdge(a.window)

        /** Calls [block] with the system bar sizes now and whenever they change (rotation, full screen). */
        @Suppress("DEPRECATION")
        fun onInsets(v: View, block: (Bars) -> Unit) {
            v.setOnApplyWindowInsetsListener { _, ins ->
                val b = if (Build.VERSION.SDK_INT >= 30) {
                    val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    Bars(i.left, i.top, i.right, i.bottom)
                } else Bars(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                block(b)
                ins
            }
            v.requestApplyInsets()
        }
    }

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dp(v: Float) = (v * density).toInt()
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Told which control a change came from (Settings keeps that control still while the screen around it changes). */
    var onTouched: (View) -> Unit = {}

    fun ripple(c: Int) = rippleOf(c)
    fun tinted(t: Float, base: Int = th.surface) = Themes.mix(base, th.accent, t)
    fun selectedFill() = tinted(0.16f)

    // ---------- surfaces ----------

    fun rounded(color: Int?, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        color?.let { setColor(it) }
        stroke?.let { setStroke(maxOf(1, dp(strokeDp)), it) }
    }

    fun oval(color: Int?, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        color?.let { setColor(it) }
        stroke?.let { setStroke(maxOf(1, dp(strokeDp)), it) }
    }

    /** A card's background: [ReaderTheme.surface] with a hairline; [stroke] marks the selected one. */
    fun surface(stroke: Int? = null) = rounded(th.surface, 20, stroke ?: th.outline, if (stroke != null) 1.5f else 1f)

    /** Background for tappable rows: rounded, with the accent ripple; [fill] marks the selected one. */
    fun pressable(fill: Int? = null, radiusDp: Int = 14): Drawable =
        RippleDrawable(ripple(th.accent), fill?.let { rounded(it, radiusDp) }, rounded(-1, radiusDp))

    // ---------- text ----------

    /** Section header: small caps in the accent color, with an optional icon. */
    fun header(t: String, icon: Int? = null) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.1f
        setTextColor(th.accent); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
        icon?.let { Icons.leading(this, it, th.accent, sizeDp = 18f, gapDp = 10f) }
    }

    fun label(t: String) = TextView(ctx).apply { text = t; textSize = 15f; setTextColor(th.text); setPadding(0, dp(14), 0, dp(8)) }

    fun hint(t: String) = TextView(ctx).apply {
        text = t; textSize = 12.5f; setTextColor(th.dim); setLineSpacing(0f, 1.25f); setPadding(0, dp(2), 0, dp(6))
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(th.outline)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply { topMargin = dp(10); bottomMargin = dp(4) }
    }

    // ---------- cards and rows ----------

    /** A card: optional header with an icon, then [content]. */
    fun card(title: String? = null, icon: Int? = null, stroke: Int? = null, content: LinearLayout.() -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(CARD_PAD), dp(16), dp(CARD_PAD), dp(12))
        clipToPadding = false
        background = surface(stroke)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        if (title != null) addView(header(title, icon).apply { setPadding(0, 0, 0, dp(8)) })
        content()
    }

    /** Full-width row params that reach [ROW_INSET] into the card padding on both sides. */
    fun insetParams() = LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(ROW_INSET); marginEnd = -dp(ROW_INSET) }

    /** A row that opens something: title, its current value underneath, and a chevron. */
    fun row(title: String, value: String?, icon: Int? = null, chevron: Boolean = true, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(ROW_INSET), dp(8), dp(ROW_INSET - 4), dp(8))
        background = pressable()
        icon?.let {
            addView(ImageView(ctx).apply { setImageDrawable(Icons.get(ctx, it, th.dim, 22f)) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(th.text) })
            if (value != null) addView(TextView(ctx).apply { text = value; textSize = 13f; setTextColor(th.dim); setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(ImageView(ctx).apply {
            setImageDrawable(Icons.get(ctx, R.drawable.ic_chevron_right, th.dim, 22f))
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        contentDescription = if (value != null) "$title, $value" else title
        setOnClickListener { onClick() }
        layoutParams = insetParams()
    }

    /** One option in a single-choice list: radio mark, title and a description. */
    fun choiceRow(title: String, summary: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            setPadding(dp(ROW_INSET), dp(10), dp(ROW_INSET), dp(10))
            background = pressable(if (selected) selectedFill() else null)
            addView(ImageView(ctx).apply {
                setImageDrawable(Icons.get(ctx, if (selected) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked,
                    if (selected) th.accent else th.dim, 20f))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14); topMargin = dp(1) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(th.text); if (selected) typeface = medium })
                addView(TextView(ctx).apply {
                    text = summary; textSize = 13f; setTextColor(th.dim); setLineSpacing(0f, 1.2f); setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!enabled) alpha = 0.6f
            if (enabled && !selected) setOnClickListener { onClick() }
            contentDescription = "$title, $summary" + if (selected) ", selected" else ""
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
        }

    /** A row in a menu sheet: icon and label. */
    fun menuRow(a: Action, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = pressable()
        addView(ImageView(ctx).apply { setImageDrawable(Icons.get(ctx, a.icon, th.text, 22f)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(18) })
        addView(TextView(ctx).apply { text = a.label; textSize = 16f; setTextColor(th.text) }, LinearLayout.LayoutParams(0, -2, 1f))
        contentDescription = a.label
        setOnClickListener { onClick() }
    }

    // ---------- buttons ----------

    /**
     * Pill button: filled (the main action) or outlined, with an optional leading icon. A [wide] one fills its row,
     * with the icon inline so it stays centered with the text.
     */
    fun pill(text: String, icon: Int?, filled: Boolean, wide: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        textSize = 14f; typeface = medium; gravity = Gravity.CENTER; maxLines = 1
        val fg = if (filled) th.onAccent else th.accent
        setTextColor(fg)
        minHeight = dp(48)
        setPadding(dp(if (icon != null && !wide) 16 else 20), dp(10), dp(20), dp(10))
        this.text = if (icon != null && wide) Icons.inline(ctx, icon, fg, text) else text
        if (icon != null && !wide) Icons.leading(this, icon, fg, sizeDp = 18f, gapDp = 8f)
        background = RippleDrawable(ripple(if (filled) th.onAccent else th.accent),
            if (filled) rounded(th.accent, 24) else rounded(null, 24, th.accent), rounded(-1, 24))
        contentDescription = text
        setOnClickListener { onClick() }
    }

    /** Small pill on a surface (the player's speed and audio chips). */
    fun chip(text: CharSequence, icon: Int? = null, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; textSize = 13f; typeface = medium; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        setTextColor(th.text)
        minHeight = dp(36)
        setPadding(dp(if (icon != null) 10 else 14), 0, dp(14), 0)
        icon?.let { Icons.leading(this, it, th.accent, sizeDp = 16f, gapDp = 6f) }
        background = RippleDrawable(ripple(th.accent), rounded(th.surface, 18, th.outline), rounded(-1, 18))
        setOnClickListener { onClick() }
    }

    /** Round icon-only button with a 48dp touch target; [tint] defaults to the accent. */
    fun iconButton(icon: Int, description: String, outlined: Boolean = false, tint: Int = th.accent, sizeDp: Int = 48, onClick: () -> Unit) =
        ImageButton(ctx).apply {
            setImageDrawable(Icons.get(ctx, icon, tint, 24f))
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(ripple(tint), if (outlined) oval(null, tint) else null, oval(-1))
            contentDescription = description
            tooltipText = description
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    // ---------- inputs ----------

    /** Segmented choice: equal-width pills, the selected one filled. [fonts] shows an option in its own typeface. */
    fun chips(options: List<Pair<String, String>>, selected: String?, fonts: Map<String, Typeface> = emptyMap(), onPick: (String) -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // chips line up by their box, not their text baseline (a 2-line chip was pushed down and cut off)
            isBaselineAligned = false
            setPadding(0, 0, 0, dp(4))
            val group = this
            val views = ArrayList<TextView>()
            fun paint(sel: String) = views.forEach { v ->
                val on = v.tag == sel
                v.setTextColor(if (on) th.onAccent else th.text)
                v.background = RippleDrawable(ripple(th.accent), if (on) rounded(th.accent, 20) else rounded(null, 20, th.outline), rounded(-1, 20))
                v.isSelected = on
            }
            options.forEachIndexed { i, (name, value) ->
                addView(TextView(ctx).apply {
                    text = name; tag = value; textSize = 14f; gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    // one line: shrink a long label ("One sentence") to fit its share of the row instead of wrapping
                    isSingleLine = true
                    setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    fonts[value]?.let { typeface = it }
                    setOnClickListener { onTouched(group); onPick(value); paint(value) }
                    views += this
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < options.size - 1) marginEnd = dp(8) })
            }
            paint(selected ?: "")
        }

    /** Labeled slider with live value and its default; [onChange] fires on release. Laid out to reach into card padding. */
    fun slider(
        title: String, min: Float, max: Float, step: Float, value: Float, default: Float,
        format: (Float) -> String, hint: String? = null, live: ((Float) -> Unit)? = null, onChange: (Float) -> Unit,
    ): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        val valueView = TextView(ctx).apply { textSize = 14f; setTextColor(th.accent); typeface = Typeface.DEFAULT_BOLD }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ROW_INSET), 0, dp(ROW_INSET), 0)
            addView(TextView(ctx).apply { text = title; textSize = 15f; setTextColor(th.text) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(valueView)
        })
        val steps = ((max - min) / step).toInt()
        fun at(p: Int) = min + p * step
        valueView.text = format(value)
        fun idx(v: Float) = Math.round((v - min) / step).coerceIn(0, steps)
        val reset = TextView(ctx).apply {
            text = "Reset"; textSize = 13f; setTextColor(th.accent); gravity = Gravity.CENTER_VERTICAL
            Icons.leading(this, R.drawable.ic_reset, th.accent, sizeDp = 16f, gapDp = 4f)
            minHeight = dp(36)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = pressable()
            contentDescription = "Reset $title to default"
        }
        val bar = SeekBar(ctx).apply {
            this.max = steps
            progress = idx(value)
            setPadding(dp(ROW_INSET), dp(8), dp(ROW_INSET), dp(8))
            minimumHeight = dp(40)
            progressTintList = ColorStateList.valueOf(th.accent)
            thumbTintList = ColorStateList.valueOf(th.accent)
            progressBackgroundTintList = ColorStateList.valueOf(th.dim)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueView.text = format(at(p))
                    if (Build.VERSION.SDK_INT >= 30) sb.stateDescription = format(at(p))
                    live?.invoke(at(p))
                    reset.visibility = if (p == idx(default)) View.INVISIBLE else View.VISIBLE
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onTouched(box); onChange(at(sb.progress)) }
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        reset.visibility = if (idx(value) == idx(default)) View.INVISIBLE else View.VISIBLE
        reset.setOnClickListener { onTouched(box); bar.progress = idx(default); onChange(default) }
        box.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ROW_INSET), 0, dp(ROW_INSET - 8), 0)
            addView(TextView(ctx).apply { text = "Default: ${format(default)}"; textSize = 12.5f; setTextColor(th.dim) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(reset)
        })
        hint?.let { box.addView(hint(it).apply { setPadding(dp(ROW_INSET), dp(2), dp(ROW_INSET), dp(6)) }) }
        box.layoutParams = insetParams()
        return box
    }

    fun switchRow(title: String, on: Boolean, hint: String?, onChange: (Boolean) -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4))
            val box = this
            addView(Switch(ctx).apply {
                text = title; textSize = 15f; setTextColor(th.text); isChecked = on
                minHeight = dp(48)
                thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.accent, th.dim))
                trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(th.highlight, th.outline))
                setOnCheckedChangeListener { _, v -> onTouched(box); onChange(v) }
            })
            hint?.let { addView(hint(it)) }
        }

    fun progressBar(color: Int, heightDp: Int = 6) = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        val radius = dp(heightDp / 2f).toFloat()
        val track = GradientDrawable().apply { cornerRadius = radius; setColor(th.outline) }
        val fill = ClipDrawable(GradientDrawable().apply { cornerRadius = radius; setColor(color) }, Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf<Drawable>(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
    }

    /** A centered card for "nothing here yet": icon in a soft circle, a title, a line of help and one action. */
    fun emptyState(icon: Int, title: String, body: String, action: String?, actionIcon: Int? = R.drawable.ic_add, onAction: () -> Unit = {}) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            background = surface()
            addView(ImageView(ctx).apply {
                setImageDrawable(Icons.get(ctx, icon, th.accent, 36f))
                scaleType = ImageView.ScaleType.CENTER
                background = oval(tinted(0.16f))
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(TextView(ctx).apply {
                text = title; textSize = 18f; setTextColor(th.text); typeface = medium; gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(6))
            })
            addView(TextView(ctx).apply {
                text = body; textSize = 13.5f; setTextColor(th.dim); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
            })
            if (action != null) addView(pill(action, actionIcon, filled = true) { onAction() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }

    // ---------- top bar ----------

    class TopBar(val view: LinearLayout, val row: LinearLayout, val title: TextView, val subtitle: TextView, val actions: LinearLayout) {
        fun setTitle(t: CharSequence?, sub: CharSequence? = null) {
            title.text = t
            subtitle.text = sub
            subtitle.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * The 56dp bar at the top of every screen, drawn by the app (not the system action bar) so it follows the theme
     * and can slide away. Up to three [actions] show as icons; more go into a "More" sheet. The caller pads it by the
     * status bar height (its top padding).
     */
    fun topBar(title: CharSequence?, subtitle: CharSequence? = null, nav: Action? = null, actions: List<Action> = emptyList(),
               background: Drawable? = null): TopBar {
        val titleView = TextView(ctx).apply {
            textSize = 17f; typeface = medium; setTextColor(th.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(ctx).apply {
            textSize = 13f; setTextColor(th.dim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val acts = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            if (nav != null) addView(iconButton(nav.icon, nav.label, tint = th.text) { nav.onClick() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView); addView(subView)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(if (nav != null) 4 else 16); marginEnd = dp(8) })
            addView(acts)
        }
        setActions(acts, actions)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            this.background = background ?: android.graphics.drawable.ColorDrawable(th.bg)
            addView(row, LinearLayout.LayoutParams(-1, dp(56)))
            isClickable = true   // taps on the bar don't fall through to the page
        }
        return TopBar(bar, row, titleView, subView, acts).apply { setTitle(title, subtitle) }
    }

    /** Fill a top bar's action icons: up to three, or two and a "More" sheet with the rest. */
    fun setActions(acts: LinearLayout, actions: List<Action>) {
        acts.removeAllViews()
        val shown = if (actions.size > 3) actions.take(2) else actions
        for (a in shown) acts.addView(iconButton(a.icon, a.label, tint = th.text) { a.onClick() })
        if (actions.size > 3) {
            val rest = actions.drop(2)
            acts.addView(iconButton(R.drawable.ic_more, "More", tint = th.text) { menuSheet(null, rest) })
        }
    }

    // ---------- bottom sheets ----------

    /**
     * A panel that slides up from the bottom over a faded page: drag handle, optional title, then [content]
     * (scrolling unless [scroll] is false). [tall] sheets take their full height (80% of the screen) right away, for
     * lists that fill in as you type. Dismissed by tapping outside, Back, or dragging the handle down.
     * Call [Sheet.show] when it's ready.
     */
    fun sheet(title: String?, scroll: Boolean = true, tall: Boolean = false, content: LinearLayout.() -> Unit): Sheet {
        val s = Sheet(ctx, th, tall)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        s.build(title, body, scroll)
        body.content()
        return s
    }

    /** A sheet of actions; tapping one closes the sheet first. */
    fun menuSheet(title: String?, items: List<Action>, header: View? = null): Sheet {
        lateinit var s: Sheet
        s = sheet(title) {
            header?.let { addView(it) }
            for (a in items) addView(menuRow(a) { s.dismiss(); a.onClick() })
        }
        s.show()
        return s
    }

    class Sheet(ctx: Context, private val th: ReaderTheme, private val tall: Boolean) :
        Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar) {

        private val ui = Ui(ctx, th)
        lateinit var panel: LinearLayout; private set
        private lateinit var scrim: View
        var scrollView: ScrollView? = null; private set
        lateinit var body: LinearLayout; private set
        private var closing = false

        internal fun build(title: String?, body: LinearLayout, scroll: Boolean) {
            this.body = body
            val root = FrameLayout(context)
            scrim = View(context).apply {
                setBackgroundColor(th.scrim)
                alpha = 0f
                setOnClickListener { cancel() }
                contentDescription = "Close"
            }
            root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            val screenH = context.resources.displayMetrics.heightPixels
            panel = object : LinearLayout(context) {
                override fun onMeasure(w: Int, h: Int) {
                    val avail = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) screenH else MeasureSpec.getSize(h)
                    val cap = minOf(avail, (screenH * 0.8f).toInt() + paddingBottom)
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, if (tall) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                elevation = ui.dp(12).toFloat()
                background = GradientDrawable().apply {
                    val r = ui.dp(24).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(th.bar)
                }
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(FrameLayout(context).apply {
                    addView(View(context).apply { background = ui.rounded(Themes.mix(th.bar, th.text, 0.25f), 2) },
                        FrameLayout.LayoutParams(ui.dp(32), ui.dp(4), Gravity.CENTER))
                }, LinearLayout.LayoutParams(-1, ui.dp(24)))
                if (title != null) addView(TextView(context).apply {
                    text = title; textSize = 17f; typeface = ui.medium; setTextColor(th.text)
                    setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(12))
                })
            }
            dragToClose(head)
            panel.addView(head)
            if (scroll) {
                val sv = ScrollView(context).apply { addView(body); overScrollMode = View.OVER_SCROLL_NEVER }
                scrollView = sv
                panel.addView(sv, LinearLayout.LayoutParams(-1, if (tall) 0 else -2, if (tall) 1f else 0f))
            } else panel.addView(body, LinearLayout.LayoutParams(-1, if (tall) 0 else -2, if (tall) 1f else 0f))
            val maxW = ui.dp(640)
            root.addView(panel, FrameLayout.LayoutParams(
                if (context.resources.displayMetrics.widthPixels > maxW) maxW else -1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setContentView(root)

            window?.let { w ->
                w.setLayout(-1, -1)
                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                @Suppress("DEPRECATION")
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                edgeToEdge(w)
                w.setWindowAnimations(0)
            }
            // the panel's color reaches under the navigation bar; the keyboard pushes the whole panel up
            root.setOnApplyWindowInsetsListener { _, ins ->
                // left, top, right, bottom of the system bars, then the bottom including the keyboard
                @Suppress("DEPRECATION")
                val b = if (Build.VERSION.SDK_INT >= 30) {
                    val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    val i = ins.getInsets(types)
                    intArrayOf(i.left, i.top, i.right, i.bottom, ins.getInsets(types or WindowInsets.Type.ime()).bottom)
                } else intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight,
                    ins.stableInsetBottom, ins.systemWindowInsetBottom)
                root.setPadding(b[0], b[1], b[2], maxOf(0, b[4] - b[3]))
                panel.setPadding(0, 0, 0, b[3])
                ins
            }
            setOnShowListener {
                window?.let { Themes.lightBars(it, th.light) }
                panel.translationY = screenH.toFloat()
                panel.post {
                    panel.translationY = panel.height.toFloat()
                    panel.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
                    scrim.animate().alpha(1f).setDuration(200).start()
                }
            }
        }

        private fun dragToClose(handle: View) {
            var y0 = 0f
            var moved = false
            handle.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (ev.rawY - y0).coerceAtLeast(0f)
                        if (dy > ui.dp(4)) moved = true
                        panel.translationY = dy
                        scrim.alpha = 1f - (dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved && panel.translationY > panel.height * 0.22f) cancel()
                        else {
                            panel.animate().translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                            scrim.animate().alpha(1f).setDuration(160).start()
                        }
                    }
                }
                true
            }
        }

        private fun close() = super.dismiss()

        /** Slides down, then goes. */
        override fun dismiss() {
            if (closing) return
            if (!isShowing || !::panel.isInitialized) { close(); return }
            closing = true
            panel.animate().translationY(panel.height.toFloat()).setDuration(170)
                .setInterpolator(AccelerateInterpolator()).withEndAction { close() }.start()
            scrim.animate().alpha(0f).setDuration(170).start()
        }
    }
}
