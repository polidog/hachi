package dev.polidog.hachi

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Home page 1: what it is doing outside now, the rest of today, tomorrow, and the rain.
 *
 * Laid out like a page of a weather almanac rather than a dashboard: one very large temperature,
 * the few words that qualify it, and everything else set small around it. Nothing sits in a box;
 * the columns are held apart by space alone.
 */
class WeatherPage(context: Context) : LinearLayout(context) {
    private val place = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 12f
        letterSpacing = 0.3f
        isAllCaps = true
    }
    private val range = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 15f
    }
    private val temperature = TextView(context).apply {
        setTextColor(TEXT)
        textSize = 92f
        typeface = DISPLAY
        letterSpacing = -0.03f
        includeFontPadding = false
    }
    private val condition = TextView(context).apply {
        setTextColor(TEXT)
        textSize = 22f
    }
    private val chance = TextView(context).apply { textSize = 15f }
    private val tomorrow = DayCard(context)
    private val hours = HourlyStrip(context)
    private val rain = RainTimelineView(context)
    private val rainSummary = TextView(context).apply {
        setTextColor(TEXT)
        textSize = 15f
    }
    private val message = TextView(context).apply {
        setTextColor(MUTED)
        textSize = 14f
        gravity = Gravity.CENTER
        visibility = GONE
    }

    init {
        orientation = VERTICAL
        val side = context.dp(48)
        setPadding(side, context.dp(28), side, context.dp(24))

        addView(place)
        addView(message, LayoutParams(FILL, 0, 1f))

        // Left: the number and what qualifies it. Right: the rain, then tomorrow.
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            // Today's range and its chance of rain share a line: on a 5-inch screen a line of its
            // own for the chance is the one that falls off the bottom.
            addView(LinearLayout(context).apply {
                addView(range)
                addView(chance, LayoutParams(WRAP, WRAP).apply { marginStart = context.dp(20) })
            })
            addView(temperature, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(4) })
            addView(condition)
        }
        val right = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(rainSummary)
            addView(rain, LayoutParams(FILL, context.dp(44)).apply { topMargin = context.dp(8) })
            addView(tomorrow, LayoutParams(FILL, WRAP).apply { topMargin = context.dp(28) })
        }
        val columns = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LayoutParams(0, WRAP, 1.1f))
            addView(right, LayoutParams(0, WRAP, 1f).apply { marginStart = context.dp(32) })
        }
        addView(columns, LayoutParams(FILL, 0, 1f))
        addView(hours, LayoutParams(FILL, WRAP).apply { topMargin = context.dp(10) })
    }

    fun bind(state: WeatherState) {
        val forecast = state.forecast
        val parts = listOf<android.view.View>(range, temperature, condition, chance, tomorrow, hours, rain, rainSummary)
        if (state.place == null || forecast == null) {
            // Nothing to show: say which of the two reasons it is.
            message.text = context.getString(
                if (state.place == null) R.string.weather_no_place else R.string.weather_unavailable
            )
            message.visibility = VISIBLE
            place.text = state.place?.name.orEmpty()
            parts.forEach { it.visibility = GONE }
            return
        }
        message.visibility = GONE
        listOf<android.view.View>(temperature, condition, tomorrow).forEach { it.visibility = VISIBLE }

        place.text = state.place.name
        temperature.text = String.format(Locale.getDefault(), "%.0f°", forecast.temperature)
        condition.text = context.getString(WeatherText.labelRes(forecast.code))
        val today = forecast.days.getOrNull(0)
        range.visibility = if (today == null) GONE else VISIBLE
        today?.let { range.text = String.format(Locale.getDefault(), "↑ %.0f°   ↓ %.0f°", it.high, it.low) }
        chance.visibility = if (today == null || today.rainChance < 0) GONE else VISIBLE
        today?.let {
            chance.text = "☂  ${it.rainChance}%"
            chance.setTextColor(if (it.rainChance >= WET) ACCENT_INK else MUTED)
        }
        tomorrow.bind(context.getString(R.string.weather_tomorrow), forecast.days.getOrNull(1))
        hours.bind(forecast.hours)
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

/** A chance of rain at or above this is worth the accent; below it, it is just weather. */
private const val WET = 30

/** The hours ahead, across the bottom: when, what it will be doing, how warm, how likely to rain. */
private class HourlyStrip(context: Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        setPadding(0, context.dp(12), 0, 0)
    }

    fun bind(hours: List<Hour>) {
        val shown = hours.take(COUNT)
        visibility = if (shown.isEmpty()) GONE else VISIBLE
        // The strip is the same shape every refresh, so the columns are built once and rebound.
        while (childCount < shown.size) addView(column(), LayoutParams(0, WRAP, 1f))
        for (index in 0 until childCount) {
            val column = getChildAt(index) as LinearLayout
            val hour = shown.getOrNull(index)
            column.visibility = if (hour == null) GONE else VISIBLE
            if (hour == null) continue
            (column.getChildAt(0) as TextView).text =
                context.getString(R.string.weather_hour, hour.hour)
            (column.getChildAt(1) as TextView).text = WeatherText.emoji(hour.code, hour.isDay)
            (column.getChildAt(2) as TextView).text =
                String.format(Locale.getDefault(), "%.0f°", hour.temperature)
            (column.getChildAt(3) as TextView).apply {
                text = if (hour.rainChance >= 0) "${hour.rainChance}%" else ""
                setTextColor(if (hour.rainChance >= WET) ACCENT_INK else MUTED)
            }
        }
    }

    private fun column() = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text(12f, MUTED))
        addView(text(17f, TEXT))
        addView(text(15f, TEXT))
        addView(text(12f, ACCENT_INK))
    }

    private fun text(size: Float, colour: Int) = TextView(context).apply {
        textSize = size
        setTextColor(colour)
    }

    private companion object {
        /** As many hours as fit across the display without the columns crowding each other. */
        const val COUNT = 8
    }
}

/** One day's line: label, icon, high/low and the chance of rain. */
private class DayCard(context: Context) : LinearLayout(context) {
    private val label = text(13f, MUTED)
    private val icon = text(17f, TEXT)
    private val range = text(17f, TEXT)
    private val chance = text(13f, ACCENT_INK)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label, LayoutParams(context.dp(52), WRAP))
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
        chance.setTextColor(if (day.rainChance >= WET) ACCENT_INK else MUTED)
    }

    private fun text(size: Float, colour: Int) = TextView(context).apply {
        textSize = size
        setTextColor(colour)
    }
}
