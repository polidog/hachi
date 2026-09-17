package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkySceneTest {
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun everyWmoCodeLandsSomewhere() {
        assertEquals(SkyScene.CLEAR, sceneOf(0))
        assertEquals(SkyScene.PARTLY_CLOUDY, sceneOf(2))
        assertEquals(SkyScene.CLOUDY, sceneOf(3))
        assertEquals(SkyScene.FOG, sceneOf(48))
        assertEquals(SkyScene.RAIN, sceneOf(63))
        assertEquals(SkyScene.RAIN, sceneOf(82))
        assertEquals(SkyScene.SNOW, sceneOf(75))
        assertEquals(SkyScene.THUNDER, sceneOf(95))
        // An unknown or absent code must not throw; a clear sky is the safe default.
        assertEquals(SkyScene.CLEAR, sceneOf(-1))
        assertEquals(SkyScene.CLEAR, sceneOf(9999))
    }

    @Test
    fun aClearSkyIsLeftAlone() {
        val palette = DayPalette.at(720)
        val same = washed(palette, SkyScene.CLEAR)
        assertEquals(palette.top, same.top)
        assertEquals(palette.bottom, same.bottom)
    }

    @Test
    fun rainIsDimmerAndLessColourfulThanTheSameHourClear() {
        val noon = DayPalette.at(720)
        val wet = washed(noon, SkyScene.RAIN)
        val brightness = { c: Int -> red(c) + green(c) + blue(c) }
        assertTrue("rain should be darker", brightness(wet.top) < brightness(noon.top))
        val spread = { c: Int -> maxOf(red(c), green(c), blue(c)) - minOf(red(c), green(c), blue(c)) }
        assertTrue("rain should be greyer", spread(wet.top) < spread(noon.top))
    }

    @Test
    fun theTimeOfDayStillShowsThroughTheWeather() {
        // An overcast noon must stay brighter than an overcast dusk, or the sky stops telling time.
        val brightness = { c: Int -> red(c) + green(c) + blue(c) }
        val noon = brightness(washed(DayPalette.at(720), SkyScene.CLOUDY).top)
        val night = brightness(washed(DayPalette.at(0), SkyScene.CLOUDY).top)
        assertTrue("$noon should beat $night", noon > night)
    }

    @Test
    fun coloursStayOpaqueAndInRange() {
        for (scene in SkyScene.entries) {
            for (minute in 0 until 1440 step 60) {
                val palette = washed(DayPalette.at(minute), scene)
                for (colour in listOf(palette.top, palette.middle, palette.bottom)) {
                    assertEquals(0xFF, (colour ushr 24) and 0xFF)
                    assertTrue(red(colour) in 0..255 && green(colour) in 0..255 && blue(colour) in 0..255)
                }
            }
        }
    }
}
