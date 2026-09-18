package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarTest {
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test fun `the window starts at local midnight and covers whole days`() {
        val (from, to) = calendarWindow("3", LocalDate.of(2026, 9, 18), tokyo)
        assertEquals("2026-09-17T15:00:00Z", Instant.ofEpochMilli(from).toString())
        assertEquals("2026-09-20T15:00:00Z", Instant.ofEpochMilli(to).toString())
    }

    @Test fun `a missing or silly day count falls back to today`() {
        val today = LocalDate.of(2026, 9, 18)
        val oneDay = calendarWindow("1", today, tokyo)
        assertEquals(oneDay, calendarWindow("", today, tokyo))
        assertEquals(oneDay, calendarWindow("なんとなく", today, tokyo))
        assertEquals(oneDay, calendarWindow("0", today, tokyo))
        assertEquals(calendarWindow("31", today, tokyo), calendarWindow("365", today, tokyo))
    }

    @Test fun `an all-day event keeps the date the calendar wrote, not the local one`() {
        // Stored at UTC midnight: read in Tokyo it would come back as the evening before.
        val begin = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val event = calendarEvent("祝日", begin, begin + DAY, true, null, "ja.japanese", tokyo)
        assertEquals(LocalDate.of(2026, 9, 19), event.date)
        assertEquals(event.date, event.lastDate)
        assertTrue(event.allDay)
        assertNull(event.start)
        assertEquals("ja.japanese", event.calendar)
    }

    @Test fun `a multi-day all-day event ends on its last day, not the midnight after`() {
        val begin = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val event = calendarEvent("旅行", begin, begin + 3 * DAY, true, "", "", tokyo)
        assertEquals(LocalDate.of(2026, 9, 21), event.lastDate)
        assertEquals("", event.location)
    }

    @Test fun `a timed event gets a local clock and an untitled one still says something`() {
        val begin = Instant.parse("2026-09-18T01:30:00Z").toEpochMilli()
        val event = calendarEvent(" ", begin, begin + 1_800_000, false, " 会議室 ", null, tokyo)
        assertEquals(LocalDate.of(2026, 9, 18), event.date)
        assertEquals(LocalTime.of(10, 30), event.start)
        assertEquals(LocalTime.of(11, 0), event.end)
        assertEquals(UNTITLED, event.title)
        assertEquals("会議室", event.location)
    }

    private companion object {
        const val DAY = 86_400_000L
    }

    @Test fun `a day holds the all-day events that span it, and nothing from either side`() {
        val begin = Instant.parse("2026-09-19T00:00:00Z").toEpochMilli()
        val trip = calendarEvent("旅行", begin, begin + 3 * DAY, true, null, null, tokyo)
        val dentist = calendarEvent(
            "歯医者", Instant.parse("2026-09-20T01:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-20T02:00:00Z").toEpochMilli(), false, null, null, tokyo,
        )
        val events = listOf(trip, dentist)
        assertEquals(emptyList<CalendarEvent>(), eventsOn(events, LocalDate.of(2026, 9, 18)))
        assertEquals(listOf(trip), eventsOn(events, LocalDate.of(2026, 9, 19)))
        assertEquals(listOf(trip, dentist), eventsOn(events, LocalDate.of(2026, 9, 20)))
        assertEquals(listOf(trip), eventsOn(events, LocalDate.of(2026, 9, 21)))
        assertEquals(emptyList<CalendarEvent>(), eventsOn(events, LocalDate.of(2026, 9, 22)))
    }

    @Test fun `the next event is the first timed one still ahead, never an all-day one`() {
        val at = { d: Int, h: Int -> calendarEvent("e$d-$h", Instant.parse("2026-09-${d}T${"%02d".format(h - 9)}:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-${d}T${"%02d".format(h - 8)}:00:00Z").toEpochMilli(), false, null, null, tokyo) }
        val holiday = calendarEvent("祝日", Instant.parse("2026-09-18T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-09-19T00:00:00Z").toEpochMilli(), true, null, null, tokyo)
        val events = listOf(holiday, at(18, 10), at(18, 15), at(19, 11))
        val now = java.time.LocalDateTime.of(2026, 9, 18, 12, 0)
        assertEquals("e18-15", nextEvent(events, now)?.title)
        assertEquals("e19-11", nextEvent(events, now.withHour(16))?.title)
        assertNull(nextEvent(events, java.time.LocalDateTime.of(2026, 9, 19, 12, 0)))
        assertNull(nextEvent(listOf(holiday), now))
    }
}
