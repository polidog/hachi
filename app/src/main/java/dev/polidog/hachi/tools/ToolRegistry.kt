package dev.polidog.hachi.tools

import android.content.Context
import android.util.Log
import dev.polidog.hachi.Settings
import dev.polidog.hachi.liveContext
import org.json.JSONArray
import org.json.JSONObject

/** Every tool the model is offered, and the one place they are run from. */
class ToolRegistry(context: Context, settings: Settings) {
    private val tools: List<Tool> = listOfNotNull(
        GetCurrentTimeTool(),
        TimerTool(context.applicationContext),
        SetVolumeTool(context.applicationContext),
        GetWeatherTool(context.applicationContext, settings),
        GetCalendarTool(context.applicationContext).takeIf { it.available },
    ) + houseTools(settings)

    private val byName = tools.associateBy { it.name }

    val declarations: List<JSONObject> = tools.map { it.declaration }

    init {
        Log.i(TAG, "tools offered: ${tools.joinToString(", ") { it.name }}")
    }

    /** Blocking -- tools do network I/O. A failure is reported to the model, never thrown. */
    fun run(name: String, args: JSONObject): JSONObject {
        val tool = byName[name] ?: return failure("unknown_tool")
        return try {
            tool.run(args).also { Log.i(TAG, "tool $name -> ${it.toString().take(300)}") }
        } catch (e: Exception) {
            Log.w(TAG, "tool $name failed", e)
            failure(e.message ?: "tool_failed")
        }
    }

    private companion object {
        const val TAG = "Hachi"
    }
}

/**
 * The tools the house offered the last time it was asked -- see [refreshHouseTools].
 *
 * Reading the cache rather than the server is what lets this run on the main thread.
 */
private fun houseTools(settings: Settings): List<Tool> {
    val server = McpServer.from(settings) ?: return emptyList()
    val cached = runCatching { JSONArray(settings.houseTools) }.getOrNull() ?: return emptyList()
    val declarations = mcpDeclarations(cached)
    val live = declarations.map { it.optString("name") }.firstOrNull { it.endsWith("GetLiveContext") }
    val rename = JevNames.from(settings)?.takeIf { live != null }?.let { jev ->
        { args: JSONObject ->
            val devices = liveContext(server.call(live!!, JSONObject()).optString("result"))
            // A name the house lists was refused for another reason (DUPLICATE_NAME, a wrong area).
            if (devices.isEmpty() || devices.any { it.name == args.optString("name") }) null
            // The thermostat over its infrared twin, as a tile does: it is the one that knows the mode.
            else jev.pick(args, devices)?.let { meant ->
                devices.filter { it.name == meant }.let { same -> same.firstOrNull { it.domain == "climate" } ?: same.firstOrNull() }
            }
        }
    }
    return declarations.map { McpTool(server, it, rename) }
}

/**
 * Asks the house what it can do and remembers the answer for the next conversation.
 *
 * Blocking. A house that cannot be reached keeps the tools from last time rather than losing them:
 * Home Assistant being down for a minute should not silently make a conversation forget the lights.
 */
fun refreshHouseTools(settings: Settings) {
    val server = McpServer.from(settings) ?: return
    val tools = server.tools()
    if (tools.length() == 0) return
    settings.houseTools = tools.toString()
    Log.i("Hachi", "house tools: ${mcpDeclarations(tools).map { it.optString("name") }}")
}
