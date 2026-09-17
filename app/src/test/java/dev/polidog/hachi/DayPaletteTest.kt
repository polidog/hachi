package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPaletteTest {
    @Test
    fun keyframeMinutesReturnTheirOwnColours() {
        for ((minute, palette) in DayPalette.keyframes) {
            assertEquals("at $minute", palette.top, DayPalette.at(minute).top)
            assertEquals("at $minute", palette.bottom, DayPalette.at(minute).bottom)
        }
    }

    @Test
    fun midpointsLandBetweenTheirNeighbours() {
        // 07:00 sits halfway between the 06:00 dawn and 08:00 morning keyframes.
        val dawn = DayPalette.at(360).top and 0xFF
        val morning = DayPalette.at(480).top and 0xFF
        val between = DayPalette.at(420).top and 0xFF
        assertTrue("$between between $dawn and $morning", between in minOf(dawn, morning)..maxOf(dawn, morning))
        assertNotEquals(dawn, between)
    }

    @Test
    fun theDayWrapsAtMidnight() {
        // 20:00 and 00:00 are the same night palette, so everything between them holds still.
        assertEquals(DayPalette.at(1200).top, DayPalette.at(1350).top)
        assertEquals(DayPalette.at(0).top, DayPalette.at(1440).top)
        assertEquals(DayPalette.at(0).top, DayPalette.at(-60).top)
    }

    @Test
    fun everyColourIsOpaque() {
        for (minute in 0 until 1440 step 7) {
            val palette = DayPalette.at(minute)
            for (colour in listOf(palette.top, palette.middle, palette.bottom)) {
                assertEquals("alpha at $minute", 0xFF, (colour ushr 24) and 0xFF)
            }
        }
    }
}
