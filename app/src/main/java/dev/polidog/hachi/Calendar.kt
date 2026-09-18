package dev.polidog.hachi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What is on the calendar, as the device already knows it.
 *
 * Nothing is fetched from Google here. A tablet signed in to an account syncs its calendars into the
 * system provider, and this is that provider read back -- so there is no OAuth flow, no client id
 * and no refresh token to keep alive on a wall. Whatever the Calendar app shows is what Hachi can
 * see, Google or otherwise, and a device with no account simply has nothing to show.
 *
 * Read once here for both readers: the tool the model calls and the page on the wall.
 */
data class CalendarEvent(
    val title: String,
    /** The day it starts, read in the calendar's own terms -- see [calendarEvent]. */
    val date: LocalDate,
    /** The last day it covers; the same as [date] unless an all-day event spans several. */
    val lastDate: LocalDate,
    /** Null for an all-day event, which is the whole of [date] and has no clock. */
    val start: LocalTime?,
    val end: LocalTime?,
    val location: String,
    val calendar: String,
) {
    val allDay get() = start == null
}

fun calendarPermitted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * Blocking. The events from [start] onwards, in time order; null when the provider could not be read.
 *
 * [days] counts [start] itself, so 1 is that day alone.
 */
fun readCalendar(
    context: Context,
    days: Int,
    zone: ZoneId = ZoneId.systemDefault(),
    limit: Int = MAX_EVENTS,
    start: LocalDate = LocalDate.now(zone),
): List<CalendarEvent>? {
    val (from, to) = calendarWindow(days.toString(), start, zone)
    // Instances, not Events: a weekly meeting is one Events row, and the occurrences are what a
    // question about today is actually about. The window goes in the path, not in a selection.
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(from.toString()).appendPath(to.toString()).build()
    val events = mutableListOf<CalendarEvent>()
    // The same appointment is often on two calendars at once -- one shared, one personal -- and a
    // list read out loud is where saying it twice actually shows. Same title at the same moment is
    // the same appointment; the calendar it is credited to is the first one seen.
    val seen = HashSet<String>()
    val cursor = try {
        context.contentResolver.query(uri, PROJECTION, null, null, ORDER)
    } catch (e: Exception) {
        Log.w("Hachi", "calendar unreadable", e)
        null
    } ?: return null
    cursor.use {
        while (events.size < limit && it.moveToNext()) {
            if (!seen.add("${it.getString(0)}|${it.getLong(1)}|${it.getLong(2)}")) continue
            events += calendarEvent(
                title = it.getString(0),
                begin = it.getLong(1),
                end = it.getLong(2),
                allDay = it.getInt(3) == 1,
                location = it.getString(4),
                calendar = it.getString(5),
                zone = zone,
            )
        }
    }
    return events
}

/** The window to ask for, as the provider wants it: epoch millis, from midnight, end exclusive. */
fun calendarWindow(days: String, today: LocalDate, zone: ZoneId): Pair<Long, Long> {
    val span = days.trim().toIntOrNull()?.coerceIn(1, 31) ?: 1
    return today.atStartOfDay(zone).toInstant().toEpochMilli() to
        today.plusDays(span.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * One occurrence of one event.
 *
 * All-day events are stored at UTC midnight whatever the calendar's own zone was, so reading them in
 * the device zone is what turns tomorrow's holiday into today's -- they get their dates read in UTC,
 * and only timed events get a clock.
 */
fun calendarEvent(
    title: String?,
    begin: Long,
    end: Long,
    allDay: Boolean,
    location: String?,
    calendar: String?,
    zone: ZoneId,
): CalendarEvent {
    val reading = if (allDay) ZoneId.of("UTC") else zone
    val from = Instant.ofEpochMilli(begin).atZone(reading)
    val until = Instant.ofEpochMilli(end).atZone(reading)
    return CalendarEvent(
        title = title?.trim()?.ifEmpty { null } ?: UNTITLED,
        date = from.toLocalDate(),
        // An all-day event ends at midnight of the day after the last one it covers.
        lastDate = if (allDay) maxOf(from.toLocalDate(), until.toLocalDate().minusDays(1))
        else from.toLocalDate(),
        start = if (allDay) null else from.toLocalTime(),
        end = if (allDay) null else until.toLocalTime(),
        location = location?.trim().orEmpty(),
        calendar = calendar?.trim().orEmpty(),
    )
}

/** What is on [date], counting an all-day event on every day it spans and not just its first. */
fun eventsOn(events: List<CalendarEvent>, date: LocalDate): List<CalendarEvent> =
    events.filter { date in it.date..it.lastDate }

/**
 * The next timed event that has not started yet at [now], or null.
 *
 * All-day events are passed over: a holiday calendar puts one on nearly every day, and "all day,
 * national holiday" is not what anyone glancing at the clock wants to know is next.
 */
fun nextEvent(events: List<CalendarEvent>, now: java.time.LocalDateTime): CalendarEvent? =
    events.filter { it.start != null && it.date.atTime(it.start) > now }
        .minByOrNull { it.date.atTime(it.start) }

const val UNTITLED = "(untitled)"

// ponytail: a month of a busy calendar is more than anyone is told out loud or reads off a wall;
// raise it if something ever wants the whole list rather than what is next.
private const val MAX_EVENTS = 50
private val PROJECTION = arrayOf(
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.EVENT_LOCATION,
    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
)
private const val ORDER = "${CalendarContract.Instances.BEGIN} ASC"
