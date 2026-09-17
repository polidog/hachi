package dev.polidog.hachi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Home page 2: the rain radar over a map, centred on the configured place.
 *
 * Tiles are drawn at their native size rather than scaled to the display's density. The default zoom
 * is deliberately wide: at zoom 9 the whole viewport around Miyazaki came back as empty tiles while
 * a rain band sat just outside it, which is exactly the thing this page exists to show. Tapping
 * cycles in for detail.
 *
 * Only the current frame is shown. The nowcast also publishes the past and the next hour at
 * five-minute steps, which would animate; that means holding twenty-odd frames' worth of tiles, so
 * it waits until someone wants it.
 */
class RadarPage(context: Context) : View(context) {
    private val tiles = RadarTiles(context)
    private val main = Handler(Looper.getMainLooper())

    private var place: Place? = null
    private var base = mutableMapOf<Pair<Int, Int>, Bitmap>()
    private var rain = mutableMapOf<Pair<Int, Int>, Bitmap>()
    private var loading = false
    private var failed = false
    private var zoom = DEFAULT_ZOOM

    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0x5A, 0x4A) }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
    }
    private val credit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB3, 0x20, 0x20, 0x20)
        textSize = context.dp(9).toFloat()
    }
    private val creditBackdrop = Paint().apply { color = Color.argb(0x99, 0xFF, 0xFF, 0xFF) }
    private val message = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM_60
        textSize = context.dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        setOnClickListener {
            zoom = if (zoom >= MAX_ZOOM) DEFAULT_ZOOM else zoom + 1
            base.clear()
            rain.clear()
            invalidate()
            load()
        }
    }

    /** Called when the place changes, and on every weather refresh. */
    fun bind(state: WeatherState) {
        if (state.place != place) {
            place = state.place
            base.clear()
            rain.clear()
        }
        load()
    }

    private fun load() {
        val centre = place ?: return
        if (loading || width == 0) return
        loading = true
        val level = zoom
        thread(name = "hachi-radar") {
            val frame = tiles.latestFrame()
            val fetchedBase = mutableMapOf<Pair<Int, Int>, Bitmap>()
            val fetchedRain = mutableMapOf<Pair<Int, Int>, Bitmap>()
            for ((x, y) in visibleTiles(centre, level)) {
                val key = x to y
                // The base map is immutable, so it is only fetched once per place; OkHttp's cache
                // means even that usually does not leave the device.
                (base[key] ?: tiles.base(level, x, y))?.let { fetchedBase[key] = it }
                if (frame != null) tiles.rain(frame, level, x, y)?.let { fetchedRain[key] = it }
            }
            main.post {
                base = fetchedBase
                rain = fetchedRain
                failed = frame == null || fetchedBase.isEmpty()
                loading = false
                invalidate()
            }
        }
    }

    /** Every tile touching the viewport, with the place at its centre. */
    private fun visibleTiles(centre: Place, level: Int): List<Pair<Int, Int>> {
        val cx = tileX(centre.longitude, level)
        val cy = tileY(centre.latitude, level)
        val halfX = width / 2.0 / TILE
        val halfY = height / 2.0 / TILE
        val from = floor(cx - halfX).toInt() to floor(cy - halfY).toInt()
        val to = ceil(cx + halfX).toInt() to ceil(cy + halfY).toInt()
        return buildList {
            for (x in from.first..to.first) for (y in from.second..to.second) add(x to y)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        load() // the tile count depends on the viewport
    }

    override fun onDraw(canvas: Canvas) {
        val centre = place
        if (centre == null) {
            canvas.drawText(
                context.getString(R.string.weather_no_place),
                width / 2f,
                height / 2f,
                message,
            )
            return
        }
        if (base.isEmpty()) {
            canvas.drawText(
                context.getString(if (failed) R.string.radar_unavailable else R.string.radar_loading),
                width / 2f,
                height / 2f,
                message,
            )
            return
        }

        val cx = tileX(centre.longitude, zoom)
        val cy = tileY(centre.latitude, zoom)
        fun left(x: Int) = (width / 2.0 + (x - cx) * TILE).toFloat()
        fun top(y: Int) = (height / 2.0 + (y - cy) * TILE).toFloat()

        for ((key, bitmap) in base) canvas.drawBitmap(bitmap, left(key.first), top(key.second), null)
        for ((key, bitmap) in rain) canvas.drawBitmap(bitmap, left(key.first), top(key.second), null)

        // Where the forecast is actually for.
        canvas.drawCircle(width / 2f, height / 2f, dp(5).toFloat(), marker)
        canvas.drawCircle(width / 2f, height / 2f, dp(5).toFloat(), markerRing)

        // Both sources require attribution.
        val text = context.getString(R.string.radar_credit)
        val textWidth = credit.measureText(text)
        val pad = dp(4).toFloat()
        canvas.drawRect(
            width - textWidth - pad * 2, height - credit.textSize - pad * 2,
            width.toFloat(), height.toFloat(), creditBackdrop,
        )
        canvas.drawText(text, width - textWidth - pad, height - pad, credit)
    }

    private fun dp(value: Int) = context.dp(value)

    private companion object {
        /** About 500 km across on this screen: wide enough to see weather that has not arrived yet. */
        const val DEFAULT_ZOOM = 8
        const val MAX_ZOOM = 10
        const val TILE = 256
    }
}
