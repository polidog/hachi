package dev.polidog.hachi.tools

import android.util.Log
import dev.polidog.hachi.Device
import dev.polidog.hachi.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds which device was meant when the house does not know the name the model sent.
 *
 * Assist only matches a name or alias exactly, and the model passes on what it heard: "書斎のエアコン"
 * finds nothing when the entity is called "書斎エアコン". TypeSafe's Jev picks from the names the house
 * actually has -- asked only after a miss, so a name that matches costs nothing.
 */
class JevNames(private val key: String) {
    /** Blocking -- network. Which listed name [args] meant, or null when Jev could not be asked. */
    fun pick(args: JSONObject, devices: List<Device>): JevPick? = try {
        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(jevRequest(args, devices).toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use {
            val body = it.body?.string().orEmpty()
            if (it.isSuccessful) jevPick(body) else {
                Log.w("Hachi", "jev failed (${it.code}): ${body.take(200)}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w("Hachi", "jev failed", e)
        null
    }

    companion object {
        private const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        private val JSON = "application/json".toMediaType()
        // Someone is standing there waiting for the light; a slow answer is worse than none.
        private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

        fun from(settings: Settings): JevNames? = settings.typesafeKey.takeIf { it.isNotBlank() }?.let(::JevNames)
    }
}

/** Never a device name: the option for "none of these". */
const val JEV_NONE = "(none)"

// ponytail: 0.5 is a guess, not a measurement. Raise it if a wrong device ever gets switched.
const val JEV_CONFIDENCE = 0.5

fun jevRequest(args: JSONObject, devices: List<Device>): JSONObject {
    // One option per name: an air conditioner is listed twice (climate and switch) under one name.
    val criteria = JSONObject()
    devices.groupBy { it.name }.entries.take(254).forEach { (name, same) ->
        criteria.put(name, same.joinToString(" / ") { listOf(it.area, it.domain).filter(String::isNotBlank).joinToString(" ") })
    }
    criteria.put(JEV_NONE, "None of the listed devices is the one asked for")
    return JSONObject()
        .put("model", "jev-latest")
        .put("state", JSONObject().put("request", args))
        .put("questions", JSONObject().put("device", JSONObject()
            .put("type", "choice")
            .put("instructions", "A voice assistant asked Home Assistant to operate the device named in " +
                "`request.name` -- or, with no name, the device of kind `request.domain` in the room " +
                "`request.area` -- but the house found nothing by that name or in that room. The name came from speech, so it may " +
                "differ from the real one by particles such as の, spacing, word order, reading or a synonym. " +
                "Which listed device did the user mean? `request.area` and `request.domain`, when present, " +
                "say where and what kind. Choose none when no device is clearly the same thing.")
            .put("criteria", criteria)))
}

/** The name Jev is sure of, or failing that the few it thinks likely, most likely first. */
class JevPick(val sure: String?, val maybe: List<String>)

// ponytail: 0.05 and three are guesses; a list that is always full of noise wants a higher floor.
private const val JEV_MAYBE = 0.05
private const val JEV_MAYBE_COUNT = 3

/** Null for a body that is not an answer; an unsure answer or none still names what was likely. */
fun jevPick(body: String, threshold: Double = JEV_CONFIDENCE): JevPick? {
    val answer = runCatching { JSONObject(body).getJSONObject("answers").getJSONObject("device") }.getOrNull()
        ?: return null
    val choice = answer.optString("choice")
    val sure = choice.takeIf { it.isNotBlank() && it != JEV_NONE && answer.optDouble("confidence", 0.0) >= threshold }
    val odds = answer.optJSONObject("probabilities")
    val maybe = odds?.keys()?.asSequence()
        ?.filter { it != JEV_NONE && odds.optDouble(it, 0.0) >= JEV_MAYBE }
        ?.sortedByDescending { odds.optDouble(it, 0.0) }
        ?.take(JEV_MAYBE_COUNT)?.toList().orEmpty()
    return JevPick(sure, maybe)
}
