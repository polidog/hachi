package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.sqrt

/**
 * The next hour of rain as a row of bars, one per ten-minute reading.
 *
 * Observed readings are drawn solid and forecast ones translucent, so "it is raining" and "it will
 * be raining" are not the same mark, and dry slots are greyed so a dry hour reads as dry at a
 * glance.
 *
 * Heights follow the square root of mm/h up to [FULL_SCALE]. Linear was unreadable: real drizzle of
 * 0.45 mm/h came out at 4% of the bar, indistinguishable from no rain at all, while the readings
 * that matter for "do I need an umbrella" live almost entirely below 3 mm/h.
 */
class RainTimelineView(context: Context) : View(context) {
    private var points: List<RainPoint> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HAIRLINE
        strokeWidth = context.dp(1).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = context.dp(10).toFloat()
    }
    private val bar = RectF()

    fun bind(points: List<RainPoint>) {
        this.points = points
        visibility = if (points.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val labelHeight = labelPaint.textSize * 1.6f
        val floor = height - labelHeight
        canvas.drawLine(0f, floor, width.toFloat(), floor, axisPaint)

        val slot = width.toFloat() / points.size
        val gap = slot * 0.22f
        for ((index, point) in points.withIndex()) {
            val fraction = sqrt((point.mmPerHour / FULL_SCALE).coerceIn(0.0, 1.0)).toFloat()
            // A dry slot still gets a sliver, so the row reads as a timeline rather than a gap.
            val barHeight = maxOf(fraction * (floor - dp(4)), dp(2).toFloat())
            barPaint.color = when {
                point.mmPerHour <= 0.0 -> DRY
                point.observed -> OBSERVED
                else -> FORECAST
            }
            bar.set(index * slot + gap / 2, floor - barHeight, (index + 1) * slot - gap / 2, floor)
            canvas.drawRoundRect(bar, dp(2).toFloat(), dp(2).toFloat(), barPaint)
        }

        val baseline = height - labelPaint.descent()
        canvas.drawText(context.getString(R.string.rain_now), 0f, baseline, labelPaint)
        val last = points.last().minutesFromNow
        val tail = context.getString(R.string.rain_minutes, last)
        canvas.drawText(tail, width - labelPaint.measureText(tail), baseline, labelPaint)
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        /** mm/h that fills a bar. 10 mm/h is already heavy rain. */
        const val FULL_SCALE = 10.0
        /** Deep for what has fallen, the plain yellow for what is coming: both read on paper. */
        val OBSERVED = ACCENT_INK
        val FORECAST = ACCENT
        val DRY = Color.argb(0x1F, 0x16, 0x15, 0x12)
    }
}
