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
import kotlin.random.Random

/**
 * The face of a conversation: a dark sphere with a few bright shapes turning over inside it, and a
 * green rim lit from below.
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

    // ColorMatrixColorFilter cannot be changed once built, so only the matrix is kept; a filter is
    // built per frame, but only during a flare, which is a few seconds a minute.
    private val heatMatrix = ColorMatrix()
    private var flareAt = -100f
    private var nextFlare = FIRST_FLARE

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
        val r = min(height * 0.26f, width * 0.20f) * (1f + 0.035f * sin(t * 1.1f))
        if (r <= 0f) return

        val heat = heat(t)
        val filter = tint(heat)
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

        // The green edge of the reference: one arc low on the left, drifting a little.
        if (blurred != r) {
            rim.maskFilter = BlurMaskFilter(r * 0.10f, BlurMaskFilter.Blur.NORMAL)
            blurred = r
        }
        rim.strokeWidth = r * 0.07f
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 112f + 14f * sin(t * 0.5f), 96f, false, rim)
    }

    /**
     * How far into a flare it is: 0 while calm, 1 at the height of one.
     *
     * Every so often the whole thing goes red for a few seconds and comes back. Nothing means it --
     * it is the look, not a state. ponytail: if it should ever mean something (a tool touching a
     * lock, a session that failed), hand the flare in from outside instead of rolling for it here.
     */
    private fun heat(t: Float): Float {
        if (t >= nextFlare) {
            flareAt = t
            nextFlare = t + GAP_MIN + Random.nextFloat() * (GAP_MAX - GAP_MIN)
        }
        val since = t - flareAt
        if (since < 0f || since > FLARE_SECONDS) return 0f
        // In and back out over the length of the flare, so neither edge is a cut.
        return sin(Math.PI.toFloat() * since / FLARE_SECONDS)
    }

    /**
     * Everything the orb is made of, pushed towards red by [heat].
     *
     * The red is the picture's own brightness moved into the red channel, so the bright shapes stay
     * bright and the dark body stays dark -- a red version of the same orb, not a red disc.
     */
    private fun tint(heat: Float): ColorMatrixColorFilter? {
        if (heat <= 0.01f) return null
        heatMatrix.set(
            floatArrayOf(
                lerp(1f, 0.40f, heat), lerp(0f, 0.79f, heat), lerp(0f, 0.15f, heat), 0f, 0f,
                lerp(0f, 0.045f, heat), lerp(1f, 0.088f, heat), lerp(0f, 0.017f, heat), 0f, 0f,
                lerp(0f, 0.036f, heat), lerp(0f, 0.070f, heat), lerp(1f, 0.014f, heat), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        return ColorMatrixColorFilter(heatMatrix)
    }

    private fun lerp(from: Float, to: Float, k: Float) = from + (to - from) * k

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
        /** Seconds a flare lasts, and how long apart they fall. Long enough to be a surprise. */
        const val FLARE_SECONDS = 3.2f
        const val FIRST_FLARE = 18f
        const val GAP_MIN = 25f
        const val GAP_MAX = 70f
    }
}
