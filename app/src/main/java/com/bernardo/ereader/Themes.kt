package com.bernardo.ereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan
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
)

object Themes {
    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    val all = listOf(
        ReaderTheme("midnight", "Midnight", c(0x1E2430), c(0x171C26), c(0xD3D9E3), c(0x8A94A6), c(0x8FB3E8), c(0x10151D), c(0xA9C4EE), c(0x34496B), c(0x4A6390), false),
        ReaderTheme("graphite", "Graphite", c(0x252627), c(0x1B1C1D), c(0xD8D8D4), c(0x8F908C), c(0xC9CCD2), c(0x1B1C1D), c(0xEDEDE8), c(0x47494E), c(0x5C5F66), false),
        ReaderTheme("black", "Black", c(0x000000), c(0x0C0C0C), c(0xC8C8C8), c(0x777777), c(0x8FB3E8), c(0x000000), c(0xE8E8E8), c(0x1D2840), c(0x2D3C58), false),
        ReaderTheme("sepia", "Sepia", c(0xF3EAD6), c(0xE8DCC0), c(0x3A3027), c(0x7C6D5B), c(0x8A5A2B), c(0xFFF8EA), c(0x5B3A1D), c(0xEACD8F), c(0xD9BE85), true),
        ReaderTheme("paper", "Paper", c(0xFAFAF7), c(0xEEEEE9), c(0x222222), c(0x6A6A6A), c(0x2F5D9E), c(0xFFFFFF), c(0x1D3E6E), c(0xFCE3A0), c(0xBFD3F0), true),
    )

    fun current(ctx: Context): ReaderTheme {
        val p = BookStore.prefs(ctx)
        val id = p.getString("theme", "midnight")
        if (id == "custom") return derive(p.getInt("custom_bg", all[0].bg), p.getInt("custom_accent", all[0].accent))
        return all.firstOrNull { it.id == id } ?: all[0]
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
        0xF3EAD6, 0xFAFAF7, 0xE8EEF2, 0xF6E9E4, 0xD9E4D3).map { c(it.toLong()) }
    val accents = listOf(0x8FB3E8, 0x6FD0E0, 0x7FC8A9, 0x16A085, 0xE8C36A, 0xE8A87C, 0xE87C8F, 0xC0392B,
        0xC59BE8, 0xB0B5BD, 0x8A5A2B, 0x2F5D9E).map { c(it.toLong()) }

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

    /** Action bar, status bar and nav bar colors for the given theme. */
    @Suppress("DEPRECATION")
    fun applyChrome(a: Activity, t: ReaderTheme) {
        a.window.statusBarColor = t.bar
        a.window.navigationBarColor = t.bar
        a.window.decorView.setBackgroundColor(t.bg)
        a.actionBar?.setBackgroundDrawable(ColorDrawable(t.bar))
        a.actionBar?.elevation = 0f
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            a.window.insetsController?.setSystemBarsAppearance(if (t.light) flags else 0, flags)
        } else {
            var v = a.window.decorView.systemUiVisibility
            v = if (t.light) v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else v and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            a.window.decorView.systemUiVisibility = v
        }
    }

    /** Action bar text in theme colors (the base theme's title color is fixed light). */
    fun tint(s: CharSequence?, color: Int): CharSequence? = s?.let {
        SpannableString(it.toString()).apply { setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
