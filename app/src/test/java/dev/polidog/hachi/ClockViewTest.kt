package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class ClockViewTest {
    @Test fun `Japanese keeps the month and the weekday`() {
        assertEquals("9月18日 (金)", clockDate(LocalDateTime.of(2026, 9, 18, 7, 30), Locale.JAPAN))
    }

    @Test fun `Japanese writes the era on its own`() {
        assertEquals("令和8年", clockEra(LocalDateTime.of(2026, 9, 18, 7, 30), Locale.JAPAN))
    }

    @Test fun `the era changes on the day it did`() {
        assertEquals("平成31年", clockEra(LocalDateTime.of(2019, 4, 30, 12, 0), Locale.JAPAN))
        assertEquals("令和1年", clockEra(LocalDateTime.of(2019, 5, 1, 0, 0), Locale.JAPAN))
    }

    @Test fun `English stays a month and a day, with no era`() {
        val now = LocalDateTime.of(2026, 9, 18, 7, 30)
        assertEquals("Sep 18 (Fri)", clockDate(now, Locale.US))
        assertEquals("", clockEra(now, Locale.US))
    }
}
