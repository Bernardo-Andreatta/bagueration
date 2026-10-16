package com.bernardo.ereader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.sin

/**
 * Three little dots bouncing one after the other: "the voice is loading". Sized like an icon (24dp) so it can stand
 * in for a play/stop icon on a button while a sample is being prepared. Animates itself as long as it's drawn.
 */
class BouncingDots(color: Int, private val density: Float) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var running = false
    private val size = (24 * density).toInt()
    private val frame = Runnable { invalidateSelf() }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = 2.4f * density
        val gap = 6.5f * density
        val cx = b.exactCenterX(); val cy = b.exactCenterY() + 1.5f * density
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0..2) {
            // each dot a third of a cycle behind the one before; only the upward half of the sine shows
            val phase = (t * 2 * Math.PI / 0.9 - i * 0.9).let { sin(it) }.coerceAtLeast(0.0)
            val lift = (phase * 5 * density).toFloat()
            canvas.drawCircle(cx + (i - 1) * gap, cy - lift, r, paint)
        }
        if (running) scheduleSelf(frame, SystemClock.uptimeMillis() + 33)
    }

    override fun start() { if (!running) { running = true; invalidateSelf() } }
    override fun stop() { running = false; unscheduleSelf(frame) }
    override fun isRunning() = running

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
