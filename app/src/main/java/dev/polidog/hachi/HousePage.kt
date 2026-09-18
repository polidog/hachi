package dev.polidog.hachi

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat

/**
 * Rooms → devices → controls. Selecting a card never sends a command to the house.
 *
 * It lifts over whatever page is showing rather than living in the pager: the house is something
 * you come to on purpose, by the Devices button, not something a stray swipe lands you on.
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
        setPadding(context.dp(28), context.dp(54), context.dp(28), context.dp(92))
        setBackgroundColor(INK)
        // Swallows the taps that would otherwise reach the page underneath, including the swipe
        // that would turn it.
        isClickable = true
        // Keep the reserved chrome outside the scrolling viewport. Padding on ScrollView itself
        // can make short overflows fail its touch-scroll range check on this Android version.
        clipToPadding = true
        addView(scroll, LayoutParams(FILL, FILL))
        alpha = 0f
        visibility = GONE
    }

    val showing get() = visibility == VISIBLE

    /** Fades in at the room list: coming back to it should not resume someone else's half-press. */
    fun show() {
        area = null
        selected = null
        bind()
        scroll.scrollTo(0, 0)
        visibility = VISIBLE
        translationY = context.dp(14).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(220)
    }

    fun hide() {
        animate().alpha(0f).translationY(context.dp(8).toFloat()).setDuration(160)
            .withEndAction { visibility = GONE }
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
        header(device, devices)
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
            area != null -> deviceList(devices.filter { it.area == area })
            else -> rooms(devices)
        }
    }

    private fun header(device: Device?, devices: List<Device>) {
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (area != null) addView(action(context.getString(R.string.house_back)) { back() },
                LinearLayout.LayoutParams(context.dp(78), context.dp(48)).apply { marginEnd = context.dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(context.getString(R.string.action_devices).uppercase(), 10f, MUTED).apply {
                    letterSpacing = 0.18f
                })
                addView(label(device?.name ?: area?.let(::roomName) ?: context.getString(R.string.house_rooms), 23f).apply {
                    maxLines = 1
                })
                addView(label(when {
                    device != null -> roomName(device.area)
                    area != null -> context.getString(R.string.house_choose_device)
                    else -> context.getString(R.string.house_choose_room)
                }, 11f, MUTED))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (device == null && devices.isNotEmpty()) addView(label(
                context.getString(R.string.house_active_count, devices.count { it.isOn && (area == null || it.area == area) }), 12f, ON_LIME,
            ).apply {
                setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
                background = pill(context.dp(18).toFloat(), LIME)
            })
        }, LinearLayout.LayoutParams(FILL, WRAP).apply { bottomMargin = context.dp(8) })
    }

    private fun rooms(devices: List<Device>) {
        val grid = grid(3)
        devices.groupBy { it.area }.forEach { (room, members) ->
            grid.addView(card(roomName(room), context.getString(R.string.house_device_count, members.size),
                members.any { it.isOn }, "room") { area = room; selected = null; navigate() }, cell())
        }
        content.addView(grid)
    }

    private fun deviceList(devices: List<Device>) {
        val grid = grid(3)
        devices.forEach { device ->
            grid.addView(card(device.name, state(device), device.isOn, device.domain) {
                selected = device.key
                navigate()
            }, cell())
        }
        content.addView(grid)
    }

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
        content.addView(label(if (house.busy(device)) context.getString(R.string.house_sending) else state(device),
            12f, if (device.isOn) LIME else MUTED).apply {
                setPadding(context.dp(2), 0, 0, context.dp(6))
            })
        if (device.domain == "climate") {
            climateDetail(device, enabled)
        } else {
            val cover = device.domain == "cover"
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = panel()
                setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(18))
                addView(HouseGlyph(context, device.domain, if (device.isOn) LIME else MUTED),
                    LinearLayout.LayoutParams(context.dp(64), context.dp(64)).apply { marginEnd = context.dp(20) })
            }
            listOf(true, false).forEach { on ->
                val title = context.getString(if (cover) {
                    if (on) R.string.house_open else R.string.house_close
                } else if (on) R.string.house_turn_on else R.string.house_turn_off)
                row.addView(action(title, enabled && (device.state == "unknown" || device.isOn != on), device.state != "unknown" && device.isOn == on) {
                    house.setPower(device, on)
                }, LinearLayout.LayoutParams(0, context.dp(64), 1f).apply { setMargins(context.dp(6), 0, context.dp(6), 0) })
            }
            content.addView(row)
        }
    }

    private fun climateDetail(device: Device, enabled: Boolean) {
        val climate = device.climate
        if (climate == null) {
            content.addView(label(context.getString(R.string.house_climate_unavailable), 14f, MUTED))
            content.addView(action(context.getString(if (device.isOn) R.string.house_turn_off else R.string.house_turn_on), enabled) {
                house.toggle(device)
            }, LinearLayout.LayoutParams(context.dp(160), context.dp(52)))
            return
        }
        val panels = LinearLayout(context).apply { gravity = Gravity.TOP }
        val temperatures = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        }
        climate.current?.let {
            temperatures.addView(label(context.getString(R.string.house_current_temperature, degrees(it, device)), 12f, MUTED).apply {
                gravity = Gravity.CENTER
            })
        }
        val range = climate.features and 2 != 0 && (device.state == "heat_cool" || climate.features and 1 == 0)
        if (range) {
            temperature(temperatures, device, climate.low, "target_temp_low", R.string.house_low_temperature, enabled, compact = true)
            temperature(temperatures, device, climate.high, "target_temp_high", R.string.house_high_temperature, enabled, compact = true)
        } else if (climate.features and 1 != 0) {
            temperature(temperatures, device, device.setpoint, "temperature", R.string.house_target_temperature, enabled)
        } else {
            temperatures.addView(label(context.getString(R.string.house_no_temperature), 13f, MUTED))
        }
        panels.addView(temperatures, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = context.dp(14) })
        panels.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(4), 0, 0)
            addView(label(context.getString(R.string.house_mode), 12f, MUTED).apply {
                setPadding(context.dp(4), 0, 0, context.dp(6))
            })
            addView(grid(3).apply {
                climate.modes.forEach { mode ->
                    addView(action(context.getString(hvacLabel(mode)), enabled && device.state != mode, device.state == mode) {
                        house.setMode(device, mode)
                    }, cell().apply { height = context.dp(48) })
                }
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1.2f))
        content.addView(panels)
    }

    private fun temperature(parent: LinearLayout, device: Device, value: Double?, field: String, title: Int, enabled: Boolean, compact: Boolean = false) {
        parent.addView(label(context.getString(title), 11f, MUTED).apply { gravity = Gravity.CENTER })
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
            addView(FrameLayout(context).apply {
                if (!compact) addView(TemperatureArc(context, value, device.climate, device.isOn), FrameLayout.LayoutParams(FILL, FILL))
                addView(label(value?.let { degrees(it, device) } ?: "—", if (compact) 28f else 38f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                }, FrameLayout.LayoutParams(FILL, FILL))
            }, LinearLayout.LayoutParams(0, context.dp(if (compact) 54 else 118), 1f))
            addView(action("+", canStep(1)) { house.setTemperature(device, 1, field) }.apply {
                contentDescription = context.getString(R.string.house_increase, context.getString(title))
            }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        })
    }

    private fun degrees(value: Double, device: Device) =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value) + (device.climate?.unit ?: "°")

    private fun card(title: String, subtitle: String, active: Boolean, domain: String, click: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Every card wears the same face. What is on is said by the glyph and the dot, not by
            // lighting up a third of the screen -- eleven lit cards say nothing at all.
            background = touchPanel(false)
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
            minimumHeight = context.dp(82)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(HouseGlyph(context, domain, if (active) LIME else MUTED),
                    LinearLayout.LayoutParams(context.dp(22), context.dp(22)))
                addView(label(if (active) "●" else "○", 10f, if (active) LIME else MUTED).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            })
            addView(label(title, 16f).apply {
                maxLines = 1
                setPadding(0, context.dp(5), 0, context.dp(2))
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(subtitle, 11f, MUTED).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(label("›", 14f, MUTED))
            })
            contentDescription = "$title, $subtitle"
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun action(title: String, enabled: Boolean = true, active: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        // The state the device is already in is outlined in the accent, not filled with it: a
        // filled button asks to be pressed, and that one is the one press that would do nothing.
        setTextColor(if (active) LIME else TEXT)
        background = touchPanel(active)
        stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0
        minimumWidth = 0
        setPadding(context.dp(8), 0, context.dp(8), 0)
        isEnabled = enabled
        isSelected = active
        alpha = if (enabled || active) 1f else 0.45f
        setOnClickListener { click() }
    }

    private fun panel(active: Boolean = false) = context.card(active)

    private fun touchPanel(active: Boolean) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(50, 0xC8, 0xF2, 0x4E)), panel(active),
        pill(context.dp(RADIUS).toFloat(), Color.WHITE),
    )

    private fun label(value: String, size: Float, color: Int = TEXT) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun grid(columns: Int) = GridLayout(context).apply { columnCount = columns }
    private fun cell() = GridLayout.LayoutParams().apply {
        width = 0
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
    }

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
