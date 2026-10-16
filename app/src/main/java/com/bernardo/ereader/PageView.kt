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
    private val visible = Rect()
    /** The band of the view the last drawing covered (view coordinates; the top goes negative at the start of a chapter). */
    private var drawnTop = 0
    private var drawnBottom = 0
    private var banded = false   // false = the last drawing covered everything
    /** Draw only the lines near the screen. The owner must then invalidate the view when [needsRedraw] says so. */
    var clipToVisible = false

    /**
     * True when the part on screen has moved outside what was last drawn and the view must be redrawn. The reader
     * asks on every scroll: redrawing only then (not every frame) is what keeps a glide smooth on a 120 Hz screen.
     */
    fun needsRedraw(): Boolean {
        if (!banded) return false
        if (!getLocalVisibleRect(visible)) return false
        // the margin is a screen on each side: redraw once the visible part is within half a screen of the edge
        val slack = visible.height() / 2
        return visible.top < drawnTop + slack || visible.bottom > drawnBottom - slack
    }

    /**
     * A selectable TextView asks its scroll view to show the cursor after a relayout, and with no selection the
     * cursor sits at the start: the page jumped to the top. Only a real selection may ask.
     */
    override fun requestRectangleOnScreen(rectangle: Rect, immediate: Boolean): Boolean =
        if (selectionStart == selectionEnd) false else super.requestRectangleOnScreen(rectangle, immediate)

    override fun onDraw(canvas: Canvas) {
        // A chapter is one tall view inside the scroll view. Without this clip, every redraw (a word mark fifteen times
        // a second, a new sentence) recorded all of the chapter's lines, which stuttered while the page glided. Only
        // the lines on screen, plus a screen above and below, are drawn.
        // only where the owner redraws the view as it scrolls (the reader): the appearance preview in Settings is a
        // PageView too, and nobody asks it to redraw as that screen scrolls
        if (clipToVisible && getLocalVisibleRect(visible)) {
            // vertical only: while a chapter slides in sideways the visible rect is shifted, and clipping the sides
            // too left a blank band over the text
            val margin = visible.height().coerceAtLeast(lineHeight * 4)
            drawnTop = visible.top - margin
            drawnBottom = visible.bottom + margin
            banded = true
            canvas.clipRect(0, drawnTop, width, drawnBottom)
        } else banded = false
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
            val extra = justifyExtra(layout, t, line)
            fun x(o: Int) = layout.getPrimaryHorizontal(o) + if (extra == 0f) 0f else extra * spaces(t, ls, o)
            val x1 = x(a)
            // at a line's end (a hyphenated or wrapped word) the offset belongs to the next line: use the line's edge
            val x2 = if (b >= le) (if (extra > 0f) layout.width.toFloat() else layout.getLineRight(line)) else x(b)
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

    private fun spaces(t: CharSequence, from: Int, to: Int): Int {
        var n = 0
        for (i in from until to) if (t[i] == ' ') n++
        return n
    }

    /**
     * How much wider each space on [line] is drawn than [android.text.Layout.getPrimaryHorizontal] assumes. Justified
     * lines get their spaces stretched when drawn, but on many Android versions the layout's positions ignore that, so
     * marks ended short of the last word and started a word early. 0 if the line isn't stretched or the positions
     * already include it.
     */
    private fun justifyExtra(layout: android.text.Layout, t: CharSequence, line: Int): Float {
        if (justificationMode == android.text.Layout.JUSTIFICATION_MODE_NONE) return 0f
        if (layout.getParagraphDirection(line) != android.text.Layout.DIR_LEFT_TO_RIGHT) return 0f
        val ls = layout.getLineStart(line)
        val le = layout.getLineEnd(line)
        // the last line of a paragraph (and of the text) isn't stretched
        if (le >= t.length || t[le - 1] == '\n') return 0f
        var te = le
        while (te > ls && t[te - 1].isWhitespace()) te--
        val n = spaces(t, ls, te)
        if (n == 0 || te <= ls) return 0f
        // where the line's text ends as laid out: before its trailing space, or (a hyphenated word, no space) after its
        // last letter and the hyphen
        val natural = if (te < le) layout.getPrimaryHorizontal(te)
            else layout.getPrimaryHorizontal(te - 1) + paint.measureText(t, te - 1, te) + paint.measureText("-")
        val gap = layout.width - natural
        return if (gap < 1f) 0f else gap / n
    }
}
