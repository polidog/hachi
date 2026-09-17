package dev.polidog.hachi

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** One reading from the rain radar: how hard it is raining, or will be, at [minutesFromNow]. */
data class RainPoint(val minutesFromNow: Int, val mmPerHour: Double, val observed: Boolean)

/**
 * Yahoo! Japan's weather API (YOLP): observed and forecast rainfall in mm/h at 10-minute steps, from
 * now to 60 minutes ahead, for one point. This is what answers "is it about to rain" -- Open-Meteo's
 * daily forecast cannot.
 *
 * Japan only, and it needs a Yahoo! application id. Without one configured, the app simply goes
 * without the rain timeline rather than failing.
 */
object RainRadar {
    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Blocking; call off the main thread. Empty when unconfigured or unavailable. */
    fun fetch(appId: String, place: Place): List<RainPoint> {
        if (appId.isBlank()) return emptyList()
        return try {
            val url = "https://map.yahooapis.jp/weather/V1/place" +
                "?coordinates=${place.longitude},${place.latitude}&output=json&interval=10&appid=$appId"
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) parseRain(body) else {
                    Log.w(TAG, "rain radar failed (${response.code})")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rain radar failed", e)
            emptyList()
        }
    }

    private const val TAG = "Hachi"
}

/**
 * Parses YOLP's response. The readings are timestamped (yyyyMMddHHmm) and start at the current
 * 10-minute step, so the offset in minutes is counted from the first entry rather than from the
 * device clock, which may disagree with the server's idea of "now" by a few minutes.
 */
internal fun parseRain(body: String): List<RainPoint> {
    val features = runCatching { JSONObject(body).optJSONArray("Feature") }.getOrNull() ?: return emptyList()
    val list = features.optJSONObject(0)
        ?.optJSONObject("Property")
        ?.optJSONObject("WeatherList")
        ?.optJSONArray("Weather")
        ?: return emptyList()
    var start: LocalDateTime? = null
    return buildList {
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val stamp = parseStamp(entry.optString("Date")) ?: continue
            val from = start ?: stamp.also { start = it }
            add(
                RainPoint(
                    minutesFromNow = Duration.between(from, stamp).toMinutes().toInt(),
                    mmPerHour = entry.optDouble("Rainfall", 0.0),
                    observed = entry.optString("Type") == "observation",
                )
            )
        }
    }
}

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

private fun parseStamp(value: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(value, STAMP) }.getOrNull()

/** What the radar readings amount to: raining now? starting soon? stopping soon? how hard? */
data class RainSummary(
    val rainingNow: Boolean,
    val startsInMinutes: Int?,
    val stopsInMinutes: Int?,
    /** Rain returning after [stopsInMinutes] -- a lull is not the same as a clear hour. */
    val resumesInMinutes: Int?,
    val peakMmPerHour: Double,
)

/**
 * Reduces the radar series to the few facts worth saying out loud.
 *
 * "Now" is the first reading, which the API anchors to the current 10-minute step. [startsInMinutes]
 * is only set when it is dry now, and [stopsInMinutes] only when it is raining now -- reporting both
 * at once would mean describing a gap the caller did not ask about.
 *
 * [resumesInMinutes] exists because saying only "it stops in ten minutes" reads as an hour in the
 * clear, which is wrong whenever the radar shows rain returning before the hour is out.
 */
fun summarizeRain(points: List<RainPoint>): RainSummary {
    if (points.isEmpty()) return RainSummary(false, null, null, null, 0.0)
    val rainingNow = points.first().mmPerHour > 0.0
    val upcoming = points.drop(1)
    val stops = if (rainingNow) upcoming.firstOrNull { it.mmPerHour <= 0.0 }?.minutesFromNow else null
    return RainSummary(
        rainingNow = rainingNow,
        startsInMinutes = if (rainingNow) null else upcoming.firstOrNull { it.mmPerHour > 0.0 }?.minutesFromNow,
        stopsInMinutes = stops,
        resumesInMinutes = stops?.let { after ->
            upcoming.firstOrNull { it.minutesFromNow > after && it.mmPerHour > 0.0 }?.minutesFromNow
        },
        peakMmPerHour = points.maxOf { it.mmPerHour },
    )
}
