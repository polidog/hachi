package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainRadarTest {
    /** Shaped as the YOLP reference documents it; no app id was available to capture a live one. */
    private fun body(vararg readings: Triple<String, Double, String>): String {
        val entries = readings.joinToString(",") { (date, rainfall, type) ->
            """{"Type":"$type","Date":"$date","Rainfall":$rainfall}"""
        }
        return """
            {"ResultInfo":{"Count":1,"Total":1,"Start":1,"Status":200},
            "Feature":[{"Id":"201609200910_139.732293_35.663613","Name":"地点(139.732293,35.663613)の2016年9月20日 9時10分から60分間の天気情報",
            "Geometry":{"Type":"point","Coordinates":"139.732293,35.663613"},
            "Property":{"WeatherAreaCode":4410,"WeatherList":{"Weather":[$entries]}}}]}
        """.trimIndent()
    }

    @Test
    fun readsEachReadingAndCountsMinutesFromTheFirstOne() {
        val points = parseRain(
            body(
                Triple("202609171610", 0.0, "observation"),
                Triple("202609171620", 0.35, "forecast"),
                Triple("202609171630", 4.2, "forecast"),
            )
        )
        assertEquals(3, points.size)
        assertEquals(0, points[0].minutesFromNow)
        assertEquals(10, points[1].minutesFromNow)
        assertEquals(20, points[2].minutesFromNow)
        assertEquals(4.2, points[2].mmPerHour, 1e-9)
        assertTrue(points[0].observed)
        assertFalse(points[1].observed)
    }

    @Test
    fun minutesKeepCountingAcrossAMonthBoundary() {
        // 30 Sep 23:50 -> 1 Oct 00:00: the day component drops, but only ten minutes passed.
        val points = parseRain(
            body(
                Triple("202609302350", 0.0, "observation"),
                Triple("202610010000", 1.0, "forecast"),
            )
        )
        assertEquals(10, points[1].minutesFromNow)
    }

    @Test
    fun aResponseWithoutReadingsIsEmpty() {
        assertTrue(parseRain("not json").isEmpty())
        assertTrue(parseRain("""{"ResultInfo":{"Count":0},"Feature":[]}""").isEmpty())
    }

    @Test
    fun saysWhenRainStarts() {
        val summary = summarizeRain(
            listOf(
                RainPoint(0, 0.0, true),
                RainPoint(10, 0.0, false),
                RainPoint(20, 2.5, false),
                RainPoint(30, 8.0, false),
            )
        )
        assertFalse(summary.rainingNow)
        assertEquals(20, summary.startsInMinutes)
        assertNull("not asked about stopping while it is dry", summary.stopsInMinutes)
        assertEquals(8.0, summary.peakMmPerHour, 1e-9)
    }

    @Test
    fun saysWhenRainStops() {
        val summary = summarizeRain(
            listOf(
                RainPoint(0, 3.0, true),
                RainPoint(10, 1.0, false),
                RainPoint(20, 0.0, false),
            )
        )
        assertTrue(summary.rainingNow)
        assertEquals(20, summary.stopsInMinutes)
        assertNull("stays dry once it stops", summary.resumesInMinutes)
        assertNull("not asked about starting while it is already raining", summary.startsInMinutes)
    }

    @Test
    fun aLullIsNotAClearHour() {
        // Exactly the Miyazaki reading that made the page claim an hour in the clear: raining now,
        // dry for most of the hour, raining again at the end of it.
        val summary = summarizeRain(
            listOf(
                RainPoint(0, 0.95, true),
                RainPoint(10, 0.0, false),
                RainPoint(20, 0.0, false),
                RainPoint(30, 0.0, false),
                RainPoint(40, 0.0, false),
                RainPoint(50, 0.0, false),
                RainPoint(60, 3.13, false),
            )
        )
        assertTrue(summary.rainingNow)
        assertEquals(10, summary.stopsInMinutes)
        assertEquals(60, summary.resumesInMinutes)
    }

    @Test
    fun aDrySpellSaysNothingIsComing() {
        val summary = summarizeRain(listOf(RainPoint(0, 0.0, true), RainPoint(10, 0.0, false)))
        assertFalse(summary.rainingNow)
        assertNull(summary.startsInMinutes)
        assertEquals(0.0, summary.peakMmPerHour, 1e-9)
        // And an empty series must not throw.
        assertFalse(summarizeRain(emptyList()).rainingNow)
    }
}
