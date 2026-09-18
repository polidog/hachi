package dev.polidog.hachi

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A place the forecast can be fetched for. */
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** One hour of the forecast. */
data class Hour(
    val time: String,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val rainChance: Int,
) {
    /** "2026-09-17T20:00" -> 20. */
    val hour: Int get() = time.substringAfter('T').substringBefore(':').toIntOrNull() ?: 0
}

/** One day of the forecast. */
data class Day(val date: String, val high: Double, val low: Double, val code: Int, val rainChance: Int)

/** Current conditions plus the next couple of days, from Open-Meteo. */
data class Forecast(
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val hours: List<Hour>,
    val days: List<Day>,
)

/**
 * Open-Meteo: no account, no key, worldwide. Covers temperature and the daily forecast; the
 * minute-by-minute rain that matters for "should I leave now" comes from [RainRadar] instead.
 */
object Weather {
    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Blocking; call off the main thread. Returns null if the forecast could not be fetched. */
    fun fetch(place: Place): Forecast? = try {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,is_day,precipitation_probability" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) parseForecast(body) else {
                Log.w(TAG, "forecast failed (${response.code})")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "forecast failed", e)
        null
    }

    /** Blocking; call off the main thread. Place-name search, also key-free. */
    fun search(query: String, language: String): List<Place> = try {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${java.net.URLEncoder.encode(query, "UTF-8")}&count=8&language=$language&format=json"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) parsePlaces(response.body?.string().orEmpty()) else emptyList()
        }
    } catch (e: Exception) {
        Log.w(TAG, "place search failed", e)
        emptyList()
    }

    private const val TAG = "Hachi"
}

/** Parses Open-Meteo's forecast response. Returns null if the payload is not what it should be. */
internal fun parseForecast(body: String): Forecast? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val current = root.optJSONObject("current") ?: return null
    val daily = root.optJSONObject("daily") ?: return null
    val dates = daily.optJSONArray("time") ?: return null
    val codes = daily.optJSONArray("weather_code")
    val highs = daily.optJSONArray("temperature_2m_max")
    val lows = daily.optJSONArray("temperature_2m_min")
    val chances = daily.optJSONArray("precipitation_probability_max")
    val days = buildList {
        for (i in 0 until dates.length()) {
            add(
                Day(
                    date = dates.optString(i),
                    high = highs?.optDouble(i) ?: Double.NaN,
                    low = lows?.optDouble(i) ?: Double.NaN,
                    code = codes?.optInt(i) ?: -1,
                    // Absent (not zero) when the API omits it, so a missing value reads as unknown.
                    rainChance = chances?.optInt(i, -1) ?: -1,
                )
            )
        }
    }
    return Forecast(
        temperature = current.optDouble("temperature_2m", Double.NaN),
        code = current.optInt("weather_code", -1),
        isDay = current.optInt("is_day", 1) == 1,
        hours = parseHours(root.optJSONObject("hourly"), current.optString("time")),
        days = days,
    )
}

/**
 * The hourly series, from the current hour onwards. It starts at midnight local time, so the hours
 * already gone are dropped -- the timestamps are the same local ISO shape as current.time, which
 * makes a plain string comparison enough to find where now is.
 */
private fun parseHours(hourly: JSONObject?, from: String): List<Hour> {
    val times = hourly?.optJSONArray("time") ?: return emptyList()
    val temperatures = hourly.optJSONArray("temperature_2m")
    val codes = hourly.optJSONArray("weather_code")
    val daylight = hourly.optJSONArray("is_day")
    val chances = hourly.optJSONArray("precipitation_probability")
    return buildList {
        for (i in 0 until times.length()) {
            val time = times.optString(i)
            if (time < from) continue
            add(
                Hour(
                    time = time,
                    temperature = temperatures?.optDouble(i) ?: Double.NaN,
                    code = codes?.optInt(i) ?: -1,
                    isDay = (daylight?.optInt(i, 1) ?: 1) == 1,
                    rainChance = chances?.optInt(i, -1) ?: -1,
                )
            )
        }
    }
}

/** Parses Open-Meteo's geocoding response into places. */
internal fun parsePlaces(body: String): List<Place> {
    val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
    return buildList {
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            // "Shibuya, Tokyo, Japan" reads better than "Shibuya" alone when several match.
            val label = listOfNotNull(
                entry.optString("name").ifBlank { null },
                entry.optString("admin1").ifBlank { null },
                entry.optString("country").ifBlank { null },
            ).joinToString(", ")
            add(Place(label, entry.optDouble("latitude"), entry.optDouble("longitude")))
        }
    }
}
