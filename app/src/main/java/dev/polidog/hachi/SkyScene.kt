package dev.polidog.hachi

/** What the sky is doing, coarse enough to draw. */
enum class SkyScene { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

/** WMO weather codes, as Open-Meteo reports them, grouped into something paintable. */
fun sceneOf(code: Int): SkyScene = when (code) {
    0, 1 -> SkyScene.CLEAR
    2 -> SkyScene.PARTLY_CLOUDY
    3 -> SkyScene.CLOUDY
    45, 48 -> SkyScene.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> SkyScene.RAIN
    71, 73, 75, 77, 85, 86 -> SkyScene.SNOW
    95, 96, 99 -> SkyScene.THUNDER
    else -> SkyScene.CLEAR
}

/**
 * How far each scene pulls the colour of the sky away from a clear one.
 *
 * Overcast weather is not a different palette so much as the same one drained and dimmed, which also
 * means the time of day still shows through: an overcast noon stays brighter than an overcast dusk.
 */
private class Wash(val desaturate: Float, val darken: Float)

/**
 * Dimming is gentler than it was over a near-black sky: the type on this backdrop is near-black, so
 * every point the weather takes off the paper is contrast taken off the clock. A thundery afternoon
 * is a noticeably grey sheet of paper, not a dark one.
 */
private val WASHES = mapOf(
    SkyScene.CLEAR to Wash(0f, 1f),
    SkyScene.PARTLY_CLOUDY to Wash(0.20f, 0.98f),
    SkyScene.CLOUDY to Wash(0.62f, 0.93f),
    SkyScene.FOG to Wash(0.80f, 0.95f),
    SkyScene.RAIN to Wash(0.55f, 0.86f),
    SkyScene.SNOW to Wash(0.72f, 0.97f),
    SkyScene.THUNDER to Wash(0.60f, 0.80f),
)

/** The palette as [scene] leaves it. */
fun washed(palette: DayPalette, scene: SkyScene): DayPalette {
    val wash = WASHES.getValue(scene)
    return DayPalette(
        wash(palette.top, wash.desaturate, wash.darken),
        wash(palette.middle, wash.desaturate, wash.darken),
        wash(palette.bottom, wash.desaturate, wash.darken),
    )
}

/**
 * Drains [desaturate] of a colour's colour and dims what is left to [darken].
 *
 * Desaturating toward perceived brightness rather than a flat grey keeps a dim overcast sky from
 * going muddy: the blues stay blue-ish as they fade.
 */
internal fun wash(colour: Int, desaturate: Float, darken: Float): Int {
    val r = (colour shr 16) and 0xFF
    val g = (colour shr 8) and 0xFF
    val b = colour and 0xFF
    val grey = 0.299 * r + 0.587 * g + 0.114 * b
    fun channel(value: Int): Int =
        ((value + (grey - value) * desaturate) * darken).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/**
 * Where the emblem's shadow falls: [angle] in screen degrees (clockwise from right, so 90 is
 * straight down), [stretch] along that line, and [reach], how far it is carried off, in emblem radii.
 */
class Cast(val angle: Float, val stretch: Float, val reach: Float)

/**
 * The shadow at [minuteOfDay]: always a long diagonal down to the left, onto the page -- the emblem
 * sits on the right edge, so a shadow thrown right would only leave the screen. Within that it
 * follows the sun: flattest and longest in the morning, shortest at noon, steepening toward evening.
 * ponytail: the sun's arc is faked between [DAWN] and [DUSK]; take the forecast's sunrise and sunset
 * if a winter afternoon's shadow ever looks too short.
 */
fun castAt(minuteOfDay: Int): Cast {
    val dawn = DAWN.hour * 60 + DAWN.minute
    val dusk = DUSK.hour * 60 + DUSK.minute
    val t = ((minuteOfDay - dawn).toFloat() / (dusk - dawn)).coerceIn(0f, 1f)
    val height = 1f - kotlin.math.abs(2f * t - 1f)
    return Cast(125f + 30f * (1f - 2f * t), 2.4f - 0.9f * height, 0.6f + 1.0f * (1f - height))
}
