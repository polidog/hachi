package dev.polidog.hachi

/**
 * The colours of the backdrop at one time of day: three gradient stops, top to bottom.
 *
 * Keyframes are interpolated per channel, so the screen drifts through dawn and dusk rather than
 * snapping between looks. The day wraps, so the last keyframe interpolates back toward midnight.
 *
 * All of them are paper: the range is a warm noon down to a cooled, dimmed evening, never dark. A
 * wall this bright at 3am is the price of the light look, and the dimming here is all of the relief
 * there is.
 */
class DayPalette(val top: Int, val middle: Int, val bottom: Int) {
    companion object {
        /** Dusk and the small hours: the same paper, dimmed and cooled. Never black -- see [INK]. */
        private fun night() = DayPalette(0xFFC8C6C1.toInt(), 0xFFD2D0CB.toInt(), 0xFFDAD8D3.toInt())

        /** (minuteOfDay, palette), ascending. Exposed so the interpolation can be tested. */
        val keyframes: List<Pair<Int, DayPalette>> = listOf(
            0 to night(),
            270 to night(), // 04:30 -- holds the night look until it starts to lift
            360 to DayPalette(0xFFE8DCD0.toInt(), 0xFFEFE3D6.toInt(), 0xFFF3E8DA.toInt()), // 06:00 dawn
            480 to DayPalette(0xFFE7E6E2.toInt(), 0xFFEDECE8.toInt(), 0xFFF1F0EC.toInt()), // 08:00 morning
            720 to DayPalette(0xFFEBEAE6.toInt(), 0xFFF2F1ED.toInt(), 0xFFF6F5F1.toInt()), // 12:00 noon
            960 to DayPalette(0xFFEAE7E0.toInt(), 0xFFF0EDE6.toInt(), 0xFFF4F1EA.toInt()), // 16:00 afternoon
            1080 to DayPalette(0xFFE4D6C8.toInt(), 0xFFE9DCCE.toInt(), 0xFFEDE2D5.toInt()), // 18:00 evening
            1200 to night(), // 20:00 -- interpolates into the identical midnight keyframe
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
    }
}
