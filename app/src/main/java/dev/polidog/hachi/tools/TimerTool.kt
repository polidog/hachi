package dev.polidog.hachi.tools

import android.content.Context
import dev.polidog.hachi.Timers
import org.json.JSONArray
import org.json.JSONObject

/** Sets, lists and cancels kitchen timers; see [Timers]. */
class TimerTool(private val context: Context) : Tool {
    override val name = "timer"

    override val declaration = declare(
        name,
        "Sets, lists or cancels countdown timers on this device. The device rings when one runs out. " +
            "Use this for requests like 'three minute timer' or 'how long is left'.",
        listOf(
            Triple("action", "One of: set, list, cancel.", true),
            Triple("seconds", "For set: the duration in whole seconds, e.g. \"180\" for three minutes.", false),
            Triple("label", "For set: a short name such as \"pasta\". For cancel: which timer; empty cancels all.", false),
        ),
    )

    override fun run(args: JSONObject): JSONObject {
        val label = args.optString("label").trim()
        return when (args.optString("action")) {
            "set" -> {
                val seconds = args.optString("seconds").trim().toLongOrNull()
                    ?.takeIf { it in 1..MAX_SECONDS } ?: return failure("seconds_must_be_1_to_$MAX_SECONDS")
                Timers.set(context, seconds, label)
                list()
            }
            "cancel" -> JSONObject().put("cancelled", timers(Timers.cancel(label))).put("timers", timers(Timers.pending()))
            "list" -> list()
            else -> failure("unknown_action")
        }
    }

    private fun list() = JSONObject().put("timers", timers(Timers.pending()))

    private fun timers(list: List<Timers.Timer>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("label", it.label).put("seconds_left", it.secondsLeft())) }
    }

    private companion object {
        const val MAX_SECONDS = 24 * 60 * 60L
    }
}
