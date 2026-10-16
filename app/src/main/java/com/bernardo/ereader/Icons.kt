package com.bernardo.ereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView

/**
 * The app's icons: Material Symbols (outlined; filled for the play controls) in res/drawable/ic_*.
 * They're drawn white and tinted at runtime, so they follow whichever reader theme is in use.
 */
object Icons {
    fun get(ctx: Context, res: Int, color: Int, sizeDp: Float = 18f): Drawable =
        ctx.getDrawable(res)!!.mutate().apply {
            setTint(color)
            val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
            setBounds(0, 0, px, px)
        }

    /** [res] before the text of [tv], in [color]. */
    fun leading(tv: TextView, res: Int, color: Int, sizeDp: Float = 18f, gapDp: Float = 8f) {
        tv.setCompoundDrawablesRelative(get(tv.context, res, color, sizeDp), null, null, null)
        tv.compoundDrawablePadding = (gapDp * tv.resources.displayMetrics.density).toInt()
    }

    /** [text] with [res] in front of it, inline, so both stay centered together in a wide button. */
    fun inline(ctx: Context, res: Int, color: Int, text: CharSequence, sizeDp: Float = 18f): CharSequence =
        SpannableStringBuilder("  ").append(text).apply {
            setSpan(CenteredSpan(get(ctx, res, color, sizeDp)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** An image span centered on the text's x-height band (ImageSpan's own centering needs API 29). */
    private class CenteredSpan(private val d: Drawable) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val pfm = paint.fontMetricsInt
                val mid = (pfm.ascent + pfm.descent) / 2
                fm.ascent = minOf(pfm.ascent, mid - d.bounds.height() / 2); fm.top = fm.ascent
                fm.descent = maxOf(pfm.descent, mid + d.bounds.height() / 2); fm.bottom = fm.descent
            }
            return d.bounds.right
        }

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val pfm = paint.fontMetricsInt
            c.save()
            c.translate(x, (y + (pfm.ascent + pfm.descent) / 2 - d.bounds.height() / 2).toFloat())
            d.draw(c)
            c.restore()
        }
    }

    /** [res] after the text of [tv] (at its end edge), in [color]. */
    fun trailing(tv: TextView, res: Int, color: Int, sizeDp: Float = 18f, gapDp: Float = 6f) {
        tv.setCompoundDrawablesRelative(null, null, get(tv.context, res, color, sizeDp), null)
        tv.compoundDrawablePadding = (gapDp * tv.resources.displayMetrics.density).toInt()
    }
}
