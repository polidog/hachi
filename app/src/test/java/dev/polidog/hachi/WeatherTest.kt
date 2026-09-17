package dev.polidog.hachi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads captured from the live services, not written by hand. */
class WeatherTest {
    private val forecastBody = """
        {"latitude":35.7,"longitude":139.6875,"utc_offset_seconds":32400,"timezone":"Asia/Tokyo",
        "current_units":{"temperature_2m":"°C","weather_code":"wmo code","is_day":""},
        "current":{"time":"2026-09-17T20:00","interval":900,"temperature_2m":20.9,"weather_code":1,"is_day":0},
        "daily_units":{"temperature_2m_max":"°C"},
        "daily":{"time":["2026-09-17","2026-09-18","2026-09-19"],"weather_code":[53,3,51],
        "temperature_2m_max":[25.0,24.9,23.1],"temperature_2m_min":[19.2,18.6,20.2],
        "precipitation_probability_max":[100,22,78]}}
    """.trimIndent()

    private val geocodingBody = """
        {"results":[
        {"id":11808021,"name":"渋谷","latitude":35.6589,"longitude":139.70665,"country_code":"JP",
        "timezone":"Asia/Tokyo","country":"日本","admin1":"東京都","admin2":"渋谷区"},
        {"id":9277764,"name":"渋谷","latitude":34.82654,"longitude":135.43974,"country_code":"JP",
        "country":"日本","admin1":"大阪府","admin2":"池田市"}]}
    """.trimIndent()

    @Test
    fun readsCurrentConditionsAndEachDay() {
        val forecast = parseForecast(forecastBody)!!
        assertEquals(20.9, forecast.temperature, 1e-9)
        assertEquals(1, forecast.code)
        assertEquals(false, forecast.isDay) // is_day of 0 is night
        assertEquals(3, forecast.days.size)
        val today = forecast.days[0]
        assertEquals("2026-09-17", today.date)
        assertEquals(25.0, today.high, 1e-9)
        assertEquals(19.2, today.low, 1e-9)
        assertEquals(53, today.code)
        assertEquals(100, today.rainChance)
        assertEquals(22, forecast.days[1].rainChance)
    }

    @Test
    fun aBodyThatIsNotAForecastIsNotOne() {
        assertNull(parseForecast("not json"))
        assertNull(parseForecast("""{"error":true,"reason":"nope"}"""))
    }

    @Test
    fun placesAreLabelledWellEnoughToTellDuplicatesApart() {
        val places = parsePlaces(geocodingBody)
        assertEquals(2, places.size)
        // Both are called 渋谷; the prefecture is what distinguishes them.
        assertEquals("渋谷, 東京都, 日本", places[0].name)
        assertEquals("渋谷, 大阪府, 日本", places[1].name)
        assertEquals(35.6589, places[0].latitude, 1e-9)
        assertTrue(parsePlaces("""{"generationtime_ms":0.1}""").isEmpty())
    }
}
