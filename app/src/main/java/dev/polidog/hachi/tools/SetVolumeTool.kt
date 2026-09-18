package dev.polidog.hachi.tools

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

/**
 * The loudness of this device's own voice -- not the house's speakers, which the house's own
 * volume tool drives. Hachi speaks on the voice-call stream (see SpeakerStream), so that is the one.
 */
class SetVolumeTool(context: Context) : Tool {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val name = "set_device_volume"

    override val declaration = declare(
        name,
        "Changes how loud this device itself speaks (the assistant's own voice and this display's " +
            "speaker). Not for the house's speakers or music players.",
        listOf(
            Triple("change", "\"up\", \"down\", or an absolute level from 0 to 100.", true),
        ),
    )

    override fun run(args: JSONObject): JSONObject {
        val stream = AudioManager.STREAM_VOICE_CALL
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        val change = args.optString("change").trim()
        val target = when (change) {
            "up" -> now + 1
            "down" -> now - 1
            else -> change.toIntOrNull()?.let { min + (max - min) * it / 100 } ?: return failure("unknown_change")
        }.coerceIn(min, max)
        audio.setStreamVolume(stream, target, 0)
        return JSONObject()
            .put("level_percent", (target - min) * 100 / (max - min))
            .put("at_limit", target == min || target == max)
    }
}
