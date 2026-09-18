package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.time.LocalDateTime
import java.time.chrono.JapaneseChronology
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home page 0: the time, set large and flush left, with the date under it.
 *
 * The layout is a calendar page's: one heavy number, then the month on one line and the year in grey
 * under it, with the weekday pushed to the far side. No box, no shadow -- on paper the type is the
 * whole of the design, and anything behind it only competes.
 */
class ClockView(context: Context) : View(context) {
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        typeface = DISPLAY
        // Big numerals set at their default spacing look like they are drifting apart.
        letterSpacing = -0.03f
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val yearPaint = Paint(datePaint).apply { color = MUTED }
    private val weekdayPaint = Paint(datePaint).apply { textAlign = Paint.Align.RIGHT }

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
        val locale = Locale.getDefault()
        val side = width * 0.07f
        timePaint.textSize = height * 0.42f
        val small = height * 0.07f
        datePaint.textSize = small
        yearPaint.textSize = small
        weekdayPaint.textSize = small

        val timeBase = height * 0.52f
        canvas.drawText(now.format(TIME), side, timeBase, timePaint)
        val dateBase = timeBase + small * 2.1f
        canvas.drawText(clockDate(now, locale), side, dateBase, datePaint)
        // The era where there is one, the plain year where there is not: either way, grey under it.
        val yearBase = dateBase + small * 1.3f
        canvas.drawText(clockEra(now, locale).ifEmpty { now.year.toString() }, side, yearBase, yearPaint)
        canvas.drawText(now.dayOfWeek.getDisplayName(TextStyle.FULL, locale), width - side, yearBase, weekdayPaint)
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}

/** The month and day under the time. The weekday and the era each get their own place. */
internal fun clockDate(now: LocalDateTime, locale: Locale): String =
    if (locale.language == "ja") "${now.monthValue}月${now.dayOfMonth}日"
    else now.format(DATE.withLocale(locale))

/**
 * Empty when the locale has no era to show. A pattern on an ISO date prints 西暦, so this has
 * to be the Japanese calendar.
 */
internal fun clockEra(now: LocalDateTime, locale: Locale): String =
    if (locale.language == "ja") now.format(ERA) else ""

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d")
private val ERA: DateTimeFormatter = DateTimeFormatter.ofPattern("Gy年")
    .withChronology(JapaneseChronology.INSTANCE)
    .withLocale(Locale.JAPAN)
