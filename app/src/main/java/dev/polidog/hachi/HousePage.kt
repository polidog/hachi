package dev.polidog.hachi

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat

/**
 * Rooms → devices → controls. A device that is only on or off is switched from its card; the
 * rest open a page of controls. A room with an air conditioner opens on its controls.
 *
 * A page of the pager, swiped to like the weather.
 */
class HousePage(context: Context, private val house: House) : FrameLayout(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isScrollbarFadingEnabled = false
        addView(content, LayoutParams(FILL, WRAP))
    }
    private var area: String? = null
    private var selected: DeviceKey? = null

    init {
        setPadding(context.dp(28), context.dp(54), context.dp(28), context.dp(12))
        setBackgroundColor(INK)
        // Keep the reserved chrome outside the scrolling viewport. Padding on ScrollView itself
        // can make short overflows fail its touch-scroll range check on this Android version.
        clipToPadding = true
        addView(scroll, LayoutParams(FILL, FILL))
    }

    /** Back at the room list: coming back to it should not resume someone else's half-press. */
    fun reset() {
        area = null
        selected = null
        navigate()
    }

    fun back(): Boolean {
        when {
            selected != null -> selected = null
            area != null -> area = null
            else -> return false
        }
        bind()
        scroll.scrollTo(0, 0)
        return true
    }

    fun bind() {
        val devices = tiles(house.devices)
        // Keep the chosen room/device through refreshes, but leave a removed device gracefully.
        if (!house.failed && devices.isNotEmpty()) {
            if (area != null && devices.none { it.area == area }) { area = null; selected = null }
            if (selected != null && devices.none { it.key == selected }) selected = null
        }
        content.removeAllViews()
        val device = devices.firstOrNull { it.key == selected }
        header(device)
        if (devices.isEmpty()) {
            content.addView(label(context.getString(when {
                !house.configured -> R.string.house_unconfigured
                house.failed -> R.string.house_unavailable
                else -> R.string.house_empty
            }), 15f))
            return
        }
        when {
            device != null -> detail(device)
            area != null -> room(devices.filter { it.area == area })
            else -> rooms(devices, house.devices)
        }
    }

    /** Nothing over the rooms -- the cards say what they are. Below them, the way back and a name. */
    private fun header(device: Device?) {
        if (area == null) return
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(context).apply {
                text = context.getString(R.string.house_back)
                isAllCaps = false
                textSize = 15f
                setTextColor(MUTED)
                background = RippleDrawable(PRESS, null, pill(context.dp(24).toFloat(), Color.WHITE))
                stateListAnimator = null
                minWidth = 0
                minimumWidth = 0
                setPadding(context.dp(4), 0, context.dp(12), 0)
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(WRAP, context.dp(48)).apply { marginEnd = context.dp(12) })
            addView(label(device?.name ?: roomName(area!!), 23f).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            air(house.devices.filter { it.area == area })
        }, LinearLayout.LayoutParams(FILL, WRAP).apply { bottomMargin = context.dp(12) })
    }

    /** The rooms as a row of cards, scrolled sideways: six of them will not share this screen. */
    private fun rooms(devices: List<Device>, everything: List<Device>) =
        carousel(devices.groupBy { it.area }.map { (room, members) ->
            roomCard(room, members, everything.filter { it.area == room })
        }, 280)

    /**
     * A row of cards taking whatever height the page has, so they never push it into a scroll;
     * more than fit are reached sideways.
     */
    private fun carousel(cards: List<View>, width: Int) {
        val row = LinearLayout(context)
        cards.forEach { row.addView(it, LinearLayout.LayoutParams(context.dp(width), FILL).apply { marginEnd = context.dp(16) }) }
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row, LayoutParams(WRAP, FILL))
        }, LinearLayout.LayoutParams(FILL, 0, 1f))
    }

    /**
     * A room as the reference's profile card: a picture fading into the card, the room's mark
     * standing on its edge, the name, what is in it, and the numbers in a row with rules between.
     */
    private fun roomCard(room: String, members: List<Device>, everything: List<Device>) = FrameLayout(context).apply {
        val on = members.count { it.isOn }
        val name = roomName(room)
        background = RippleDrawable(PRESS, RoomBackdrop(context, room.hashCode()), null)

        // Everything stands on the bottom edge; the picture takes whatever height is left above.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), 0, context.dp(14), context.dp(14))
            // The mark, and beside it the room's air when something in it is measuring.
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    background = pill(context.dp(26).toFloat(), SURFACE)
                    addView(HouseGlyph(context, "room", if (on > 0) ACCENT_INK else MUTED),
                        LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(context.dp(52), context.dp(52)).apply { marginEnd = context.dp(4) })
                air(everything)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = context.dp(10) })
            addView(label(name, 19f).apply { maxLines = 1; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(label(members.joinToString("・") { it.name }, 11f, MUTED).apply {
                maxLines = 1
                setPadding(0, context.dp(3), 0, 0)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(14), 0, 0)
                addView(figure(on.toString(), context.getString(R.string.house_stat_on), if (on > 0) ACCENT_INK else TEXT))
                addView(rule())
                addView(figure(members.size.toString(), context.getString(R.string.house_stat_devices)))
                addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                addView(label(context.getString(R.string.house_open_room), 13f, INK).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                    background = pill(context.dp(20).toFloat(), TEXT)
                })
            })
        }, LayoutParams(FILL, WRAP, Gravity.BOTTOM))

        contentDescription = "$name, " + context.getString(R.string.house_device_count, members.size)
        isFocusable = true
        setOnClickListener { area = room; selected = null; navigate() }
    }

    /**
     * A room's air, each figure after a rule: a thermometer in it, or else what the air conditioner
     * feels, and a hygrometer. Nothing when nothing there is measuring.
     */
    private fun LinearLayout.air(everything: List<Device>) {
        val indoor = everything.firstOrNull { it.measures("temperature") }?.let { number(it.state.toDouble()) + (it.unit ?: "°") }
            ?: everything.firstNotNullOfOrNull { it.climate?.current?.let { t -> degrees(t, it) } }
        val humidity = everything.firstOrNull { it.measures("humidity") }?.let { number(it.state.toDouble()) + "%" }
        indoor?.let { addView(rule()); addView(figure(it, context.getString(R.string.house_room))) }
        humidity?.let { addView(rule()); addView(figure(it, context.getString(R.string.house_humidity))) }
    }

    /** One number of a room card: the value over a small grey caption. */
    private fun figure(value: String, caption: String, color: Int = TEXT) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label(value, 15f, color).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        addView(label(caption, 10f, MUTED).apply { setPadding(0, context.dp(3), 0, 0) })
    }

    private fun rule() = View(context).apply { setBackgroundColor(HAIRLINE_STRONG) }.also {
        it.layoutParams = LinearLayout.LayoutParams(context.dp(1), context.dp(28)).apply {
            setMargins(context.dp(12), 0, context.dp(12), 0)
        }
    }

    private fun deviceList(devices: List<Device>) = carousel(devices.map(::deviceCard), 196)

    /**
     * A room with an air conditioner opens on its controls, since that is what it is visited for;
     * the rest of the room waits in a row of pills underneath.
     */
    private fun room(members: List<Device>) {
        val ac = members.firstOrNull { it.domain == "climate" } ?: return deviceList(members)
        detail(ac)
        val others = members - ac
        if (others.isEmpty()) return
        val row = LinearLayout(context)
        others.forEach { row.addView(chip(it), LinearLayout.LayoutParams(WRAP, context.dp(52)).apply { marginEnd = context.dp(10) }) }
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row)
        }, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = context.dp(12) })
    }

    /** A device as a pill: its mark, its name, what it is doing. A tap does what its card would. */
    private fun chip(device: Device) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14), 0, context.dp(20), 0)
        background = RippleDrawable(PRESS, pill(context.dp(26).toFloat(), SURFACE), null)
        val tone = if (device.isOn) ACCENT_INK else MUTED
        addView(HouseGlyph(context, device.domain, tone),
            LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(device.name, 14f).apply { maxLines = 1 })
            addView(label(status(device), 11f, tone).apply { maxLines = 1; setPadding(0, context.dp(2), 0, 0) })
        })
        contentDescription = "${device.name}, ${status(device)}"
        isFocusable = true
        setOnClickListener { press(device) }
    }

    /** Only a thermostat has more to it than on and off. A state the house does not know still opens,
     * so the page can offer both presses instead of guessing one. */
    private fun switchable(device: Device) = device.domain != "climate" && device.state != "unknown"

    private fun press(device: Device) =
        if (switchable(device)) house.toggle(device) else { selected = device.key; navigate() }

    private fun status(device: Device) =
        if (house.busy(device)) context.getString(R.string.house_sending) else state(device)

    /**
     * A device as a card lit by its own state: the glyph standing in a pool of its light, the name,
     * and what it is doing now. Opening it is still the only thing a tap does.
     */
    private fun deviceCard(device: Device) = FrameLayout(context).apply {
        val switch = switchable(device)
        background = RippleDrawable(PRESS, backdrop(device), null)
        addView(HouseGlyph(context, device.domain, if (device.isOn) ACCENT_INK else MUTED),
            LayoutParams(context.dp(52), context.dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = context.dp(30)
            })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), 0, context.dp(14), context.dp(14))
            addView(label(device.name, 16f).apply { maxLines = 1; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(12), 0, 0)
                addView(label(status(device), 12f, if (device.isOn) ACCENT_INK else MUTED).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                if (!switch) addView(label("›", 18f, INK).apply {
                    gravity = Gravity.CENTER
                    background = pill(context.dp(18).toFloat(), TEXT)
                }, LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
            })
        }, LayoutParams(FILL, WRAP, Gravity.BOTTOM))
        contentDescription = "${device.name}, ${state(device)}"
        isFocusable = true
        setOnClickListener { press(device) }
    }

    /** The light a device gives off on its card: lamplight, the air conditioner's mode, or none. */
    private fun backdrop(device: Device, centre: Float = 0.26f) = DeviceBackdrop(context, when {
        device.domain == "climate" -> modeTones(if (device.available) device.state else "off")
        device.isOn -> LAMP_TONES
        else -> modeTones("off")
    }, device.isOn, centre)

    private fun navigate() { bind(); scroll.scrollTo(0, 0) }
    private fun roomName(value: String) = value.ifBlank { context.getString(R.string.house_unassigned) }

    private fun state(device: Device): String = when {
        !device.available || device.state == "unknown" -> context.getString(R.string.house_unknown)
        device.domain == "climate" -> buildString {
            append(context.getString(hvacLabel(device.state)))
            device.setpoint?.let { append(" · ${degrees(it, device)}") }
        }
        device.domain == "cover" -> context.getString(if (device.isOn) R.string.house_open else R.string.house_closed)
        else -> context.getString(if (device.isOn) R.string.house_on else R.string.house_off)
    }

    private fun detail(device: Device) {
        val enabled = device.available && !house.busy(device)
        val status = status(device)
        if (device.domain == "climate") {
            if (house.busy(device)) content.addView(label(status, 12f, MUTED).apply { setPadding(0, 0, 0, context.dp(6)) })
            climateDetail(device, enabled)
            return
        }
        val cover = device.domain == "cover"
        val tone = if (device.isOn) ACCENT_INK else MUTED
        val buttons = LinearLayout(context).apply {
            // Only the press that would change something: the state it is already in is the line
            // above, not a button. Unknown offers both.
            listOf(true, false).filter { device.state == "unknown" || device.isOn != it }.forEach { on ->
                val title = context.getString(if (cover) {
                    if (on) R.string.house_open else R.string.house_close
                } else if (on) R.string.house_turn_on else R.string.house_turn_off)
                addView(action(title, enabled, primary = true) {
                    house.setPower(device, on)
                }, LinearLayout.LayoutParams(context.dp(180), context.dp(56)).apply { marginEnd = context.dp(12) })
            }
        }
        // The device card grown large on the left, lit as it is; what it is doing and the press
        // that changes it on the right.
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                background = backdrop(device, 0.5f)
                addView(HouseGlyph(context, device.domain, tone), LayoutParams(context.dp(96), context.dp(96), Gravity.CENTER))
            }, LinearLayout.LayoutParams(context.dp(300), FILL).apply { marginEnd = context.dp(36) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(status, 34f, tone).apply {
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    setPadding(0, 0, 0, context.dp(20))
                })
                addView(buttons)
            })
        }, LinearLayout.LayoutParams(FILL, 0, 1f))
    }

    /**
     * The readings down the left, as the reference's row of three turned on its side; the fan of
     * modes and the dial on the right.
     *
     * A thermostat working to a low/high range has two targets and one needle will not say that, so
     * it keeps the two steppers where the others get the dial -- the fan of modes stays either way.
     */
    private fun climateDetail(device: Device, enabled: Boolean) {
        val climate = device.climate
        if (climate == null) {
            content.addView(label(context.getString(R.string.house_climate_unavailable), 14f, MUTED))
            content.addView(action(context.getString(if (device.isOn) R.string.house_turn_off else R.string.house_turn_on), enabled) {
                house.toggle(device)
            }, LinearLayout.LayoutParams(context.dp(160), context.dp(52)))
            return
        }
        val range = climate.features and 2 != 0 && (device.state == "heat_cool" || climate.features and 1 == 0)
        val single = !range && climate.features and 1 != 0
        val readings = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(stat(R.string.house_mode, context.getString(hvacLabel(device.state))))
            if (range) {
                temperature(this, device, climate.low, "target_temp_low", R.string.house_low_temperature, enabled)
                temperature(this, device, climate.high, "target_temp_high", R.string.house_high_temperature, enabled)
            } else if (!single) {
                addView(label(context.getString(R.string.house_no_temperature), 13f, MUTED))
            }
        }
        val title = context.getString(R.string.house_target_temperature)
        val step = { direction: Int -> device.setpoint?.let { climate.shifted(it, direction) } }
        val dial = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            // The dial is dragged; these are the same thing for a finger that would rather tap, and
            // for anything reading the screen out loud.
            if (single) addView(round("−", context.getString(R.string.house_decrease, title),
                enabled && step(-1) != device.setpoint) { house.setTemperature(device, -1) })
            addView(ClimateDial(
                context,
                modes = climate.modes,
                mode = device.state,
                modeLabel = { context.getString(hvacLabel(it)) },
                value = if (single) device.setpoint else climate.current,
                climate = climate,
                caption = context.getString(if (single) R.string.house_target_temperature else R.string.house_room),
                enabled = enabled,
                onMode = { house.setMode(device, it) },
                onTarget = { if (single) house.setTemperatureTo(device, it) },
            ), LinearLayout.LayoutParams(context.dp(300), FILL))
            if (single) addView(round("+", context.getString(R.string.house_increase, title),
                enabled && step(1) != device.setpoint) { house.setTemperature(device, 1) })
        }
        // Whatever height the page has left goes to the dial: the viewport is filled, so the weight
        // is measured against the screen and not against the dial's own wish.
        content.addView(LinearLayout(context).apply {
            addView(readings, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dial, LinearLayout.LayoutParams(WRAP, FILL))
        }, LinearLayout.LayoutParams(FILL, 0, 1f))
    }

    /** One reading: a small grey caption over a large plain number, the reference's stat. */
    private fun stat(title: Int, value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, context.dp(14))
        addView(label(context.getString(title), 12f, MUTED))
        addView(label(value, 26f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, context.dp(4), 0, 0)
        })
    }

    /** A round outlined button carrying one glyph. */
    private fun round(glyph: String, description: String, enabled: Boolean, click: () -> Unit) = Button(context).apply {
        text = glyph
        textSize = 22f
        setTextColor(TEXT)
        background = RippleDrawable(PRESS, pill(context.dp(26).toFloat(), Color.TRANSPARENT, context.dp(1), HAIRLINE_STRONG), null)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        contentDescription = description
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
        setOnClickListener { click() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(context.dp(52), context.dp(52)).apply {
            setMargins(context.dp(10), 0, context.dp(10), 0)
        }
    }

    private fun temperature(parent: LinearLayout, device: Device, value: Double?, field: String, title: Int, enabled: Boolean) {
        parent.addView(label(context.getString(title), 13f, MUTED))
        parent.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            fun canStep(direction: Int): Boolean {
                val climate = device.climate ?: return false
                val next = value?.let { climate.shifted(it, direction) } ?: return false
                return enabled && next != value && when (field) {
                    "target_temp_low" -> climate.high?.let { next <= it } == true
                    "target_temp_high" -> climate.low?.let { next >= it } == true
                    else -> true
                }
            }
            addView(action("−", canStep(-1)) { house.setTemperature(device, -1, field) }.apply {
                contentDescription = context.getString(R.string.house_decrease, context.getString(title))
            }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            addView(label(value?.let { degrees(it, device) } ?: "—", 28f).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, context.dp(54), 1f))
            addView(action("+", canStep(1)) { house.setTemperature(device, 1, field) }.apply {
                contentDescription = context.getString(R.string.house_increase, context.getString(title))
            }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        })
    }

    private fun degrees(value: Double, device: Device) = number(value) + (device.climate?.unit ?: "°")

    private fun number(value: Double) = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)

    /**
     * A pill: outlined for the small steps, filled dark for the one press a page is for -- the same
     * dark pill the cards open with.
     */
    private fun action(title: String, enabled: Boolean = true, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) INK else TEXT)
        val radius = context.dp(RADIUS).toFloat()
        background = RippleDrawable(PRESS,
            if (primary) pill(radius, TEXT) else pill(radius, Color.TRANSPARENT, context.dp(1), HAIRLINE_STRONG),
            pill(radius, Color.WHITE))
        stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0
        minimumWidth = 0
        setPadding(context.dp(8), 0, context.dp(8), 0)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { click() }
    }

    private fun label(value: String, size: Float, color: Int = TEXT) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private val PRESS get() = ColorStateList.valueOf(HAIRLINE_STRONG)

}

/** The mode a thermostat is in, as Home Assistant spells it. */
fun hvacLabel(state: String): Int = when (state) {
    "heat" -> R.string.hvac_heat
    "cool" -> R.string.hvac_cool
    "heat_cool" -> R.string.hvac_heat_cool
    "auto" -> R.string.hvac_auto
    "dry" -> R.string.hvac_dry
    "fan_only" -> R.string.hvac_fan_only
    "off" -> R.string.hvac_off
    else -> R.string.house_unknown
}
