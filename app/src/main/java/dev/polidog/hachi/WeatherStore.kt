package dev.polidog.hachi

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** What the weather page draws: the forecast, the rain radar, and whether the last fetch failed. */
data class WeatherState(
    val place: Place?,
    val forecast: Forecast?,
    val rain: List<RainPoint>,
    val failed: Boolean,
)

/**
 * Keeps one copy of the weather for the whole app, refreshed on a timer while the screen is up.
 *
 * Both services are polled together, since the page shows them side by side. Refreshing stops when
 * the activity pauses: a wall display left alone would otherwise poll all night for nobody.
 */
class WeatherStore(context: Context, private val settings: Settings) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((WeatherState) -> Unit)? = null
    private var running = false

    var state = WeatherState(null, null, emptyList(), false)
        private set

    fun start(onUpdate: (WeatherState) -> Unit) {
        listener = onUpdate
        onUpdate(state)
        if (running) return
        running = true
        tick.run()
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        listener = null
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    private fun refresh() {
        val place = settings.weatherPlace
        if (place == null) {
            update(WeatherState(null, null, emptyList(), false))
            return
        }
        thread(name = "hachi-weather") {
            val forecast = Weather.fetch(place)
            val rain = RainRadar.fetch(settings.yahooAppId, place)
            main.post { update(WeatherState(place, forecast, rain, forecast == null)) }
        }
    }

    private fun update(next: WeatherState) {
        state = next
        listener?.invoke(next)
    }

    private companion object {
        // Open-Meteo updates hourly and the radar every ten minutes; a quarter hour is a fair middle
        // and keeps the daily request count far below either service's limit.
        const val REFRESH_MS = 15 * 60 * 1000L
    }
}
