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

/** The standby face: a big clock over a flat background, redrawn once a second. */
class ClockView(context: Context) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
        textAlign = Paint.Align.CENTER
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
        canvas.drawColor(Color.rgb(0x10, 0x14, 0x1C))
        val now = LocalDateTime.now()
        timePaint.textSize = height * 0.42f
        datePaint.textSize = height * 0.10f
        val cx = width / 2f
        canvas.drawText(now.format(TIME), cx, height * 0.55f, timePaint)
        canvas.drawText(dateLine(now), cx, height * 0.75f, datePaint)
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
    }
}
