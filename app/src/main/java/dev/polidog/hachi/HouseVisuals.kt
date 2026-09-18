package dev.polidog.hachi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.polidog.hachi.tools.ClimateState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Decorative only: the adjacent native buttons remain the accessible temperature controls. */
@SuppressLint("ViewConstructor") // Created with device data in code, never inflated from XML.
internal class TemperatureArc(context: Context, value: Double?, climate: ClimateState?, active: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private val fraction = if (value != null && climate?.minimum != null && climate.maximum != null && climate.maximum > climate.minimum)
        ((value - climate.minimum) / (climate.maximum - climate.minimum)).coerceIn(0.0, 1.0).toFloat() else null
    private val accent = if (active) LIME else MUTED

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f - context.dp(9)
        if (radius <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = context.dp(3).toFloat()
        paint.color = SURFACE_ON
        canvas.drawArc(oval, 140f, 260f, false, paint)
        fraction?.let {
            paint.color = Color.argb(24, Color.red(accent), Color.green(accent), Color.blue(accent))
            paint.strokeWidth = context.dp(9).toFloat()
            canvas.drawArc(oval, 140f, 260f * it, false, paint)
            paint.color = accent
            paint.strokeWidth = context.dp(3).toFloat()
            canvas.drawArc(oval, 140f, 260f * it, false, paint)
        }
        paint.strokeWidth = 1f
        paint.color = Color.argb(0x4D, 0x93, 0x9B, 0x8F)
        for (tick in 0..26) {
            val angle = Math.toRadians(140.0 + tick * 10)
            val inner = radius - context.dp(7)
            val outer = radius - context.dp(if (tick % 5 == 0) 11 else 9)
            canvas.drawLine(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer, paint)
        }
    }
}

/** One consistent line weight for the device types supported by House. */
@SuppressLint("ViewConstructor") // Created with a device type in code, never inflated from XML.
internal class HouseGlyph(context: Context, private val kind: String, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        val size = min(width, height).toFloat()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (kind) {
            "room" -> {
                path.reset(); path.moveTo(3f, 10f); path.lineTo(12f, 3f); path.lineTo(21f, 10f)
                path.moveTo(5f, 9f); path.lineTo(5f, 21f); path.lineTo(19f, 21f); path.lineTo(19f, 9f)
                path.moveTo(10f, 21f); path.lineTo(10f, 14f); path.lineTo(14f, 14f); path.lineTo(14f, 21f)
                canvas.drawPath(path, paint)
            }
            "climate" -> {
                canvas.drawRoundRect(2f, 4f, 22f, 14f, 3f, 3f, paint)
                canvas.drawLine(5f, 10f, 19f, 10f, paint)
                for (x in listOf(7f, 12f, 17f)) canvas.drawLine(x, 17f, x, 21f, paint)
            }
            "light" -> {
                canvas.drawArc(6f, 3f, 18f, 15f, 150f, 240f, false, paint)
                path.reset(); path.moveTo(7f, 12f); path.lineTo(9f, 17f); path.lineTo(15f, 17f); path.lineTo(17f, 12f)
                canvas.drawPath(path, paint)
                canvas.drawLine(9f, 20f, 15f, 20f, paint)
                canvas.drawLine(11f, 22f, 13f, 22f, paint)
            }
            "cover" -> {
                canvas.drawRoundRect(4f, 3f, 20f, 21f, 1f, 1f, paint)
                for (y in listOf(7f, 11f, 15f)) canvas.drawLine(4f, y, 20f, y, paint)
                canvas.drawLine(12f, 17f, 12f, 19f, paint)
            }
            "fan" -> {
                canvas.drawCircle(12f, 12f, 2f, paint)
                repeat(3) {
                    canvas.save(); canvas.rotate(it * 120f, 12f, 12f)
                    path.reset(); path.moveTo(11f, 10f); path.cubicTo(2f, 7f, 10f, -2f, 14f, 4f)
                    path.cubicTo(16f, 7f, 13f, 8f, 13f, 10f); canvas.drawPath(path, paint)
                    canvas.restore()
                }
            }
            else -> {
                canvas.drawArc(4f, 4f, 20f, 21f, -55f, 290f, false, paint)
                canvas.drawLine(12f, 2f, 12f, 12f, paint)
            }
        }
        canvas.restore()
    }
}
