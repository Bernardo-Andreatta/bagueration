package com.bernardo.ereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.AttributeSet
import android.widget.TextView

/**
 * A mark on the page, drawn by [PageView] behind the text: a soft rounded highlight per line (the spoken sentence,
 * bookmarks, notes, search hits), or a dotted underline (where you left off). [layer] orders overlapping marks.
 */
class Mark(val color: Int, val layer: Int = 0, val dotted: Boolean = false) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {}
}

/** The book page: a TextView that draws [Mark]s as rounded, padded shapes instead of hard-edged span backgrounds. */
class PageView(ctx: Context, attrs: AttributeSet?) : TextView(ctx, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 4 * density
    private val padH = 2 * density
    private val padV = 2 * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(0.01f, 5 * density), 0f)
    }
    private val rect = RectF()
    private val clip = Rect()

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val t = text as? Spanned
        if (layout != null && t != null) {
            val marks = t.getSpans(0, t.length, Mark::class.java)
            if (marks.isNotEmpty()) {
                marks.sortBy { it.layer }
                canvas.save()
                canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
                canvas.getClipBounds(clip)
                val firstVisible = layout.getLineForVertical(clip.top)
                val lastVisible = layout.getLineForVertical(clip.bottom)
                for (m in marks) draw(canvas, layout, t, m, firstVisible, lastVisible)
                canvas.restore()
            }
        }
        super.onDraw(canvas)
    }

    private fun draw(canvas: Canvas, layout: android.text.Layout, t: Spanned, m: Mark, firstVisible: Int, lastVisible: Int) {
        val s = t.getSpanStart(m)
        val e = t.getSpanEnd(m)
        if (s < 0 || e <= s) return
        val l0 = maxOf(layout.getLineForOffset(s), firstVisible)
        val l1 = minOf(layout.getLineForOffset(e - 1), lastVisible)
        for (line in l0..l1) {
            val ls = layout.getLineStart(line)
            val le = layout.getLineEnd(line)
            var a = maxOf(s, ls)
            var b = minOf(e, le)
            while (b > a && t[b - 1].isWhitespace()) b--   // not the space or line break at the end of the line
            while (a < b && t[a].isWhitespace()) a++
            if (b <= a) continue
            val x1 = layout.getPrimaryHorizontal(a)
            // at a line's end (a hyphenated or wrapped word) the offset belongs to the next line: use the line's edge
            val x2 = if (b >= le) layout.getLineRight(line) else layout.getPrimaryHorizontal(b)
            val left = minOf(x1, x2); val right = maxOf(x1, x2)
            val baseline = layout.getLineBaseline(line).toFloat()
            // the line's ascent (the tallest text on it, so headings get a taller mark); descent from it, since the
            // line's own bottom includes the line spacing
            val ascent = baseline - layout.getLineTop(line)
            if (m.dotted) {
                dots.color = m.color
                val y = baseline + ascent * 0.24f
                canvas.drawLine(left + dots.strokeWidth, y, right, y, dots)
            } else {
                fill.color = m.color
                rect.set(left - padH, baseline - ascent * 0.9f - padV, right + padH, baseline + ascent * 0.28f + padV)
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
        }
    }
}
