package dev.polidog.hachi.tools

import android.content.Context
import dev.polidog.hachi.R
import dev.polidog.hachi.RainPoint
import dev.polidog.hachi.RainRadar
import dev.polidog.hachi.Settings
import dev.polidog.hachi.Weather
import dev.polidog.hachi.WeatherText
import dev.polidog.hachi.rainLabelRes
import dev.polidog.hachi.summarizeRain
import org.json.JSONArray
import org.json.JSONObject

/**
 * The weather where the device lives.
 *
 * Two sources, because they answer different questions: Open-Meteo gives the temperature and the
 * daily forecast anywhere in the world, while Yahoo's radar gives the next hour of rain minute by
 * minute, which is what "do I need an umbrella right now" actually depends on. The radar is Japan
 * only and needs an app id, so its half is simply left out when it is not configured.
 */
class GetWeatherTool(private val context: Context, private val settings: Settings) : Tool {
    override val name = "get_weather"

    override val declaration = declare(
        name,
        "Returns the current weather, today's and tomorrow's forecast, and -- in Japan -- whether " +
            "it is about to rain within the hour, for the place this device is set to. " +
            "Use this instead of searching the web for the local weather.",
    )

    override fun run(args: JSONObject): JSONObject {
        val place = settings.weatherPlace ?: return failure("weather_place_not_set")
        val forecast = Weather.fetch(place) ?: return failure("weather_unavailable")

        val result = JSONObject()
            .put("place", place.name)
            .put(
                "now",
                JSONObject()
                    .put("temperature_c", forecast.temperature)
                    .put("condition", context.getString(WeatherText.labelRes(forecast.code))),
            )
        forecast.days.getOrNull(0)?.let { result.put("today", day(it)) }
        forecast.days.getOrNull(1)?.let { result.put("tomorrow", day(it)) }

        val rain = RainRadar.fetch(settings.yahooAppId, place)
        if (rain.isNotEmpty()) result.put("rain_next_hour", rain(rain))
        return result
    }

    private fun day(day: dev.polidog.hachi.Day) = JSONObject()
        .put("date", day.date)
        .put("high_c", day.high)
        .put("low_c", day.low)
        .put("condition", context.getString(WeatherText.labelRes(day.code)))
        .apply { if (day.rainChance >= 0) put("rain_chance_percent", day.rainChance) }

    private fun rain(points: List<RainPoint>): JSONObject {
        val summary = summarizeRain(points)
        val series = JSONArray()
        for (point in points) {
            series.put(
                JSONObject()
                    .put("in_minutes", point.minutesFromNow)
                    .put("mm_per_hour", point.mmPerHour)
            )
        }
        return JSONObject()
            .put("raining_now", summary.rainingNow)
            .apply { summary.startsInMinutes?.let { put("starts_in_minutes", it) } }
            .apply { summary.stopsInMinutes?.let { put("stops_in_minutes", it) } }
            .apply { summary.resumesInMinutes?.let { put("resumes_in_minutes", it) } }
            .put("peak_mm_per_hour", summary.peakMmPerHour)
            .put("peak_intensity", context.getString(rainLabelRes(summary.peakMmPerHour)))
            .put("series", series)
    }
}
