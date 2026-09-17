package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Home page 1: what it is doing outside now, what it will do today and tomorrow, and the rain. */
class WeatherPage(context: Context) : LinearLayout(context) {
    private val place = TextView(context).apply {
        setTextColor(CREAM_60)
        textSize = 13f
        letterSpacing = 0.05f
    }
    private val emoji = TextView(context).apply { textSize = 40f }
    private val temperature = TextView(context).apply {
        setTextColor(CREAM)
        textSize = 46f
    }
    private val condition = TextView(context).apply {
        setTextColor(CREAM_60)
        textSize = 15f
    }
    private val today = DayCard(context)
    private val tomorrow = DayCard(context)
    private val rain = RainTimelineView(context)
    private val rainSummary = TextView(context).apply {
        setTextColor(CREAM)
        textSize = 13f
    }
    private val message = TextView(context).apply {
        setTextColor(CREAM_60)
        textSize = 14f
        gravity = Gravity.CENTER
        visibility = GONE
    }

    init {
        orientation = VERTICAL
        val side = context.dp(28)
        // The floating Talk button sits over the bottom centre of every page, so the page keeps
        // clear of it rather than drawing underneath.
        setPadding(side, context.dp(16), side, context.dp(54))

        addView(place)
        addView(message, LayoutParams(FILL, 0, 1f))

        val now = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(emoji)
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(temperature)
                    addView(condition)
                },
                LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(14) },
            )
        }
        // Left: what it is doing now, with the rain timeline under it. Right: the next two days.
        // Splitting it this way keeps both columns clear of the Talk button in the middle.
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(now)
            addView(rainSummary, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
            addView(
                rain,
                LayoutParams(FILL, context.dp(60)).apply { topMargin = context.dp(4) },
            )
        }
        val right = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(today)
            addView(tomorrow, LayoutParams(FILL, WRAP).apply { topMargin = context.dp(8) })
        }
        val columns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LayoutParams(0, WRAP, 1.05f))
            addView(right, LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(16) })
        }
        addView(columns, LayoutParams(FILL, 0, 1f))
    }

    fun bind(state: WeatherState) {
        val forecast = state.forecast
        if (state.place == null || forecast == null) {
            // Nothing to show: say which of the two reasons it is.
            message.text = context.getString(
                if (state.place == null) R.string.weather_no_place else R.string.weather_unavailable
            )
            message.visibility = VISIBLE
            place.text = state.place?.name.orEmpty()
            listOf<android.view.View>(emoji, temperature, condition, today, tomorrow, rain, rainSummary)
                .forEach { it.visibility = GONE }
            return
        }
        message.visibility = GONE
        listOf<android.view.View>(emoji, temperature, condition, today, tomorrow).forEach { it.visibility = VISIBLE }

        place.text = state.place.name
        emoji.text = WeatherText.emoji(forecast.code, forecast.isDay)
        temperature.text = String.format(Locale.getDefault(), "%.1f°", forecast.temperature)
        condition.text = context.getString(WeatherText.labelRes(forecast.code))
        today.bind(context.getString(R.string.weather_today), forecast.days.getOrNull(0))
        tomorrow.bind(context.getString(R.string.weather_tomorrow), forecast.days.getOrNull(1))
        rain.bind(state.rain)
        bindRainSummary(state.rain)
    }

    /** The one sentence the radar is worth: is it raining, and is that about to change? */
    private fun bindRainSummary(points: List<RainPoint>) {
        if (points.isEmpty()) {
            rainSummary.visibility = GONE
            return
        }
        rainSummary.visibility = VISIBLE
        val summary = summarizeRain(points)
        val intensity = context.getString(rainLabelRes(summary.peakMmPerHour))
        rainSummary.text = when {
            summary.rainingNow && summary.stopsInMinutes != null && summary.resumesInMinutes != null ->
                context.getString(
                    R.string.rain_stops_then_resumes,
                    summary.stopsInMinutes,
                    summary.resumesInMinutes,
                )
            summary.rainingNow && summary.stopsInMinutes != null ->
                context.getString(R.string.rain_stops_in, summary.stopsInMinutes)
            summary.rainingNow -> context.getString(R.string.rain_continues, intensity)
            summary.startsInMinutes != null ->
                context.getString(R.string.rain_starts_in, summary.startsInMinutes, intensity)
            else -> context.getString(R.string.rain_dry_hour)
        }
    }
}

/** One day's line: label, icon, high/low and the chance of rain. */
private class DayCard(context: Context) : LinearLayout(context) {
    private val label = text(11f, CREAM_60)
    private val icon = text(17f, CREAM)
    private val range = text(15f, CREAM)
    private val chance = text(12f, Color.rgb(0x8A, 0xB4, 0xF8))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pill(context.dp(10).toFloat())
        val h = context.dp(12)
        val v = context.dp(9)
        setPadding(h, v, h, v)
        addView(label, LayoutParams(context.dp(44), WRAP))
        addView(icon)
        addView(range, LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(8) })
        addView(chance)
    }

    fun bind(title: String, day: Day?) {
        visibility = if (day == null) GONE else VISIBLE
        if (day == null) return
        label.text = title
        // Daily codes have no day/night sense; they describe a whole day, so draw the daytime icon.
        icon.text = WeatherText.emoji(day.code, isDay = true)
        range.text = String.format(Locale.getDefault(), "%.0f° / %.0f°", day.high, day.low)
        chance.text = if (day.rainChance >= 0) "${day.rainChance}%" else ""
    }

    private fun text(size: Float, colour: Int) = TextView(context).apply {
        textSize = size
        setTextColor(colour)
    }
}
