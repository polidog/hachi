package dev.polidog.hachi.tools

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The time changes while a session runs, so it cannot be baked into the session's opening context --
 * the model has to be able to ask.
 */
class GetCurrentTimeTool : Tool {
    override val name = "get_current_time"

    override val declaration = declare(
        name,
        "Returns the current local date, time and day of the week on the device. " +
            "Use this whenever the answer depends on what time or day it is now.",
    )

    override fun run(args: JSONObject): JSONObject {
        val now = ZonedDateTime.now()
        val locale = Locale.getDefault()
        return JSONObject()
            .put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("date", now.toLocalDate().toString())
            .put("time", now.format(DateTimeFormatter.ofPattern("HH:mm")))
            .put("weekday", now.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
            .put("timezone", now.zone.id)
    }
}
