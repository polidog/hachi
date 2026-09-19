package dev.polidog.hachi

import java.time.LocalTime

/**
 * The type turns over here, and the backdrop jumps with it (see [DayPalette]).
 * ponytail: fixed hours. Take them from the forecast's sunrise and sunset if a summer evening
 * going black at a quarter past six ever feels early.
 */
val DUSK: LocalTime = LocalTime.of(18, 15)
val DAWN: LocalTime = LocalTime.of(5, 45)

fun isNight(time: LocalTime): Boolean = time >= DUSK || time < DAWN

/**
 * The colours of the backdrop at one time of day: three gradient stops, top to bottom.
 *
 * Keyframes are interpolated per channel, so the screen drifts through dawn and dusk rather than
 * snapping between looks. The day wraps, so the last keyframe interpolates back toward midnight.
 *
 * Paper by day and black by night, with a quarter hour of fade either side of [DUSK] and [DAWN],
 * and a jump at each of them, on the minute the type turns over. Fading straight through left the
 * backdrop mid grey for the minutes around the flip, and under cloud grey type on grey was gone.
 */
class DayPalette(val top: Int, val middle: Int, val bottom: Int) {
    companion object {
        /** Night: black, a breath lighter at the bottom so it is a sky and not a switched-off screen. */
        private fun night() = DayPalette(0xFF050505.toInt(), 0xFF0A0A0A.toInt(), 0xFF121210.toInt())

        /** (minuteOfDay, palette), ascending. Exposed so the interpolation can be tested. */
        val keyframes: List<Pair<Int, DayPalette>> = listOf(
            0 to night(),
            330 to night(), // 05:30 -- holds the night until it starts to lift
            DAWN.minuteOfDay() - 1 to DayPalette(0xFF2A2724.toInt(), 0xFF2E2B27.toInt(), 0xFF33302B.toInt()),
            DAWN.minuteOfDay() to DayPalette(0xFFD8CCC0.toInt(), 0xFFDFD3C6.toInt(), 0xFFE3D8CA.toInt()), // light type -> dark
            360 to DayPalette(0xFFE8DCD0.toInt(), 0xFFEFE3D6.toInt(), 0xFFF3E8DA.toInt()), // 06:00 dawn
            480 to DayPalette(0xFFE7E6E2.toInt(), 0xFFEDECE8.toInt(), 0xFFF1F0EC.toInt()), // 08:00 morning
            720 to DayPalette(0xFFEBEAE6.toInt(), 0xFFF2F1ED.toInt(), 0xFFF6F5F1.toInt()), // 12:00 noon
            960 to DayPalette(0xFFEAE7E0.toInt(), 0xFFF0EDE6.toInt(), 0xFFF4F1EA.toInt()), // 16:00 afternoon
            1080 to DayPalette(0xFFE4D6C8.toInt(), 0xFFE9DCCE.toInt(), 0xFFEDE2D5.toInt()), // 18:00 evening
            DUSK.minuteOfDay() - 1 to DayPalette(0xFFD8CCC0.toInt(), 0xFFDFD3C6.toInt(), 0xFFE3D8CA.toInt()),
            DUSK.minuteOfDay() to DayPalette(0xFF2A2724.toInt(), 0xFF2E2B27.toInt(), 0xFF33302B.toInt()), // dark type -> light
            1110 to night(), // 18:30 -- black
        )

        /** The palette at [minuteOfDay] (values outside 0..1439 wrap). */
        fun at(minuteOfDay: Int): DayPalette {
            val minute = Math.floorMod(minuteOfDay, DAY)
            var lower = 0
            for (i in keyframes.indices) if (keyframes[i].first <= minute) lower = i
            val upper = (lower + 1) % keyframes.size
            val from = keyframes[lower].first
            val to = keyframes[upper].first + if (upper == 0) DAY else 0
            val span = to - from
            val t = if (span <= 0) 0f else (minute - from).toFloat() / span
            val a = keyframes[lower].second
            val b = keyframes[upper].second
            return DayPalette(
                blend(a.top, b.top, t),
                blend(a.middle, b.middle, t),
                blend(a.bottom, b.bottom, t),
            )
        }

        private fun blend(from: Int, to: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private const val DAY = 24 * 60

        private fun java.time.LocalTime.minuteOfDay() = hour * 60 + minute
    }
}
