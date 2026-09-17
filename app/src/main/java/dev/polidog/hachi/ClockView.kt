package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Home page 0: the clock, drawn over whatever [SkyView] is showing behind it. */
class ClockView(context: Context) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB3, 0xFA, 0xF6, 0xEC)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }

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

    override fun onDraw(canvas: Canvas) {
        val now = LocalDateTime.now()
        timePaint.textSize = height * 0.44f
        datePaint.textSize = height * 0.085f
        val shadow = height * 0.02f
        timePaint.setShadowLayer(shadow, 0f, shadow * 0.3f, SHADOW)
        datePaint.setShadowLayer(shadow, 0f, shadow * 0.3f, SHADOW)

        val cx = width / 2f
        canvas.drawText(now.format(TIME), cx, height * 0.52f, timePaint)
        canvas.drawText(dateLine(now), cx, height * 0.68f, datePaint)
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
        val SHADOW = Color.argb(0x66, 0, 0, 0)
    }
}
