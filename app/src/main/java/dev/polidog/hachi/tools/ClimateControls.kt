package dev.polidog.hachi.tools

import android.util.Log
import dev.polidog.hachi.Device
import dev.polidog.hachi.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.round

/** Capabilities from HA, in its configured temperature unit. Never guess a device's modes/range. */
data class ClimateState(
    val entityId: String,
    val name: String,
    val area: String,
    val state: String,
    val modes: List<String>,
    val temperature: Double?,
    val current: Double?,
    val minimum: Double?,
    val maximum: Double?,
    val step: Double,
    val unit: String,
    val features: Int,
    val low: Double?,
    val high: Double?,
) {
    fun shifted(value: Double, direction: Int): Double? {
        val min = minimum ?: return null
        val max = maximum ?: return null
        if (min > max) return null
        return (round((value + step * direction) * 1000) / 1000).coerceIn(min, max)
    }
}

/** REST supplements MCP only for climate details and actions that Assist does not expose. */
class ClimateControls(private val settings: Settings) {
    fun states(): List<ClimateState> {
        val body = post("template", JSONObject().put("template", TEMPLATE)) ?: return emptyList()
        return runCatching { climateStates(body) }.getOrElse {
            Log.w("Hachi", "climate: invalid capabilities", it)
            emptyList()
        }
    }

    fun call(entityId: String, service: String, data: JSONObject): Boolean =
        post("services/climate/$service", data.put("entity_id", entityId)) != null

    private fun post(path: String, data: JSONObject): String? = try {
        val base = climateApiBase(settings.homeAssistantUrl)
        if (base.isBlank()) null else {
            val request = Request.Builder().url("$base/$path")
                .header("Authorization", "Bearer ${settings.homeAssistantToken}")
                .post(data.toString().toRequestBody(JSON)).build()
            http.newCall(request).execute().use {
                if (it.isSuccessful) it.body?.string().orEmpty() else {
                    Log.w("Hachi", "climate $path failed (${it.code})")
                    null
                }
            }
        }
    } catch (e: Exception) {
        Log.w("Hachi", "climate $path failed", e)
        null
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        // JSON serialization keeps names containing quotes/newlines intact. Area resolution includes
        // the device registry, unlike /api/states. Only uniquely matched MCP entries are displayed.
        private val TEMPLATE = """
            {% set ns = namespace(items=[]) %}
            {% for s in states.climate %}
              {% set ns.items = ns.items + [dict(entity_id=s.entity_id, name=s.name,
                area=area_name(s.entity_id) or '', state=s.state, attributes=s.attributes)] %}
            {% endfor %}
            {{ dict(items=ns.items) | to_json }}
        """.trimIndent()
    }
}

fun climateApiBase(url: String): String {
    val base = url.trim().trimEnd('/')
    return if (base.isBlank()) "" else base.removeSuffix("/api/mcp").removeSuffix("/api") + "/api"
}

fun climateStates(body: String): List<ClimateState> {
    val root = JSONObject(body)
    val items = root.getJSONArray("items")
    return (0 until items.length()).mapNotNull { i ->
        val item = items.optJSONObject(i) ?: return@mapNotNull null
        val id = item.optString("entity_id")
        if (!id.startsWith("climate.")) return@mapNotNull null
        val a = item.optJSONObject("attributes") ?: JSONObject()
        val modes = a.optJSONArray("hvac_modes") ?: JSONArray()
        ClimateState(id, item.optString("name"), item.optString("area"), item.optString("state"),
            (0 until modes.length()).map { modes.optString(it) }.filter { it.isNotBlank() },
            a.number("temperature"), a.number("current_temperature"), a.number("min_temp"),
            a.number("max_temp"), a.number("target_temp_step")?.takeIf { it > 0 } ?: 1.0,
            root.optString("unit", "°"), a.optInt("supported_features"),
            a.number("target_temp_low"), a.number("target_temp_high"))
    }
}

/** Never select the first of several matching entities: identical names can be in different rooms. */
fun withClimateDetails(devices: List<Device>, states: List<ClimateState>): List<Device> = devices.map { d ->
    val match = if (d.domain == "climate") states.singleOrNull { it.name == d.name && it.area == d.area } else null
    if (match == null) d else d.copy(state = match.state, setpoint = match.temperature, climate = match)
}

private fun JSONObject.number(key: String): Double? = optDouble(key).takeIf { it.isFinite() }
