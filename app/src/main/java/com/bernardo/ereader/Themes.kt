package com.bernardo.ereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View

/** Reading themes. Colors are applied at runtime so switching is instant and keeps narration going. */
class ReaderTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val bar: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val onAccent: Int,
    val heading: Int,
    val highlight: Int,
    val selection: Int,
    val light: Boolean,
    /** Cards on the page. */
    val surface: Int = if (light) Themes.mix(bg, 0xFFFFFFFF.toInt(), 0.5f) else Themes.mix(bg, 0xFFFFFFFF.toInt(), 0.06f),
    /** Hairlines: card borders, dividers, unselected chips. */
    val outline: Int = Themes.mix(bar, text, if (light) 0.14f else 0.24f),
    /** Behind sheets: the page, faded. */
    val scrim: Int = (bg and 0xFFFFFF) or (0x99 shl 24),
)

object Themes {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    val all = listOf(
        ReaderTheme("linen", "Linen", c(0xF4EDE1), c(0xEBE2D2), c(0x2B2520), c(0x6E6257), c(0xB0633A), c(0xFFF6EC), c(0x6E3B21), c(0xF0D6B0), c(0xE2C08E), true),
        ReaderTheme("dusk", "Dusk", c(0x211C18), c(0x1A1613), c(0xE6DCCB), c(0x9A8E7E), c(0xD9A866), c(0x1F1710), c(0xEFC98C), c(0x4A3A22), c(0x6A5230), false),
        ReaderTheme("midnight", "Midnight", c(0x1E2430), c(0x171C26), c(0xD3D9E3), c(0x8A94A6), c(0x8FB3E8), c(0x10151D), c(0xA9C4EE), c(0x34496B), c(0x4A6390), false),
        ReaderTheme("graphite", "Graphite", c(0x252627), c(0x1B1C1D), c(0xD8D8D4), c(0x8F908C), c(0xC9CCD2), c(0x1B1C1D), c(0xEDEDE8), c(0x47494E), c(0x5C5F66), false),
        ReaderTheme("black", "Black", c(0x000000), c(0x0C0C0C), c(0xC8C8C8), c(0x777777), c(0x8FB3E8), c(0x000000), c(0xE8E8E8), c(0x1D2840), c(0x2D3C58), false),
        ReaderTheme("sepia", "Sepia", c(0xF3EAD6), c(0xE8DCC0), c(0x3A3027), c(0x7C6D5B), c(0x8A5A2B), c(0xFFF8EA), c(0x5B3A1D), c(0xEACD8F), c(0xD9BE85), true),
        ReaderTheme("paper", "Paper", c(0xFAFAF7), c(0xEEEEE9), c(0x222222), c(0x6A6A6A), c(0x2F5D9E), c(0xFFFFFF), c(0x1D3E6E), c(0xFCE3A0), c(0xBFD3F0), true),
    )

    fun current(ctx: Context): ReaderTheme {
        val p = BookStore.prefs(ctx)
        // never picked one: follow the system, warm either way
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val id = p.getString("theme", null) ?: if (night) "dusk" else "linen"
        if (id == "custom") return derive(p.getInt("custom_bg", all[0].bg), p.getInt("custom_accent", all[0].accent))
        return all.firstOrNull { it.id == id } ?: all[0]
    }

    /**
     * Before the warm themes and Literata, the defaults were Midnight and the system serif. Anyone who already used
     * the app keeps those; only new installs get the new defaults. Runs once, at app start.
     */
    fun migrate(ctx: Context) {
        val p = BookStore.prefs(ctx)
        if (p.contains("theme") && p.contains("font")) return
        val existing = p.contains("last_book") || p.contains("speed") || BookStore.dir(ctx).list().orEmpty().isNotEmpty()
        if (!existing) return
        p.edit().apply {
            if (!p.contains("theme")) putString("theme", "midnight")
            if (!p.contains("font")) putString("font", "serif")
        }.apply()
    }

    fun set(ctx: Context, t: ReaderTheme) = BookStore.prefs(ctx).edit().putString("theme", t.id).apply()

    /** Switch to a custom theme; unspecified colors are taken from the theme in use. */
    fun setCustom(ctx: Context, bg: Int? = null, accent: Int? = null) {
        val cur = current(ctx)
        BookStore.prefs(ctx).edit()
            .putString("theme", "custom")
            .putInt("custom_bg", bg ?: cur.bg)
            .putInt("custom_accent", accent ?: cur.accent)
            .apply()
    }

    val backgrounds = listOf(0x1E2430, 0x252627, 0x000000, 0x0F1E2E, 0x1F2A24, 0x2A1F2D, 0x2B2A27,
        0xF4EDE1, 0x211C18, 0xF3EAD6, 0xFAFAF7, 0xE8EEF2, 0xF6E9E4, 0xD9E4D3).map { c(it.toLong()) }
    val accents = listOf(0x8FB3E8, 0x6FD0E0, 0x7FC8A9, 0x16A085, 0xE8C36A, 0xE8A87C, 0xE87C8F, 0xC0392B,
        0xC59BE8, 0xB0B5BD, 0x8A5A2B, 0x2F5D9E, 0xB0633A, 0xD9A866).map { c(it.toLong()) }

    // ---------- deriving a full palette from two colors ----------

    fun luminance(color: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((color shr 16) and 0xFF) + 0.7152 * ch((color shr 8) and 0xFF) + 0.0722 * ch(color and 0xFF)
    }

    /** Linear blend of [a] toward [b] by [t] (0 = a, 1 = b). */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun m(sh: Int) = (((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    fun derive(bg: Int, accent: Int): ReaderTheme {
        val light = luminance(bg) > 0.4
        val black = c(0x000000); val white = c(0xFFFFFF)
        val text = if (light) mix(bg, black, 0.85f) else mix(bg, white, 0.84f)
        val dim = if (light) mix(bg, black, 0.55f) else mix(bg, white, 0.5f)
        val bar = when {
            light -> mix(bg, black, 0.06f)
            luminance(bg) < 0.004 -> mix(bg, white, 0.05f)   // pure black: lift the bars a touch
            else -> mix(bg, black, 0.25f)
        }
        // keep the accent readable on the background: push it toward the text color if contrast is poor
        val contrast = (maxOf(luminance(accent), luminance(bg)) + 0.05) / (minOf(luminance(accent), luminance(bg)) + 0.05)
        val acc = if (contrast < 2.2) mix(accent, text, 0.4f) else accent
        return ReaderTheme(
            id = "custom", name = "Custom", bg = bg, bar = bar, text = text, dim = dim,
            accent = acc, onAccent = if (luminance(acc) > 0.45) c(0x111111) else white,
            heading = mix(acc, text, 0.3f),
            highlight = mix(bg, acc, if (light) 0.32f else 0.35f),
            selection = mix(bg, acc, 0.55f),
            light = light,
        )
    }

    // ---------- optional paper texture behind the page ----------

    fun paperTexture(ctx: Context) = BookStore.prefs(ctx).getBoolean("paper_texture", false)

    private var paperCache: Pair<Int, android.graphics.Bitmap>? = null
    /** The texture's light and shade as signed deviations from its mean, one byte per pixel (128 = flat). */
    private var paperRelief: ByteArray? = null
    private var paperSide = 0

    /**
     * The page background: the theme color, or (with the paper option on) that color carrying the relief of a real
     * sheet of lightly crumpled paper (Paper Wrinkled 001 from 3dtextures.me, CC0), tinted once per background color.
     */
    fun pageBackground(ctx: Context, t: ReaderTheme): android.graphics.drawable.Drawable {
        if (!paperTexture(ctx)) return ColorDrawable(t.bg)
        val bmp = paperCache?.takeIf { it.first == t.bg }?.second ?: paperBitmap(ctx, t).also { paperCache = t.bg to it }
        // one texture pixel per screen pixel: a 1024 px tile is about a screen wide, so creases span the page
        bmp.density = ctx.resources.displayMetrics.densityDpi
        // no intrinsic size: a view takes its background's size as its minimum, which made the reader's top bar as tall
        // as the tile (a big blank band over the start of the chapter)
        return object : android.graphics.drawable.BitmapDrawable(ctx.resources, bmp) {
            override fun getIntrinsicWidth() = -1
            override fun getIntrinsicHeight() = -1
        }.apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            isFilterBitmap = true
        }
    }

    /**
     * The photographed sheet reduced to its relief: luminance minus the mean. It's a ruled notebook page, so the blue
     * lines are painted over with the paper just above and below them, leaving only the creases and grain.
     */
    private fun paperRelief(ctx: Context): ByteArray {
        paperRelief?.let { return it }
        val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
        val src = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.paper_wrinkled, opts)
        val n = src.width
        val px = IntArray(n * n).also { src.getPixels(it, 0, n, 0, 0, n, n) }
        src.recycle()
        val lum = FloatArray(n * n)
        val line = BooleanArray(n * n)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF; val g = (px[i] shr 8) and 0xFF; val b = px[i] and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            line[i] = b - (r + g) / 2 > 14
        }
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            if (!line[i]) continue
            // nearest unlined rows above and below (the lines are a few pixels thick)
            var up = y; var down = y
            while (up > 0 && line[up * n + x]) up--
            while (down < n - 1 && line[down * n + x]) down++
            lum[i] = (lum[up * n + x] + lum[down * n + x]) / 2
        }
        val mean = lum.average().toFloat()
        val out = ByteArray(n * n) { i -> (lum[i] - mean).toInt().coerceIn(-127, 127).toByte() }
        paperRelief = out
        paperSide = n
        return out
    }

    private fun paperBitmap(ctx: Context, t: ReaderTheme): android.graphics.Bitmap {
        val relief = paperRelief(ctx)
        val n = paperSide
        val r0 = (t.bg shr 16) and 0xFF; val g0 = (t.bg shr 8) and 0xFF; val b0 = t.bg and 0xFF
        // light pages: the creases darken and lighten the color itself; dark pages: a faint lift toward light, so the
        // relief shows without the page going grey
        val px = IntArray(n * n)
        for (i in px.indices) {
            val f = relief[i] / 255f
            fun ch(c: Int) = (if (t.light) c + f * c * 1.25f else c + f * (255 - c + 40) * 0.55f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(r0) shl 16) or (ch(g0) shl 8) or ch(b0)
        }
        return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** Every dialog in the app goes through this, so they all follow the current theme (presets and custom). */
    fun dialog(ctx: Context): AlertDialog.Builder = ThemedBuilder(ctx, current(ctx))

    private class ThemedBuilder(ctx: Context, val t: ReaderTheme) : AlertDialog.Builder(ctx,
        if (t.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert) {
        override fun create(): AlertDialog {
            val d = super.create()
            d.setOnShowListener { style(d, t) }
            return d
        }
    }

    private fun style(d: AlertDialog, t: ReaderTheme) {
        val density = d.context.resources.displayMetrics.density
        d.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22 * density; setColor(t.bar)
        })
        val res = d.context.resources
        res.getIdentifier("alertTitle", "id", "android").takeIf { it != 0 }
            ?.let { d.findViewById<android.widget.TextView>(it) }?.setTextColor(t.text)
        d.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(t.text)
        for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            d.getButton(b)?.setTextColor(t.accent)
        }
        // list dialogs (chapters, options, single choice): color rows as they're laid out
        d.listView?.let { list ->
            list.setBackgroundColor(t.bar)
            list.divider = null
            fun paint(v: View) {
                (v as? android.widget.TextView)?.setTextColor(t.text)
                (v as? android.widget.CheckedTextView)?.checkMarkTintList = android.content.res.ColorStateList.valueOf(t.accent)
            }
            for (i in 0 until list.childCount) paint(list.getChildAt(i))
            list.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { child?.let(::paint) }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
        }
    }

    /** System bars are transparent (edge-to-edge, see [Ui.edgeToEdge]); only their icons follow the theme. */
    @Suppress("DEPRECATION")
    fun applyChrome(a: Activity, t: ReaderTheme) {
        a.window.decorView.setBackgroundColor(t.bg)
        lightBars(a.window, t.light)
    }

    @Suppress("DEPRECATION")
    fun lightBars(w: android.view.Window, light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val v = w.decorView.systemUiVisibility
            w.decorView.systemUiVisibility = if (light) v or flags else v and flags.inv()
        }
    }
}
