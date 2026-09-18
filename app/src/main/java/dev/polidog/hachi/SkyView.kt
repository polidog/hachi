package dev.polidog.hachi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
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
 * reads as noon. Over that go the few marks that say what it is doing: cloud, rain, snow, lightning.
 *
 * Every mark is darker than the paper, not lighter. On a light backdrop a white cloud and a white
 * snowflake are the same colour as nothing at all -- the sky says what it is doing by what it casts,
 * which is also why there are no longer any stars: a star is light on dark and has nowhere to go.
 *
 * Over all that sits the emblem: the weather drawn as one large flat shape, half off the right edge
 * of the clock page -- a yellow sun, a moon, a cloud, a cloud with a bolt under it. It is what says
 * the weather from across the room, where drifting grey only says "something". It belongs to the
 * clock, so it slides away with that page as the pager turns ([emblemOffset]).
 *
 * By day the emblem hangs a little above the paper and casts a long flat shadow on it, the paper's
 * own colour taken darker -- the way a flat illustration seen from overhead shows the light. The
 * shadow swings and shortens with the hour ([castAt]), so it also says roughly what time it is.
 *
 * A clear day is completely static and is drawn once per minute. Everything else animates at 25 fps,
 * and only while the view is attached -- this is on screen all day on a slow tablet, so a still sky
 * should cost nothing.
 */
class SkyView(context: Context) : View(context) {
    private var scene = SkyScene.CLEAR
    private var isDay = true

    /** How far the pager has scrolled, in pixels: the emblem moves with the clock page. */
    var emblemOffset = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val emblemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudShape = Path()
    private val moonShape = Path()
    private val boltShape = Path()

    /** True while the emblem is being drawn a second time, as its shadow. */
    private var casting = false
    private var shadowColour = 0
    private val castMatrix = Matrix()

    private val sky = Paint().apply { isDither = true }
    private var skyMinute = -1
    private var skyScene: SkyScene? = null

    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * One unit-circle gradient, reused for every cloud lobe through a matrix.
     *
     * Flat ovals read as ovals, not cloud. Fading from the middle outwards is what makes a blob look
     * like weather, and building the gradient once keeps 25 fps from allocating shaders a frame.
     */
    private val cloudShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Color.rgb(0xC9, 0xC7, 0xC1), Color.argb(0xB0, 0xC9, 0xC7, 0xC1), Color.TRANSPARENT,
        ),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )

    /**
     * The same lobes in shadow, drawn slightly lower.
     *
     * Daylight comes from above, so a cloud is bright on top and heavy underneath; a uniformly flat
     * blob is the thing that reads as cotton wool.
     */
    private val cloudShadeShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(0xFF, 0x9A, 0x99, 0x93), Color.argb(0x8C, 0xA4, 0xA2, 0x9C), Color.TRANSPARENT),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val cloudMatrix = Matrix()
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint()
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var clouds = emptyList<Cloud>()
    private var drops = emptyList<Drop>()
    private var flakes = emptyList<Flake>()

    private val startedAt = System.currentTimeMillis()

    /** Seeded so the sky is laid out the same way across a redraw or a rotation, not reshuffled. */
    private val random = Random(20260917)

    /**
     * A still grain laid over the finished sky.
     *
     * Three gradient stops down a tablet band visibly and read as flat paint. A few percent of noise
     * breaks the bands up and is most of what makes the screen look like sky rather than a fill.
     */
    private val grainPaint = Paint().apply {
        val size = 96
        val pixels = IntArray(size * size) {
            val v = 0x60 + random.nextInt(0x60)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        shader = BitmapShader(
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
        alpha = 0x14
    }

    private class Cloud(
        val y: Float, val width: Float, val height: Float, val speed: Float, val offset: Float,
        val alpha: Int,
        /** The lumps this cloud is built from, in fractions of its own width and height. */
        val lobes: List<Lobe>,
    )
    private class Lobe(val dx: Float, val dy: Float, val rx: Float, val ry: Float)
    private class Drop(val x: Float, val offset: Float, val speed: Float, val length: Float, val width: Float, val alpha: Int)
    private class Flake(val x: Float, val offset: Float, val speed: Float, val radius: Float, val sway: Float, val phase: Float)

    private val frame = object : Runnable {
        override fun run() {
            invalidate()
            // 25 fps is plenty for drifting cloud and falling rain, and leaves the tablet alone. A
            // still sky is redrawn once a minute, which is how often its colour can change.
            postDelayed(this, if (animated) 40L else 60_000L)
        }
    }

    /** A clear sky has nothing moving on it at any hour, so it is drawn once and left alone. */
    private val animated: Boolean
        get() = scene != SkyScene.CLEAR

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
        removeCallbacks(frame)
        frame.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        skyMinute = -1 // the gradient is height-dependent
        populate(w, h)
        shapeEmblems(h.toFloat())
    }

    private fun populate(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val cloudCount = 7
        clouds = List(cloudCount) { index ->
            val lobeCount = 4 + random.nextInt(3)
            Cloud(
                // Kept to the top half: cloud sitting behind the clock is what dims it, and cloud
                // down at the horizon just looks like fog.
                y = h * (0.02f + random.nextFloat() * 0.34f),
                width = w * (0.34f + random.nextFloat() * 0.40f),
                height = h * (0.16f + random.nextFloat() * 0.14f),
                speed = w * (0.005f + random.nextFloat() * 0.009f),
                // Spread evenly with a jitter, so they never bunch up on one side of the screen.
                offset = (index + random.nextFloat() * 0.8f) / cloudCount,
                // Kept low: a cloud is a shadow on the paper, and anything solid reads as a stain.
                alpha = 0x14 + random.nextInt(0x14),
                lobes = List(lobeCount) { lobe ->
                    val t = (lobe + 0.5f) / lobeCount
                    // Fattest in the middle, thin at the ends: a cloud's silhouette, not a sausage.
                    val bulge = 1f - abs(t - 0.5f) * 1.6f
                    val rx = 0.13f + 0.16f * bulge + random.nextFloat() * 0.05f
                    val ry = 0.26f + 0.34f * bulge + random.nextFloat() * 0.10f
                    // Bottoms roughly level: cloud sits on its base and piles up on top.
                    Lobe(t, 1f - ry * (0.95f + random.nextFloat() * 0.10f), rx, ry)
                },
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
            SkyScene.CLEAR -> Unit
            SkyScene.PARTLY_CLOUDY -> drawClouds(canvas, seconds, 0.55f)
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

        drawEmblem(canvas)
        canvas.drawPaint(grainPaint)
    }

    /**
     * The shapes, built once per size around the origin, in units of the emblem's radius [h] * 0.4.
     *
     * A cloud is three circles on a flat base -- the silhouette a child draws, which is exactly the
     * point: it has to be read in half a second from the sofa.
     */
    private fun shapeEmblems(h: Float) {
        val r = h * EMBLEM
        fun circle(x: Float, y: Float, radius: Float) = Path().apply { addCircle(x * r, y * r, radius * r, Path.Direction.CW) }
        cloudShape.reset()
        cloudShape.addRoundRect(-1.05f * r, 0f, 1.05f * r, 0.55f * r, 0.275f * r, 0.275f * r, Path.Direction.CW)
        cloudShape.op(circle(-0.5f, 0.12f, 0.43f), Path.Op.UNION)
        cloudShape.op(circle(0.1f, -0.12f, 0.62f), Path.Op.UNION)
        cloudShape.op(circle(0.65f, 0.14f, 0.40f), Path.Op.UNION)
        moonShape.reset()
        moonShape.addCircle(0f, 0f, 0.78f * r, Path.Direction.CW)
        moonShape.op(circle(-0.42f, -0.26f, 0.70f), Path.Op.DIFFERENCE)
        boltShape.reset()
        boltShape.moveTo(0.02f * r, 0.62f * r)
        boltShape.lineTo(-0.30f * r, 1.18f * r)
        boltShape.lineTo(-0.04f * r, 1.18f * r)
        boltShape.lineTo(-0.22f * r, 1.62f * r)
        boltShape.lineTo(0.30f * r, 1.00f * r)
        boltShape.lineTo(0.04f * r, 1.00f * r)
        boltShape.lineTo(0.22f * r, 0.62f * r)
        boltShape.close()
    }

    private fun drawEmblem(canvas: Canvas) {
        val r = height * EMBLEM
        val cx = width * 0.86f - emblemOffset
        if (cx + r * 1.2f < 0f || r <= 0f) return
        val cy = height * 0.40f
        canvas.save()
        canvas.translate(cx, cy)
        // A shadow on black is nothing, so by night the emblem stands alone.
        if (!Theme.night) {
            val now = LocalDateTime.now()
            val cast = castAt(now.hour * 60 + now.minute)
            val angle = Math.toRadians(cast.angle.toDouble())
            castMatrix.setRotate(-cast.angle)
            castMatrix.postScale(cast.stretch, 1f)
            castMatrix.postRotate(cast.angle)
            castMatrix.postTranslate(
                (kotlin.math.cos(angle) * cast.reach * r).toFloat(),
                (kotlin.math.sin(angle) * cast.reach * r).toFloat(),
            )
            canvas.save()
            canvas.concat(castMatrix)
            casting = true
            shapes(canvas)
            casting = false
            canvas.restore()
        }
        shapes(canvas)
        canvas.restore()
    }

    private fun shapes(canvas: Canvas) {
        val r = height * EMBLEM
        when (scene) {
            SkyScene.CLEAR -> body(canvas)
            SkyScene.PARTLY_CLOUDY -> {
                body(canvas)
                cloud(canvas, CLOUD_LIGHT, -0.55f, 0.45f, 0.75f)
            }
            SkyScene.CLOUDY -> {
                cloud(canvas, CLOUD_BACK, 0.35f, -0.35f, 0.7f)
                cloud(canvas, CLOUD_GREY, -0.15f, 0.1f, 1f)
            }
            SkyScene.FOG -> for (band in 0..2) {
                tone(CLOUD_GREY)
                val y = (band - 1) * 0.42f * r
                val inset = if (band == 1) 0f else 0.25f * r
                canvas.drawRoundRect(-1.1f * r + inset, y - 0.12f * r, 1.1f * r, y + 0.12f * r, 0.12f * r, 0.12f * r, emblemPaint)
            }
            SkyScene.RAIN -> {
                // Short strokes under the cloud, kept to its left half: the weekday sits under the right.
                tone(RAIN_MARK)
                emblemPaint.strokeWidth = 0.07f * r
                emblemPaint.strokeCap = Paint.Cap.ROUND
                for ((x, y) in MARKS) canvas.drawLine(x * r, y * r, (x - 0.1f) * r, (y + 0.28f) * r, emblemPaint)
                cloud(canvas, CLOUD_DARK, 0f, -0.1f, 1f)
            }
            SkyScene.SNOW -> {
                tone(SNOW_MARK)
                for ((x, y) in MARKS) canvas.drawCircle(x * r, (y + 0.1f) * r, 0.09f * r, emblemPaint)
                cloud(canvas, CLOUD_GREY, 0f, -0.1f, 1f)
            }
            SkyScene.THUNDER -> {
                tone(ACCENT)
                canvas.save()
                canvas.translate(-0.55f * r, -0.15f * r)
                canvas.scale(0.85f, 0.85f)
                canvas.drawPath(boltShape, emblemPaint)
                canvas.restore()
                cloud(canvas, CLOUD_STORM, 0f, -0.1f, 1f)
            }
        }
    }

    /** The sun by day, the moon by night: whichever is up behind the cloud, if there is one. */
    private fun body(canvas: Canvas) {
        val r = height * EMBLEM
        if (isDay) {
            tone(ACCENT)
            canvas.drawCircle(0f, 0f, r, emblemPaint)
        } else {
            // Lifted clear of the weekday, which by night is light type and would vanish on it.
            tone(MOON)
            canvas.save()
            canvas.translate(0f, -0.3f * r)
            canvas.drawPath(moonShape, emblemPaint)
            canvas.restore()
        }
    }

    /**
     * Sets the emblem's colour, held back by night: full-strength shapes on black are the brightest
     * thing in a dark room, and the light type laid over them would be lost.
     */
    private fun tone(colour: Int) {
        // Opaque, so where a cloud's shadow overlaps the sun's it stays one shadow, not two.
        emblemPaint.color = if (casting) shadowColour else colour
        if (Theme.night) emblemPaint.alpha = 0x80
    }

    private fun cloud(canvas: Canvas, colour: Int, x: Float, y: Float, scale: Float) {
        val r = height * EMBLEM
        tone(colour)
        canvas.save()
        canvas.translate(x * r, y * r)
        canvas.scale(scale, scale)
        canvas.drawPath(cloudShape, emblemPaint)
        canvas.restore()
    }

    private companion object {
        /** The emblem's radius, as a fraction of the screen's height. */
        const val EMBLEM = 0.40f
        /** How much darker than the paper the emblem's shadow is. */
        const val SHADE = 0.92f
        val MOON = Color.rgb(0xF6, 0xF2, 0xE2)
        /** A fair-weather cloud, lighter than the paper so it reads against the sun behind it. */
        val CLOUD_LIGHT = Color.rgb(0xFA, 0xF9, 0xF6)
        val CLOUD_BACK = Color.rgb(0xD9, 0xD7, 0xD1)
        val CLOUD_GREY = Color.rgb(0xC6, 0xC4, 0xBE)
        val CLOUD_DARK = Color.rgb(0x9C, 0xA3, 0xAA)
        val CLOUD_STORM = Color.rgb(0x7A, 0x7C, 0x80)
        val RAIN_MARK = Color.rgb(0x7F, 0x95, 0xAA)
        val SNOW_MARK = Color.rgb(0xAE, 0xB9, 0xC2)
        /** Where the drops and flakes fall from, under the cloud's left half, in emblem radii. */
        val MARKS = listOf(-0.85f to 0.7f, -0.5f to 0.85f, -0.15f to 0.7f, -0.68f to 1.2f, -0.32f to 1.3f)
    }

    private fun drawSky(canvas: Canvas, minuteOfDay: Int) {
        if (minuteOfDay != skyMinute || scene != skyScene || sky.shader == null) {
            skyMinute = minuteOfDay
            skyScene = scene
            val palette = washed(DayPalette.at(minuteOfDay), scene)
            shadowColour = wash(palette.middle, 0f, SHADE)
            sky.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(palette.top, palette.middle, palette.bottom),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
    }

    private fun drawClouds(canvas: Canvas, seconds: Float, density: Float) {
        val shown = (clouds.size * density).toInt().coerceAtLeast(1)
        for (cloud in clouds.take(shown)) {
            val span = width + cloud.width * 2f
            // Drifts right, reappearing on the left; the offset keeps them from moving as a block.
            val x = ((cloud.offset * span + seconds * cloud.speed) % span) - cloud.width
            val alpha = (cloud.alpha * density).toInt()
            // The shaded underside first, then the lit lobes over it, so the cloud has a top and a
            // bottom rather than being one even smear. Overlapping lobes thicken where they meet,
            // which is the mottling a single oval can't do.
            cloudPaint.shader = cloudShadeShader
            cloudPaint.alpha = (alpha * 0.55f).toInt()
            for (lobe in cloud.lobes) {
                blob(canvas, cloudShadeShader, cloud, x, lobe, lift = 0.12f)
            }
            cloudPaint.shader = cloudShader
            cloudPaint.alpha = alpha
            for (lobe in cloud.lobes) {
                blob(canvas, cloudShader, cloud, x, lobe, lift = 0f)
            }
        }
        cloudPaint.shader = null
    }

    private fun blob(canvas: Canvas, shader: RadialGradient, cloud: Cloud, x: Float, lobe: Lobe, lift: Float) {
        val cx = x + lobe.dx * cloud.width
        val cy = cloud.y + (lobe.dy + lift) * cloud.height
        val rx = lobe.rx * cloud.width
        val ry = lobe.ry * cloud.height
        cloudMatrix.setScale(rx, ry)
        cloudMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(cloudMatrix)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, cloudPaint)
    }

    private fun drawRain(canvas: Canvas, seconds: Float) {
        val span = height + 80f
        for (drop in drops) {
            val y = ((drop.offset * span + seconds * drop.speed) % span) - 40f
            rainPaint.color = Color.argb(drop.alpha, 0x5C, 0x72, 0x8A)
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
            snowPaint.color = Color.argb(0x8C, 0x9E, 0xA9, 0xB2)
            canvas.drawCircle(x, y, flake.radius, snowPaint)
        }
    }

    private fun drawFog(canvas: Canvas, seconds: Float) {
        // Two slow, wide bands sliding past each other, rather than a flat veil.
        for (band in 0..1) {
            val drift = sin(seconds * 0.06f + band * 2.1f) * width * 0.08f
            fogPaint.color = Color.argb(0x22, 0x9B, 0xA1, 0xA6)
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
        flashPaint.color = Color.argb((0xA0 * strength).toInt(), 0xFF, 0xFF, 0xF2)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
    }
}
