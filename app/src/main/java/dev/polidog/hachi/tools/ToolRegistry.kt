package dev.polidog.hachi.tools

import android.content.Context
import android.util.Log
import dev.polidog.hachi.Settings
import org.json.JSONObject

/** Every tool the model is offered, and the one place they are run from. */
class ToolRegistry(context: Context, settings: Settings) {
    private val tools: List<Tool> = listOf(
        GetCurrentTimeTool(),
        GetWeatherTool(context.applicationContext, settings),
    )

    private val byName = tools.associateBy { it.name }

    val declarations: List<JSONObject> = tools.map { it.declaration }

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
