package dev.polidog.hachi.tools

import android.content.Context
import dev.polidog.hachi.CalendarEvent
import dev.polidog.hachi.calendarPermitted
import dev.polidog.hachi.readCalendar
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The calendars this device syncs, as the model asks about them -- see [readCalendar]. */
class GetCalendarTool(private val context: Context) : Tool {
    override val name = "get_calendar"

    /** Without the permission there is nothing to offer: the tool is left out rather than failing. */
    val available: Boolean get() = calendarPermitted(context)

    override val declaration = declare(
        name,
        "Returns the events on the calendars this device is signed in to, starting from today. " +
            "Use this for anything about what is scheduled, what is on today, or when someone is free.",
        listOf(
            Triple(
                "days",
                "How many days from today to include, as a number. 1 is today only, 7 is the " +
                    "coming week. Defaults to 1, at most 31.",
                false,
            ),
        ),
    )

    override fun run(args: JSONObject): JSONObject {
        if (!available) return failure("calendar_permission_not_granted")
        val zone = ZoneId.systemDefault()
        val days = args.optString("days").trim().toIntOrNull()?.coerceIn(1, 31) ?: 1
        val events = readCalendar(context, days, zone) ?: return failure("calendar_unavailable")
        val today = LocalDate.now(zone)
        return JSONObject()
            .put("from", today.toString())
            .put("through", today.plusDays(days - 1L).toString())
            .put("events", JSONArray().apply { events.forEach { put(json(it)) } })
    }

    private fun json(event: CalendarEvent): JSONObject {
        val result = JSONObject()
            .put("title", event.title)
            .put("date", event.date.toString())
            .put("all_day", event.allDay)
        if (event.allDay) {
            if (event.lastDate.isAfter(event.date)) result.put("through", event.lastDate.toString())
        } else {
            result.put("start", event.start?.format(CLOCK)).put("end", event.end?.format(CLOCK))
        }
        if (event.location.isNotEmpty()) result.put("location", event.location)
        if (event.calendar.isNotEmpty()) result.put("calendar", event.calendar)
        return result
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
