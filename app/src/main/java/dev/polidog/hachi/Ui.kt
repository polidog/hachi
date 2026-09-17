package dev.polidog.hachi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** A translucent rounded background, used for the controls that float over the clock. */
fun pill(radiusPx: Float, fill: Int = SCRIM, strokeWidthPx: Int = 0, stroke: Int = HAIRLINE) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fill)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, stroke)
    }

val SCRIM: Int = Color.argb(0x4D, 0x0A, 0x0E, 0x1A)
val HAIRLINE: Int = Color.argb(0x3D, 0xFF, 0xFF, 0xFF)
val CREAM: Int = Color.rgb(0xFA, 0xF6, 0xEC)
val CREAM_60: Int = Color.argb(0x99, 0xFA, 0xF6, 0xEC)
