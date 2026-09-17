package dev.polidog.hachi

/** WMO weather codes, as Open-Meteo reports them, turned into something sayable. */
object WeatherText {
    fun labelRes(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        51, 53, 55, 56, 57 -> R.string.wx_drizzle
        61, 63, 65, 66, 67 -> R.string.wx_rain
        71, 73, 75, 77 -> R.string.wx_snow
        80, 81, 82 -> R.string.wx_showers
        85, 86 -> R.string.wx_snow_showers
        95, 96, 99 -> R.string.wx_thunder
        else -> R.string.wx_unknown
    }

    fun emoji(code: Int, isDay: Boolean): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "•"
    }
}

/** Rain intensity in mm/h, described the way a forecast would say it. */
fun rainLabelRes(mmPerHour: Double): Int = when {
    mmPerHour <= 0.0 -> R.string.rain_none
    mmPerHour < 1.0 -> R.string.rain_slight
    mmPerHour < 5.0 -> R.string.rain_light
    mmPerHour < 10.0 -> R.string.rain_moderate
    mmPerHour < 30.0 -> R.string.rain_heavy
    else -> R.string.rain_violent
}
