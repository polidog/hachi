package dev.polidog.hachi.tools

import android.content.Context
import android.util.Log
import dev.polidog.hachi.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Every tool the model is offered, and the one place they are run from. */
class ToolRegistry(context: Context, settings: Settings) {
    private val tools: List<Tool> = listOf(
        GetCurrentTimeTool(),
        GetWeatherTool(context.applicationContext, settings),
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
    return mcpDeclarations(cached).map { McpTool(server, it) }
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
