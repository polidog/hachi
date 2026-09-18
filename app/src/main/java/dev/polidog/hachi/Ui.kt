package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup

/** Kotlin will not resolve these inherited Java constants unqualified, and -1/-2 read as nothing. */
const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** A rounded panel: the one shape everything on this screen is made of. */
fun pill(radiusPx: Float, fill: Int = SURFACE, strokeWidthPx: Int = 0, stroke: Int = HAIRLINE) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fill)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, stroke)
    }

/**
 * The whole screen's palette: warm off-white paper, near-black type, one yellow accent.
 *
 * Paper rather than screen. Nothing on a wall display needs a box drawn around it -- the space
 * between things says what a border used to, and what is left has to earn its ink. One accent and
 * nothing else coloured is what makes the lit thing on a page -- the device that is on, the rain
 * that is coming, today's date -- findable from across the room.
 */
val INK: Int = Color.rgb(0xE9, 0xE7, 0xE2)
/** A panel that genuinely has to be told apart from the paper it sits on, and nothing else. */
val SURFACE: Int = Color.rgb(0xF4, 0xF2, 0xED)
/** A panel that is on, or selected: the same surface with a little of the accent stirred in. */
val SURFACE_ON: Int = Color.rgb(0xFA, 0xF1, 0xCF)
/** The accent as a fill: the talk button, today, the thing that is on. */
val ACCENT: Int = Color.rgb(0xFA, 0xD9, 0x4B)
/**
 * The accent as type or a hairline. Yellow is a fill colour only -- set as text on paper it all but
 * disappears -- so anything thin that should read as the accent is set in this deep ochre instead.
 */
val ACCENT_INK: Int = Color.rgb(0x8F, 0x6B, 0x00)
/** Type over the accent. Yellow is light enough that it takes dark type, never white. */
val ON_ACCENT: Int = Color.rgb(0x16, 0x15, 0x12)
val TEXT: Int = Color.rgb(0x16, 0x15, 0x12)
val MUTED: Int = Color.rgb(0x8C, 0x88, 0x7E)
val HAIRLINE: Int = Color.argb(0x1A, 0x00, 0x00, 0x00)
/** The corner every panel shares. */
const val RADIUS = 24

/** The face the big numbers are set in: one weight, tight, nothing decorative. */
val DISPLAY: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

/**
 * A panel, for the few things that are actually objects you press.
 *
 * Flat on purpose. The gradient and hairline this used to carry were drawing a box around content
 * that reads perfectly well without one; what is left is the faintest lift off the paper, so a
 * tappable tile still looks like a tile and a paragraph of text does not.
 */
fun Context.card(active: Boolean = false, radius: Int = RADIUS) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radius).toFloat()
    setColor(if (active) SURFACE_ON else SURFACE)
    if (active) setStroke(dp(1), ACCENT_INK)
}
