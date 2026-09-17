package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The standby face: a big clock over a sky that follows the time of day, redrawn once a second.
 *
 * The gradient is rebuilt only when the palette actually moves (once a minute at most), not on every
 * tick -- this runs on a slow tablet and the clock is on screen all day.
 */
class ClockView(context: Context) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM_70
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val skyPaint = Paint()
    private var skyMinute = -1

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Re-align to the next wall-clock second so the display never drifts a beat behind.
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
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
        skyMinute = -1 // force the gradient to be rebuilt at the new height
    }

    override fun onDraw(canvas: Canvas) {
        val now = LocalDateTime.now()
        drawSky(now.hour * 60 + now.minute)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)

        timePaint.textSize = height * 0.44f
        datePaint.textSize = height * 0.085f
        val shadow = height * 0.02f
        timePaint.setShadowLayer(shadow, 0f, shadow * 0.3f, SHADOW)
        datePaint.setShadowLayer(shadow, 0f, shadow * 0.3f, SHADOW)

        val cx = width / 2f
        canvas.drawText(now.format(TIME), cx, height * 0.52f, timePaint)
        canvas.drawText(dateLine(now), cx, height * 0.68f, datePaint)
    }

    private fun drawSky(minuteOfDay: Int) {
        if (minuteOfDay == skyMinute && skyPaint.shader != null) return
        skyMinute = minuteOfDay
        val palette = DayPalette.at(minuteOfDay)
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(palette.top, palette.middle, palette.bottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun dateLine(now: LocalDateTime): String {
        val locale = Locale.getDefault()
        val day = now.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        return if (locale.language == "ja") "${now.monthValue}月${now.dayOfMonth}日 ($day)"
        else "${now.format(DATE)} ($day)"
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
        val CREAM = Color.rgb(0xFA, 0xF6, 0xEC)
        val CREAM_70 = Color.argb(0xB3, 0xFA, 0xF6, 0xEC)
        val SHADOW = Color.argb(0x66, 0, 0, 0)
    }
}
