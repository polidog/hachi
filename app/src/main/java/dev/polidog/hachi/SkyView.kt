package dev.polidog.hachi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import android.view.View
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The backdrop every page sits on: the sky at this time of day, in this weather.
 *
 * The time of day sets the colour and the weather drains and dims it, so an overcast noon still
 * reads as noon. Over that go the few marks that say what it is doing: stars, cloud, rain, snow,
 * lightning.
 *
 * A clear day is completely static and is drawn once per minute. Everything else animates at 25 fps,
 * and only while the view is attached -- this is on screen all day on a slow tablet, so a still sky
 * should cost nothing.
 */
class SkyView(context: Context) : View(context) {
    private var scene = SkyScene.CLEAR
    private var isDay = true

    private val sky = Paint()
    private var skyMinute = -1
    private var skyScene: SkyScene? = null

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * One unit-circle gradient, reused for every cloud through a matrix.
     *
     * Flat ovals read as ovals, not cloud. Fading from the middle outwards is what makes a blob look
     * like weather, and building the gradient once keeps 25 fps from allocating six shaders a frame.
     */
    private val cloudShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.WHITE, Color.argb(0xB0, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val cloudMatrix = Matrix()
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint()
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var stars = emptyList<Star>()
    private var clouds = emptyList<Cloud>()
    private var drops = emptyList<Drop>()
    private var flakes = emptyList<Flake>()

    private val startedAt = System.currentTimeMillis()

    /** Seeded so the sky is laid out the same way across a redraw or a rotation, not reshuffled. */
    private val random = Random(20260917)

    private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float)
    private class Cloud(val y: Float, val width: Float, val height: Float, val speed: Float, val offset: Float, val alpha: Int)
    private class Drop(val x: Float, val offset: Float, val speed: Float, val length: Float, val width: Float, val alpha: Int)
    private class Flake(val x: Float, val offset: Float, val speed: Float, val radius: Float, val sway: Float, val phase: Float)

    private val frame = object : Runnable {
        override fun run() {
            invalidate()
            // 25 fps is plenty for drifting cloud and falling rain, and leaves the tablet alone.
            if (animated) postDelayed(this, 40L)
        }
    }

    private val animated: Boolean
        get() = scene != SkyScene.CLEAR || !isDay // a clear night still has stars to twinkle

    /**
     * Called with each weather refresh; null leaves the sky clear. [override] forces a scene, for
     * checking how one looks without waiting for that weather to turn up.
     */
    fun bind(forecast: Forecast?, override: SkyScene? = null) {
        val next = override ?: forecast?.let { sceneOf(it.code) } ?: SkyScene.CLEAR
        val nextIsDay = forecast?.isDay ?: true
        Log.i("Hachi", "sky: code=${forecast?.code} scene=$next day=$nextIsDay override=$override")
        if (next == scene && nextIsDay == isDay) return
        scene = next
        isDay = nextIsDay
        skyScene = null // the wash changed, so the gradient has to be rebuilt
        removeCallbacks(frame)
        frame.run()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frame.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        skyMinute = -1 // the gradient is height-dependent
        populate(w, h)
    }

    private fun populate(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        stars = List(46) {
            Star(
                x = random.nextFloat() * w,
                // Kept to the upper two thirds; stars at ground level look like dust on the screen.
                y = random.nextFloat() * h * 0.66f,
                radius = 0.7f + random.nextFloat() * 1.3f,
                phase = random.nextFloat() * 6.28f,
            )
        }
        val cloudCount = 7
        clouds = List(cloudCount) { index ->
            Cloud(
                // Kept to the top half: cloud sitting behind the clock is what dims it, and cloud
                // down at the horizon just looks like fog.
                y = h * (0.02f + random.nextFloat() * 0.34f),
                width = w * (0.34f + random.nextFloat() * 0.40f),
                height = h * (0.16f + random.nextFloat() * 0.14f),
                speed = w * (0.005f + random.nextFloat() * 0.009f),
                // Spread evenly with a jitter, so they never bunch up on one side of the screen.
                offset = (index + random.nextFloat() * 0.8f) / cloudCount,
                alpha = 0x16 + random.nextInt(0x1C),
            )
        }
        // Three depths: near drops fall faster, longer and more solid than far ones.
        drops = List(84) {
            val depth = random.nextInt(3)
            Drop(
                x = random.nextFloat() * (w * 1.15f),
                offset = random.nextFloat(),
                speed = h * (0.9f + depth * 0.55f),
                length = h * (0.045f + depth * 0.022f),
                width = 1f + depth * 0.7f,
                alpha = 0x40 + depth * 0x28,
            )
        }
        flakes = List(58) {
            Flake(
                x = random.nextFloat() * w,
                offset = random.nextFloat(),
                speed = h * (0.05f + random.nextFloat() * 0.06f),
                radius = 1.3f + random.nextFloat() * 2.2f,
                sway = w * (0.01f + random.nextFloat() * 0.02f),
                phase = random.nextFloat() * 6.28f,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = LocalDateTime.now()
        drawSky(canvas, now.hour * 60 + now.minute)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000f

        when (scene) {
            SkyScene.CLEAR -> if (!isDay) drawStars(canvas, seconds)
            SkyScene.PARTLY_CLOUDY -> {
                if (!isDay) drawStars(canvas, seconds)
                drawClouds(canvas, seconds, 0.55f)
            }
            SkyScene.CLOUDY -> drawClouds(canvas, seconds, 1f)
            SkyScene.FOG -> {
                drawClouds(canvas, seconds, 0.7f)
                drawFog(canvas, seconds)
            }
            SkyScene.RAIN -> {
                drawClouds(canvas, seconds, 1f)
                drawRain(canvas, seconds)
            }
            SkyScene.SNOW -> {
                drawClouds(canvas, seconds, 0.85f)
                drawSnow(canvas, seconds)
            }
            SkyScene.THUNDER -> {
                drawClouds(canvas, seconds, 1f)
                drawRain(canvas, seconds)
                drawLightning(canvas, seconds)
            }
        }
    }

    private fun drawSky(canvas: Canvas, minuteOfDay: Int) {
        if (minuteOfDay != skyMinute || scene != skyScene || sky.shader == null) {
            skyMinute = minuteOfDay
            skyScene = scene
            val palette = washed(DayPalette.at(minuteOfDay), scene)
            sky.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(palette.top, palette.middle, palette.bottom),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
    }

    private fun drawStars(canvas: Canvas, seconds: Float) {
        for (star in stars) {
            val twinkle = 0.45f + 0.55f * abs(sin(seconds * 0.9f + star.phase))
            starPaint.color = Color.argb((0xB4 * twinkle).toInt(), 0xFF, 0xFB, 0xF0)
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }
    }

    private fun drawClouds(canvas: Canvas, seconds: Float, density: Float) {
        val shown = (clouds.size * density).toInt().coerceAtLeast(1)
        cloudPaint.shader = cloudShader
        for (cloud in clouds.take(shown)) {
            val span = width + cloud.width * 2f
            // Drifts right, reappearing on the left; the offset keeps them from moving as a block.
            val x = ((cloud.offset * span + seconds * cloud.speed) % span) - cloud.width
            cloudPaint.alpha = (cloud.alpha * density).toInt()
            // Two overlapping lobes: one blob alone is a circle, two make a cloud.
            blob(canvas, x + cloud.width * 0.5f, cloud.y + cloud.height * 0.5f, cloud.width * 0.5f, cloud.height * 0.5f)
            blob(canvas, x + cloud.width * 0.78f, cloud.y + cloud.height * 0.62f, cloud.width * 0.36f, cloud.height * 0.40f)
        }
        cloudPaint.shader = null
    }

    private fun blob(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        cloudMatrix.setScale(rx, ry)
        cloudMatrix.postTranslate(cx, cy)
        cloudShader.setLocalMatrix(cloudMatrix)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, cloudPaint)
    }

    private fun drawRain(canvas: Canvas, seconds: Float) {
        val span = height + 80f
        for (drop in drops) {
            val y = ((drop.offset * span + seconds * drop.speed) % span) - 40f
            rainPaint.color = Color.argb(drop.alpha, 0xD6, 0xE6, 0xF5)
            rainPaint.strokeWidth = drop.width
            // A consistent lean reads as wind; vertical rain looks like a test pattern.
            canvas.drawLine(drop.x, y, drop.x - drop.length * 0.18f, y + drop.length, rainPaint)
        }
    }

    private fun drawSnow(canvas: Canvas, seconds: Float) {
        val span = height + 30f
        for (flake in flakes) {
            val y = ((flake.offset * span + seconds * flake.speed) % span) - 15f
            val x = flake.x + sin(seconds * 0.7f + flake.phase) * flake.sway
            snowPaint.color = Color.argb(0xC8, 0xFF, 0xFF, 0xFF)
            canvas.drawCircle(x, y, flake.radius, snowPaint)
        }
    }

    private fun drawFog(canvas: Canvas, seconds: Float) {
        // Two slow, wide bands sliding past each other, rather than a flat veil.
        for (band in 0..1) {
            val drift = sin(seconds * 0.06f + band * 2.1f) * width * 0.08f
            fogPaint.color = Color.argb(0x22, 0xEC, 0xEF, 0xF2)
            val top = height * (0.30f + band * 0.26f)
            canvas.drawOval(-width * 0.2f + drift, top, width * 1.2f + drift, top + height * 0.36f, fogPaint)
        }
    }

    private fun drawLightning(canvas: Canvas, seconds: Float) {
        // Two quick flashes about seven seconds apart, the second dimmer, like a real strike.
        val phase = seconds % 7f
        val strength = when {
            phase < 0.10f -> 1f - phase / 0.10f
            phase in 0.22f..0.34f -> (1f - (phase - 0.22f) / 0.12f) * 0.5f
            else -> 0f
        }
        if (strength <= 0f) return
        flashPaint.color = Color.argb((0x66 * strength).toInt(), 0xFF, 0xFF, 0xF2)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
    }
}
