package dev.polidog.hachi

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.polidog.hachi.tools.McpServer
import dev.polidog.hachi.tools.ClimateControls
import dev.polidog.hachi.tools.ClimateState
import dev.polidog.hachi.tools.withClimateDetails
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** One thing in the house, as Home Assistant's live context describes it. */
data class Device(
    val name: String,
    val domain: String,
    val state: String,
    val area: String,
    /** What a thermostat is set to, when it is one. */
    val setpoint: Double? = null,
    val climate: ClimateState? = null,
    /** A sensor's `unit_of_measurement` and `device_class`, when the house gives them. */
    val unit: String? = null,
    val kind: String? = null,
)

data class DeviceKey(val area: String, val domain: String, val name: String)
val Device.key get() = DeviceKey(area, domain, name)
val Device.available get() = state != "unavailable"

/** A thermostat says which mode it is in rather than on or off, and only `off` means off. */
val Device.isOn: Boolean
    get() = if (domain == "climate") state !in setOf("off", "unknown", "unavailable")
    else state == "on" || state == "open"

/**
 * Whether this is a sensor reading [what] (`temperature`, `humidity` or `carbon_dioxide`). The class
 * is not always listed, so a thermometer is also known by its unit, and the others by unit and name.
 */
fun Device.measures(what: String) = domain == "sensor" && state.toDoubleOrNull() != null &&
    (kind == what || kind == null && when (what) {
        "temperature" -> unit == "°C" || unit == "°F"
        "humidity" -> unit == "%" && ("湿度" in name || "humid" in name.lowercase())
        else -> unit == "ppm" && ("二酸化炭素" in name || "co2" in name.lowercase() || "CO₂" in name)
    })

/** The domains a tile can work. Everything else the house lists is for the conversation to read. */
private val TILES = setOf("light", "switch", "fan", "input_boolean", "cover", "climate")

/**
 * One tile per room and name.
 *
 * An air conditioner arrives twice: as the thermostat that knows the mode and the temperature, and
 * as an infrared switch of the same name whose state is `unknown`. The thermostat is the one worth
 * a tile -- and the duplicate name is also why a tap has to say which domain it means.
 */
fun tiles(devices: List<Device>): List<Device> =
    devices.filter { it.domain in TILES }
        .groupBy { it.area to it.name }
        .map { (_, twins) -> twins.firstOrNull { it.domain == "climate" } ?: twins.first() }

/** MCP supplies the device list; REST adds the climate capabilities and detailed controls. */
class House(private val settings: Settings) {
    private val main = Handler(Looper.getMainLooper())

    /** What the house last said, before a tap that it has not caught up with yet. */
    @Volatile
    private var listed: List<Device> = emptyList()

    /** What the tiles draw: the listing, with any tap still being waited on written over it. */
    @Volatile
    var devices: List<Device> = emptyList()
        private set

    /** True when the house was asked and did not answer, as opposed to having nothing to show. */
    var failed = false
        private set

    var onUpdate: (() -> Unit)? = null

    /**
     * What a tap asked for, held over what the house says until the house catches up.
     *
     * Nothing pushes a state change here, and a light took 19 seconds to be reported as lit, so any
     * refresh in between -- opening the page, a tool call in a conversation -- would otherwise flip
     * the tile back to the state from before the tap.
     */
    private val asked = ConcurrentHashMap<DeviceKey, Pair<Device, Long>>()
    private val pending = ConcurrentHashMap.newKeySet<DeviceKey>()
    private val climate = ClimateControls(settings)
    var onError: (() -> Unit)? = null
    fun busy(device: Device) = device.key in pending

    val configured: Boolean
        get() = settings.homeAssistantUrl.isNotBlank() && settings.homeAssistantToken.isNotBlank()

    /** Blocking -- network. Asks the house what it has, then tells the screen. */
    @Synchronized
    fun refresh() {
        val server = McpServer.from(settings)
        val tool = toolNamed("GetLiveContext")
        if (server == null) return publish(emptyList(), failed = false)
        // No cached tool list means the house has not answered since setup, which is a failure to
        // reach it rather than an empty house.
        if (tool == null) return publish(emptyList(), failed = true)
        val answer = server.call(tool, JSONObject()).optString("result")
        if (answer.isBlank()) return publish(emptyList(), failed = true)
        val live = liveContext(answer)
        val fresh = if (live.any { it.domain == "climate" }) withClimateDetails(live, climate.states()) else live
        Log.i(TAG, "house: ${fresh.size} things, on: ${fresh.filter { it.isOn }.map { it.name }}")
        publish(fresh, failed = false)
    }

    fun toggle(device: Device) = setPower(device, !device.isOn)

    fun setPower(device: Device, on: Boolean) {
        val state = if (device.domain == "cover") { if (on) "open" else "closed" }
            else if (on) "on" else "off"
        change(device, device.copy(state = state)) {
            val tool = toolNamed(if (on) "HassTurnOn" else "HassTurnOff")
            val server = McpServer.from(settings)
            tool != null && server != null && !server.call(tool, arguments(device)).has("error")
        }
    }

    fun setMode(device: Device, mode: String) {
        val details = device.climate ?: return
        if (mode !in details.modes || mode == device.state) return
        change(device, device.copy(state = mode)) {
            climate.call(details.entityId, "set_hvac_mode", JSONObject().put("hvac_mode", mode))
        }
    }

    /** A target can be a single temperature or one side of an automatic heat/cool range. */
    fun setTemperature(device: Device, direction: Int, field: String = "temperature") {
        val details = device.climate ?: return
        val value = when (field) {
            "temperature" -> device.setpoint?.takeIf { details.features and 1 != 0 && device.state != "heat_cool" }
            "target_temp_low" -> details.low?.takeIf { details.features and 2 != 0 }
            "target_temp_high" -> details.high?.takeIf { details.features and 2 != 0 }
            else -> null
        } ?: return
        setTemperatureTo(device, details.shifted(value, direction) ?: return, field)
    }

    /** The same, to an exact [target] -- what the dial sends when it is let go. */
    fun setTemperatureTo(device: Device, target: Double, field: String = "temperature") {
        val details = device.climate ?: return
        val value = when (field) {
            "temperature" -> device.setpoint
            "target_temp_low" -> details.low
            "target_temp_high" -> details.high
            else -> null
        }
        if (target == value) return
        val next = when (field) {
            "target_temp_low" -> details.copy(low = target)
            "target_temp_high" -> details.copy(high = target)
            else -> details.copy(temperature = target)
        }
        val args = JSONObject()
        if (field != "temperature") {
            val low = next.low ?: return
            val high = next.high ?: return
            if (low > high) return
            args.put("target_temp_low", low).put("target_temp_high", high)
        } else args.put("temperature", target)
        change(device, device.copy(setpoint = next.temperature, climate = next)) {
            climate.call(details.entityId, "set_temperature", args)
        }
    }

    /** One outstanding action per device; a rejected request restores the last accepted display. */
    private fun change(before: Device, after: Device, action: () -> Boolean) {
        if (!before.available || !pending.add(before.key)) return
        val previous = asked[before.key]
        asked[before.key] = after to SystemClock.elapsedRealtime() + ASKED_MS
        publish(listed, failed)
        thread(name = "hachi-house") {
            val success = runCatching(action).getOrDefault(false)
            main.post {
                if (!success) {
                    if (previous == null) asked.remove(before.key) else asked[before.key] = previous
                    onError?.invoke()
                }
                pending.remove(before.key)
                publish(listed, failed)
            }
        }
    }

    /**
     * Which thing the tile means, said as fully as the tile knows it.
     *
     * The name alone is what the voice uses, and the house refuses it when two things share one --
     * a bedroom air conditioner that is both a switch and a climate entity comes back as
     * DUPLICATE_NAME. The tile came from the listing, so it can also say where and what kind.
     */
    private fun arguments(device: Device) = JSONObject()
        .put("name", device.name)
        .put("domain", JSONArray().put(device.domain))
        .apply { if (device.area.isNotBlank()) put("area", device.area) }

    /**
     * The house names its own tools (`intent__HassTurnOn`, `homeassistant__GetLiveContext`) and the
     * prefix is the server's business, so a tool is found by what it ends with.
     */
    private fun toolNamed(suffix: String): String? {
        val cached = runCatching { JSONArray(settings.houseTools) }.getOrNull() ?: return null
        return (0 until cached.length())
            .map { cached.optJSONObject(it)?.optString("name").orEmpty() }
            .firstOrNull { it.endsWith(suffix) }
    }

    private fun publish(fresh: List<Device>, failed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        asked.entries.removeAll { it.value.second < now }
        listed = fresh
        main.post {
            devices = fresh.map { device -> asked[device.key]?.first ?: device }
            this.failed = failed
            onUpdate?.invoke()
        }
    }

    private companion object {
        const val TAG = "Hachi"

        /** How long a tap is believed over the house. */
        const val ASKED_MS = 30_000L

    }
}

/**
 * The devices in a `GetLiveContext` answer.
 *
 * The house answers in its own YAML-ish listing, one item per entity, wrapped in the JSON an intent
 * response comes in. Only each item's own fields are read: sensors carry a nested `attributes` block
 * with nothing a tile could show.
 */
fun liveContext(answer: String): List<Device> {
    val body = runCatching { JSONObject(answer).optString("result") }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: answer
    val devices = mutableListOf<Device>()
    var fields = mutableMapOf<String, String>()
    fun flush() {
        // An entity may be listed under several aliases; the first is the one the house says back.
        val name = fields["names"]?.substringBefore(",")?.trim().orEmpty()
        val domain = fields["domain"].orEmpty()
        if (name.isNotBlank() && domain.isNotBlank()) {
            devices += Device(
                name,
                domain,
                fields["state"].orEmpty(),
                fields["areas"].orEmpty(),
                fields["temperature"]?.toDoubleOrNull()?.takeIf { it.isFinite() },
                unit = fields["unit_of_measurement"],
                kind = fields["device_class"],
            )
        }
        fields = mutableMapOf()
    }
    for (raw in body.lineSequence()) {
        val indent = raw.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) continue
        var line = raw.trim()
        if (line.startsWith("- ")) {
            flush()
            line = line.removePrefix("- ")
        } else if (indent > FIELD_INDENT) {
            // An attribute belongs to the entity, not to the item: only the few a tile draws are
            // kept, so that a nested `domain` cannot overwrite the item's own.
            val nested = line.substringBefore(':').trim()
            if (nested in NESTED) fields[nested] = line.substringAfter(':').trim().trim('\'', '"')
            continue
        }
        if (!line.contains(':')) continue
        val key = line.substringBefore(':').trim()
        if (key.isNotBlank()) fields[key] = line.substringAfter(':').trim().trim('\'', '"')
    }
    flush()
    return devices
}

/** How far the fields of one item are indented; anything deeper belongs to a nested block. */
private const val FIELD_INDENT = 2

/** The attributes worth keeping off an entity: what a thermostat is set to, what a sensor reads. */
private val NESTED = setOf("temperature", "unit_of_measurement", "device_class")
