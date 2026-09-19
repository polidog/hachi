package dev.polidog.hachi

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The face of a conversation: a dark sphere with a few bright shapes turning over inside it, and a
 * rim lit from below.
 *
 * Its colour never holds still. The whole orb wanders round the colour wheel, slowly enough that no
 * one moment looks like a change: blue, through green, to red and back -- see [hue].
 *
 * Nothing here is measured -- it does not follow the voice. All it has to say is that Hachi is awake
 * and the room is being listened to, and a slow restless wobble says that on its own.
 * ponytail: the amplitude is a constant. Feed it the mic peak and [SpeakerStream.busy] if the orb
 * should breathe with what is actually being said.
 */
class OrbView(context: Context) : View(context) {
    /** Where a blob sits and how fast it goes round: fractions of the sphere's radius. */
    private class Blob(val angle: Float, val distance: Float, val radius: Float, val speed: Float)

    private val blobs = listOf(
        Blob(0.6f, 0.30f, 0.46f, 0.23f),
        Blob(2.7f, 0.44f, 0.29f, -0.17f),
        Blob(4.6f, 0.24f, 0.20f, 0.31f),
    )

    // One unit-circle gradient each, moved into place by a matrix -- 25 fps must not allocate a
    // shader per blob per frame (the same trick SkyView plays with its clouds).
    private val glowShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(0x5C, 0xC8, 0xF2, 0x4E), Color.argb(0x1F, 0x3C, 0x5C, 0x14), Color.TRANSPARENT),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val bodyShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.rgb(0x35, 0x50, 0x14), Color.rgb(0x18, 0x26, 0x08), Color.rgb(0x05, 0x07, 0x03)),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val blobShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.rgb(0xEC, 0xFF, 0xC8), Color.argb(0xE0, 0xC8, 0xF2, 0x4E), Color.argb(0x00, 0x6A, 0x8C, 0x24)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowShader }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bodyShader }
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = blobShader }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(0xCC, 0xC8, 0xF2, 0x4E)
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * One filter per whole degree, built the first time that degree comes round.
     *
     * ColorMatrixColorFilter cannot be changed once built, and the drift moves a degree every few
     * frames; a few hundred small objects kept for the life of the view beat one a frame at 25 fps.
     */
    private val hues = arrayOfNulls<ColorMatrixColorFilter>(360)

    private val matrix = Matrix()
    private val outline = Path()
    private val shape = Path()
    private var blurred = 0f
    private val born = System.currentTimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 40L) // 25 fps, same as the sky
        }
    }

    /** Only animate while something is actually looking at it; this screen is on all day. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible) tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.currentTimeMillis() - born) / 1000f
        val cx = width / 2f
        // High and small enough that the captions underneath keep their own room; the view itself
        // is the whole overlay, because the glow has to be allowed to spill past the sphere.
        val cy = height * 0.34f
        val still = min(height * 0.26f, width * 0.20f)
        val r = still * (1f + 0.035f * sin(t * 1.1f))
        if (r <= 0f) return

        val filter = tint(hue(t))
        glow.colorFilter = filter
        body.colorFilter = filter
        blob.colorFilter = filter
        rim.colorFilter = filter

        fill(glow, glowShader, cx, cy, r * 1.6f)
        canvas.drawCircle(cx, cy, r * 1.6f, glow)
        // Lit from the upper left, so the gradient's middle is not the circle's middle.
        fill(body, bodyShader, cx - r * 0.22f, cy - r * 0.26f, r * 1.35f)
        canvas.drawCircle(cx, cy, r, body)

        canvas.save()
        outline.reset()
        outline.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(outline)
        blobs.forEachIndexed { index, b ->
            val a = b.angle + t * b.speed
            val bx = cx + r * b.distance * cos(a)
            val by = cy + r * b.distance * sin(a) * 0.8f // the sphere is read from slightly above
            val br = r * b.radius
            wobble(bx, by, br, t, index * 1.7f)
            fill(blob, blobShader, bx, by, br * 1.25f)
            canvas.drawPath(shape, blob)
        }
        canvas.restore()

        // The lit edge of the reference: one arc low on the left, drifting a little. The blur is
        // sized off the still radius: keyed on the breathing one it was rebuilt every frame.
        if (blurred != still) {
            rim.maskFilter = BlurMaskFilter(still * 0.10f, BlurMaskFilter.Blur.NORMAL)
            blurred = still
        }
        rim.strokeWidth = r * 0.07f
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 112f + 14f * sin(t * 0.5f), 96f, false, rim)
    }

    /**
     * Degrees to turn the orb's own lime round the colour wheel at [t] seconds.
     *
     * Two slow waves at unrelated periods, so the wander never settles into a beat you can count.
     * Between them they reach from +135 (the lime turned blue, as in the picture) to -80 (turned
     * red), passing through the green it was drawn in -- and never round the far side of the wheel,
     * where the purples and magentas are.
     */
    private fun hue(t: Float): Int {
        val wander = 0.72f * sin(TAU * t / 53f) + 0.28f * sin(TAU * t / 19f + 1.3f)
        return (27.5f + 107.5f * wander).toInt()
    }

    /**
     * The luminance-keeping hue rotation (SVG's feHueRotate), so a turned orb keeps its lights and
     * its darks where they were: the bright shapes stay bright, the body stays dark.
     */
    private fun tint(degrees: Int): ColorMatrixColorFilter? {
        if (degrees == 0) return null
        val slot = Math.floorMod(degrees, 360)
        hues[slot]?.let { return it }
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val n = sin(rad).toFloat()
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.213f + c * 0.787f - n * 0.213f, 0.715f - c * 0.715f - n * 0.715f, 0.072f - c * 0.072f + n * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + n * 0.143f, 0.715f + c * 0.285f + n * 0.140f, 0.072f - c * 0.072f - n * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - n * 0.787f, 0.715f - c * 0.715f + n * 0.715f, 0.072f + c * 0.928f + n * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))).also { hues[slot] = it }
    }

    /** Points the unit-circle [shader] at a circle of [radius] centred on ([cx], [cy]). */
    private fun fill(paint: Paint, shader: RadialGradient, cx: Float, cy: Float, radius: Float) {
        matrix.setScale(radius, radius)
        matrix.postTranslate(cx, cy)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
    }

    /**
     * A closed blob whose edge is pushed in and out by two waves at once.
     *
     * One wave alone reads as a spinning triangle; the second, at a different rate and going the
     * other way, is what stops the eye finding the beat.
     */
    private fun wobble(cx: Float, cy: Float, radius: Float, t: Float, seed: Float) {
        shape.reset()
        var a = 0f
        while (a < TAU) {
            val k = 1f + 0.24f * sin(3f * a + t * 1.4f + seed) + 0.14f * sin(5f * a - t * 0.9f + seed * 2f)
            val x = cx + radius * k * cos(a)
            val y = cy + radius * k * sin(a)
            if (a == 0f) shape.moveTo(x, y) else shape.lineTo(x, y)
            a += STEP
        }
        shape.close()
    }

    private companion object {
        const val TAU = (2 * Math.PI).toFloat()
        const val STEP = TAU / 64f
    }
}
