package dev.polidog.hachi.tools

import org.json.JSONObject

/**
 * Puts one of this display's own screens in front. Like every tool it knows nothing about the UI: it
 * only says which screen, and Conversation hands that on to whoever draws them.
 */
class ShowScreenTool : Tool {
    override val name = "show_screen"

    override val declaration = declare(
        name,
        "Shows one of this display's own screens, so the user can see it while you talk. Use it when " +
            "asked to show or open something, and when the answer is easier seen than heard, such as " +
            "the week's weather, this month's calendar, or which lights are on.",
        listOf(
            Triple("screen", "\"clock\", \"weather\", \"calendar\", or \"house\".", true),
        ),
    )

    override fun run(args: JSONObject): JSONObject {
        val screen = args.optString("screen").trim().lowercase()
        if (screen !in SCREENS) return failure("unknown_screen")
        return JSONObject().put("shown", screen)
    }

    companion object {
        val SCREENS = listOf("clock", "weather", "house", "calendar")
    }
}
