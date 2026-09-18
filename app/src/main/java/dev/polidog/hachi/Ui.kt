package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
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
 * The whole screen's palette: near-black panels, off-white type, one lime accent.
 *
 * One accent and nothing else coloured is what makes the lit thing on a page -- the device that is
 * on, the rain that is coming, the button worth pressing -- findable from across the room.
 */
val INK: Int = Color.rgb(0x0C, 0x0E, 0x0C)
/** A card at rest, and the fill of the controls that float over the sky. */
val SURFACE: Int = Color.rgb(0x1A, 0x1D, 0x19)
/** A card that is on, or selected: the same surface with a little of the accent stirred in. */
val SURFACE_ON: Int = Color.rgb(0x25, 0x2E, 0x1C)
val LIME: Int = Color.rgb(0xC8, 0xF2, 0x4E)
/** Type over the accent. Lime is bright enough that it takes dark type, never white. */
val ON_LIME: Int = Color.rgb(0x10, 0x14, 0x0B)
val TEXT: Int = Color.rgb(0xF2, 0xF4, 0xEE)
val MUTED: Int = Color.rgb(0x93, 0x9B, 0x8F)
val HAIRLINE: Int = Color.argb(0x1F, 0xFF, 0xFF, 0xFF)
/** The corner every card shares. */
const val RADIUS = 24

/** A card's face, top to bottom: lit a little at the top, as if the light came from above. */
private val CARD_TOP: Int = Color.rgb(0x22, 0x26, 0x20)
private val CARD_BOTTOM: Int = Color.rgb(0x13, 0x16, 0x12)
private val CARD_ON_TOP: Int = Color.rgb(0x2C, 0x36, 0x1E)
private val CARD_ON_BOTTOM: Int = Color.rgb(0x1A, 0x20, 0x12)

/**
 * The one card in the app: opaque, a shade lighter at the top, with a hairline around it.
 *
 * Opaque on purpose. A translucent panel over the sky picks up whatever cloud is drifting behind it,
 * and a card whose own colour moves is the thing that reads as grubby rather than as an object.
 */
fun Context.card(active: Boolean = false, radius: Int = RADIUS) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    if (active) intArrayOf(CARD_ON_TOP, CARD_ON_BOTTOM) else intArrayOf(CARD_TOP, CARD_BOTTOM),
).apply {
    cornerRadius = dp(radius).toFloat()
    setStroke(dp(1), if (active) LIME else HAIRLINE)
}
