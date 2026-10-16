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

    /**
     * The page background: the theme color, or (with the paper option on) that color with a soft, tiling paper
     * grain: gentle blotches, fine grain and a few faint fibers, made once per background color.
     */
    fun pageBackground(ctx: Context, t: ReaderTheme): android.graphics.drawable.Drawable {
        if (!paperTexture(ctx)) return ColorDrawable(t.bg)
        val bmp = paperCache?.takeIf { it.first == t.bg }?.second ?: paperBitmap(t).also { paperCache = t.bg to it }
        // shown at 2x its pixel size so the grain reads as soft texture, not pixel noise
        bmp.density = ctx.resources.displayMetrics.densityDpi / 2
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
     * Recycled paper: a slightly greyer, oatmeal stock with cloudy mottling, a sprinkle of tiny flecks (mostly brown-grey,
     * a few ink blue / red from the paper it was made from) and short fibers. Seamless tile; dark themes get dark
     * flecks (light ones read as stars there).
     */
    private fun paperBitmap(t: ReaderTheme): android.graphics.Bitmap {
        val n = 384
        val rnd = java.util.Random(11)
        // smooth value noise that wraps at the tile edge (cells divides n), so tiles join without seams
        fun layer(cells: Int): FloatArray {
            val g = FloatArray(cells * cells) { rnd.nextFloat() * 2 - 1 }
            val out = FloatArray(n * n)
            val step = n / cells.toFloat()
            for (y in 0 until n) for (x in 0 until n) {
                val gx = x / step; val gy = y / step
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val fx = gx - x0; val fy = gy - y0
                val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
                fun at(i: Int, j: Int) = g[(j % cells) * cells + (i % cells)]
                val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * sx
                val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * sx
                out[y * n + x] = top + (bottom - top) * sy
            }
            return out
        }
        val base = if (t.light) mix(t.bg, 0xFFC9BFAE.toInt(), 0.10f) else mix(t.bg, 0xFF6B6255.toInt(), 0.04f)
        val cloud = layer(3); val mottle = layer(8); val mid = layer(24); val fine = layer(96)
        // light paper darkens in the grain; dark themes lighten a touch (less, or it looks dusty)
        val amp = if (t.light) 0.075f else 0.045f
        val px = IntArray(n * n)
        val r0 = (base shr 16) and 0xFF; val g0 = (base shr 8) and 0xFF; val b0 = base and 0xFF
        for (i in 0 until n * n) {
            val v = (cloud[i] * 0.35f + mottle[i] * 0.3f + mid[i] * 0.2f + fine[i] * 0.12f + (rnd.nextFloat() * 2 - 1) * 0.12f) * amp
            fun ch(c: Int) = (c + v * (if (t.light) c else 255 - c + 40)).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (ch(r0) shl 16) or (ch(g0) shl 8) or ch(b0)
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)
        // drawn at the wrapped positions too, so marks crossing a tile edge join cleanly
        fun wrapped(draw: () -> Unit) {
            for (ox in intArrayOf(-n, 0, n)) for (oy in intArrayOf(-n, 0, n)) {
                canvas.save(); canvas.translate(ox.toFloat(), oy.toFloat()); draw(); canvas.restore()
            }
        }
        // fleck colors: brown-grey, grey, ink blue, ink red
        val flecks = intArrayOf(0x3A3128, 0x555555, 0x3B5B8C, 0x8C3B2E)
        fun fleck(): Int {
            val r = rnd.nextFloat()
            return flecks[when { r < 0.68f -> 0; r < 0.86f -> 1; r < 0.95f -> 2; else -> 3 }]
        }
        val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        repeat(260) {
            val x = rnd.nextFloat() * n; val y = rnd.nextFloat() * n
            val r = 0.3f + Math.pow(rnd.nextFloat().toDouble(), 4.0).toFloat() * 1.1f
            val squash = 0.6f + rnd.nextFloat() * 0.4f
            dot.color = when {
                t.light -> fleck() or ((16 + rnd.nextInt(34)) shl 24)
                rnd.nextFloat() < 0.85f -> (40 + rnd.nextInt(50)) shl 24                    // black fleck
                else -> 0xEBE4D7 or ((10 + rnd.nextInt(14)) shl 24)                        // faint light fleck
            }
            val oval = android.graphics.RectF(x - r, y - r * squash, x + r, y + r)
            wrapped { canvas.drawOval(oval, dot) }
        }
        val fiber = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        repeat(170) {
            val x = rnd.nextFloat() * n; val y = rnd.nextFloat() * n
            val len = 5 + rnd.nextFloat() * 22; val a = rnd.nextFloat() * Math.PI.toFloat()
            val bend = (rnd.nextFloat() - 0.5f) * 7f
            val dx = kotlin.math.cos(a) * len; val dy = kotlin.math.sin(a) * len
            val path = android.graphics.Path().apply {
                moveTo(x, y); quadTo(x + dx / 2 - dy / len * bend * 2, y + dy / 2 + dx / len * bend * 2, x + dx, y + dy)
            }
            val colored = rnd.nextFloat() < 0.12f
            val rgb = if (colored) fleck() else if (t.light) 0x28221C else 0xE6E0D6
            val alpha = ((16 + rnd.nextInt(22)) * (if (t.light) 1f else 0.7f)).toInt() + (if (colored) 10 else 0)
            fiber.color = rgb or (alpha shl 24)
            fiber.strokeWidth = 0.45f + rnd.nextFloat() * 0.55f
            wrapped { canvas.drawPath(path, fiber) }
        }
        return bmp
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
