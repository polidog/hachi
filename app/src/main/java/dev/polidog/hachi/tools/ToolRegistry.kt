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
        ShowScreenTool(),
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
    val jev = JevNames.from(settings)
    val rename = live?.let {
        { args: JSONObject ->
            val devices = liveContext(server.call(live, JSONObject()).optString("result"))
            // The thermostat over its infrared twin, as a tile does: it is the one that knows the mode.
            fun device(name: String?) = devices.filter { it.name == name }
                .let { same -> same.firstOrNull { it.domain == "climate" } ?: same.firstOrNull() }
            // A name the house lists, refused all the same: said with the wrong kind (書斎照明 is a
            // switch, heard as a light) or the wrong room, which the retry puts right.
            device(args.optString("name"))?.let { Meant(it, emptyList()) }
                ?: jev?.takeIf { devices.isNotEmpty() }?.pick(args, devices)?.let { Meant(device(it.sure), it.maybe) }
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
