package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import java.time.LocalDateTime

/**
 * The backdrop every page sits on: a sky that follows the time of day.
 *
 * Separate from the clock so the weather page shares the same sky. The gradient is rebuilt only when
 * the minute changes, and redrawn only then too -- this is on screen all day on a slow tablet.
 */
class SkyView(context: Context) : View(context) {
    private val paint = Paint()
    private var minute = -1

    private val tick = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now()
            if (now.hour * 60 + now.minute != minute) invalidate()
            postDelayed(this, 30_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        minute = -1 // the gradient is height-dependent
    }

    override fun onDraw(canvas: Canvas) {
        val now = LocalDateTime.now()
        val current = now.hour * 60 + now.minute
        if (current != minute || paint.shader == null) {
            minute = current
            val palette = DayPalette.at(current)
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(palette.top, palette.middle, palette.bottom),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
