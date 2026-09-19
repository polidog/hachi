package dev.polidog.hachi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import dev.polidog.hachi.tools.ClimateState
import java.text.NumberFormat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Where a drag around the dial lands: [fraction] of the way from [min] to [max], snapped to [step].
 *
 * Snapped from the bottom of the range rather than from zero, so a 0.5 step on a 16.2 minimum still
 * lands on values the thermostat will accept.
 */
internal fun dialValue(fraction: Float, min: Double, max: Double, step: Double): Double {
    val raw = min + (max - min) * fraction.coerceIn(0f, 1f)
    val snapped = if (step > 0) min + round((raw - min) / step) * step else raw
    return (round(snapped * 1000) / 1000).coerceIn(min, max)
}

/** (deep, light): the device card's glow for each climate mode. */
internal fun modeTones(mode: String): Pair<Int, Int> = when (mode) {
    "cool", "dry" -> Color.rgb(0xA9, 0xB9, 0xC4) to Color.rgb(0xE0, 0xE6, 0xEA)
    "heat" -> Color.rgb(0xD9, 0xB0, 0x98) to Color.rgb(0xF1, 0xE3, 0xDA)
    "off" -> Color.rgb(0xCF, 0xCD, 0xC7) to Color.rgb(0xEB, 0xE9, 0xE4)
    else -> Color.rgb(0xA8, 0xB4, 0xA3) to Color.rgb(0xDD, 0xE2, 0xD8)
}

internal fun blend(from: Int, to: Int, t: Float) = Color.rgb(
    (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
    (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
    (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
)

/**
 * A device card's face: the thing's own light, pooled at the top of the card and fading into it --
 * lamplight when a light is on, the air conditioner's colour when it runs, only a faint wash when off.
 */
internal class DeviceBackdrop(
    context: Context,
    tones: Pair<Int, Int>,
    private val lit: Boolean,
    /** How far down the card the glyph stands, as a fraction of its height: the light pools there. */
    private val centre: Float = 0.26f,
) : CardFace(context) {
    private val deep = c(tones.first)
    private val light = c(tones.second)

    override fun scene(canvas: Canvas, w: Float, h: Float) {
        val foot = h * (centre + 0.36f)
        paint.shader = LinearGradient(0f, 0f, 0f, foot, light, SURFACE, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, foot, paint)
        // Only something that is on gives off light; a grey pool round an off switch reads as a smudge.
        if (lit) {
            paint.shader = RadialGradient(w / 2, h * centre, w * 0.62f, deep, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, foot, paint)
        }
        fade(canvas, w, h * (centre + 0.14f), foot)
    }
}

/** A room with a light on: lamplight, the accent let down. */
internal val LAMP_TONES = Color.rgb(0xEE, 0xCB, 0x5E) to Color.rgb(0xF8, 0xEE, 0xCC)

/**
 * The card both faces share: a rounded panel with a picture across its top and a hairline round
 * the edge. [scene] draws the picture, already clipped to the corners.
 */
internal abstract class CardFace(protected val context: Context) : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()

    /** By night the picture sinks most of the way into the card, so the wall does not light the room. */
    protected fun c(colour: Int) = if (Theme.night) blend(colour, SURFACE, 0.72f) else colour

    protected abstract fun scene(canvas: Canvas, w: Float, h: Float)

    /** The lower part of the picture dissolving into the card. */
    protected fun fade(canvas: Canvas, w: Float, from: Float, to: Float) {
        paint.shader = LinearGradient(0f, from, 0f, to, Color.TRANSPARENT, SURFACE, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, to + 1, paint)
        paint.shader = null
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val r = context.dp(RADIUS).toFloat()
        // The outline below leaves the hairline's alpha on the paint, and a shader is drawn through it.
        paint.color = Color.BLACK
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        path.reset()
        path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawColor(SURFACE)
        scene(canvas, w, h)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1).toFloat()
        paint.color = HAIRLINE
        canvas.drawRoundRect(bounds.left + 0.5f, bounds.top + 0.5f, bounds.right - 0.5f, bounds.bottom - 0.5f, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The air conditioner as one object: a fan of modes over a large dial.
 *
 * The fan hugs the top of the dial, one segment per mode the unit offers, and the one it is in sits
 * raised and deeper in colour. The dial carries a ring of ticks and a single needle for the target;
 * dragging anywhere around it moves the needle, and letting go is what sends the new target -- a
 * drag is a dozen values on the way to one, and the house only needs to hear the last.
 *
 * The selected mode wears the yellow accent. A running unit has a warm paper dial and yellow
 * rim; off returns to plain paper. The mode's name carries the distinction between heat and cool.
 */
@SuppressLint("ViewConstructor") // Created with device data in code, never inflated from XML.
internal class ClimateDial(
    context: Context,
    private val modes: List<String>,
    private val mode: String,
    private val modeLabel: (String) -> String,
    private val value: Double?,
    private val climate: ClimateState,
    private val caption: String,
    private val enabled: Boolean,
    private val onMode: (String) -> Unit,
    private val onTarget: (Double) -> Unit,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val segment = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = CornerPathEffect(context.dp(10).toFloat()) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val oval = RectF()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    /** The fan's measurements, all in proportion to the dial so it reads the same at any size. */
    private var gap = 0f
    private var thickness = 0f
    private var raise = 0f

    /** The needle while a finger is on the dial; null when it shows the device's own target. */
    private var dragging: Double? = null
    private var pressedMode = -1

    private val min = climate.minimum
    private val max = climate.maximum
    private val adjustable = enabled && value != null && min != null && max != null && max > min

    /** The degrees of the fan, from the top: widens with the modes, and never wraps past the sides. */
    private val span = (30f * modes.size + 20f).coerceAtMost(170f)

    init { alpha = if (enabled) 1f else 0.5f }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = context.dp(4).toFloat()
        // The fan stands FAN times the radius above the dial; solve both sides for the radius.
        radius = minOf((w / 2f - pad) / (1 + FAN), (h - pad * 2) / (2 + FAN)).coerceAtLeast(0f)
        gap = radius * 0.08f
        thickness = radius * 0.32f
        raise = radius * 0.07f
        cx = w / 2f
        cy = pad + radius * FAN + radius
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        drawFan(canvas)
        drawDial(canvas)
    }

    private fun drawFan(canvas: Canvas) {
        if (modes.isEmpty()) return
        val each = span / modes.size
        modes.forEachIndexed { index, it ->
            val on = it == mode
            val from = -span / 2 + index * each + 1.2f
            val sweep = each - 2.4f
            val inner = radius + gap
            val outer = inner + thickness + if (on) raise else 0f
            path.reset()
            oval.set(cx - outer, cy - outer, cx + outer, cy + outer)
            path.arcTo(oval, from - 90f, sweep)
            oval.set(cx - inner, cy - inner, cx + inner, cy + inner)
            path.arcTo(oval, from - 90f + sweep, -sweep)
            path.close()
            segment.color = when {
                on -> ACCENT
                index == pressedMode -> SURFACE_ON
                else -> SURFACE
            }
            canvas.drawPath(path, segment)

            val middle = Math.toRadians((from + sweep / 2).toDouble())
            val at = inner + (outer - inner) / 2
            label.textSize = thickness * if (modes.size > 5) 0.34f else 0.40f
            label.color = if (on) ON_ACCENT else MUTED
            label.typeface = if (on) DISPLAY else null
            canvas.drawText(
                modeLabel(it),
                cx + sin(middle).toFloat() * at,
                cy - cos(middle).toFloat() * at - (label.descent() + label.ascent()) / 2,
                label,
            )
        }
    }

    private fun drawDial(canvas: Canvas) {
        val running = mode != "off" && mode != "unknown" && mode != "unavailable"
        fill.color = if (running) SURFACE_ON else SURFACE
        canvas.drawCircle(cx, cy, radius, fill)
        line.color = if (running) ACCENT else HAIRLINE_STRONG
        line.strokeWidth = context.dp(2).toFloat()
        canvas.drawCircle(cx, cy, radius - line.strokeWidth / 2, line)

        // The ring of ticks just inside the edge.
        line.strokeWidth = context.dp(1).toFloat()
        val outer = radius - context.dp(12)
        for (tick in 0 until 120) {
            val angle = Math.toRadians(tick * 3.0)
            val inner = outer - context.dp(if (tick % 10 == 0) 7 else 3)
            line.color = if (tick % 10 == 0) MUTED else HAIRLINE_STRONG
            canvas.drawLine(
                cx + sin(angle).toFloat() * inner, cy - cos(angle).toFloat() * inner,
                cx + sin(angle).toFloat() * outer, cy - cos(angle).toFloat() * outer, line,
            )
        }

        // The needle: from just inside the ticks toward the middle, stopping short of the number.
        val shown = dragging ?: value
        if (shown != null && min != null && max != null && max > min) {
            val angle = Math.toRadians(((shown - min) / (max - min) * 2 - 1) * SWEEP)
            line.color = if (running || dragging != null) ACCENT_INK else TEXT
            line.strokeWidth = context.dp(if (dragging != null) 3 else 2).toFloat()
            val from = outer - context.dp(10)
            val to = radius * 0.42f
            canvas.drawLine(
                cx + sin(angle).toFloat() * from, cy - cos(angle).toFloat() * from,
                cx + sin(angle).toFloat() * to, cy - cos(angle).toFloat() * to, line,
            )
        }

        label.typeface = null
        label.color = MUTED
        label.textSize = radius * 0.075f
        canvas.drawText(caption, cx, cy + radius * 0.02f, label)
        label.color = TEXT
        label.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        label.textSize = radius * 0.30f
        val number = shown?.let {
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it) + climate.unit
        } ?: "—"
        canvas.drawText(number, cx, cy + radius * 0.34f, label)
    }

    @SuppressLint("ClickableViewAccessibility") // The −/+ buttons beside it are the accessible path.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        val dx = event.x - cx
        val dy = event.y - cy
        val distance = hypot(dx, dy)
        // Degrees clockwise from the top, -180..180.
        val angle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedMode = fanSegment(angle, distance)
                if (pressedMode < 0 && adjustable && distance <= radius) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = at(angle)
                }
                invalidate()
                return pressedMode >= 0 || dragging != null
            }
            MotionEvent.ACTION_MOVE -> if (dragging != null) {
                dragging = at(angle)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging?.let { if (it != value) onTarget(it) }
                if (pressedMode >= 0 && pressedMode == fanSegment(angle, distance)) onMode(modes[pressedMode])
                dragging = null
                pressedMode = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                pressedMode = -1
                invalidate()
            }
        }
        return true
    }

    private fun at(angle: Float): Double =
        dialValue(((angle.coerceIn(-SWEEP.toFloat(), SWEEP.toFloat()) / SWEEP.toFloat()) + 1f) / 2f, min!!, max!!, climate.step)

    private fun fanSegment(angle: Float, distance: Float): Int {
        if (modes.isEmpty() || distance < radius + gap || distance > radius + gap + thickness + raise) return -1
        val index = ((angle + span / 2) / (span / modes.size)).toInt()
        return if (angle < -span / 2 || index !in modes.indices) -1 else index
    }

    private companion object {
        /** Degrees either side of the top that the needle can reach; the bottom is left clear. */
        const val SWEEP = 135.0
        /** gap + thickness + raise, as a fraction of the radius. */
        const val FAN = 0.47f
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
