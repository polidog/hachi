package dev.polidog.hachi

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The Gemini models this app can hold a voice conversation with.
 *
 * The list is fetched from ListModels and filtered to those advertising `bidiGenerateContent`, so a
 * newly released Live model shows up without an app update. [FALLBACK] covers the offline case and
 * whatever happens before the fetch lands.
 */
object ModelCatalog {
    val FALLBACK = listOf(
        "gemini-3.8-live",
        "gemini-3.8-live-extended-thinking",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025",
    )

    private val http = OkHttpClient()

    /** Blocking; call off the main thread. Returns [FALLBACK] if anything goes wrong. */
    fun fetch(apiKey: String): List<String> {
        if (apiKey.isBlank()) return FALLBACK
        return try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=$apiKey")
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "ListModels failed (${response.code})")
                    return FALLBACK
                }
                // One page of 1000 covers every model Google publishes today; nextPageToken is ignored.
                val models = JSONObject(body).optJSONArray("models") ?: return FALLBACK
                val live = buildList {
                    for (i in 0 until models.length()) {
                        val model = models.optJSONObject(i) ?: continue
                        val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                        val speaks = (0 until methods.length())
                            .any { methods.optString(it) == "bidiGenerateContent" }
                        if (speaks) add(model.optString("name").removePrefix("models/"))
                    }
                }
                live.ifEmpty { FALLBACK }.sorted()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ListModels failed", e)
            FALLBACK
        }
    }

    private const val TAG = "Hachi"
}

/**
 * The prebuilt Live API voices. There is no endpoint that lists them, so this is a hand-kept subset
 * of the ones Google documents; an unlisted voice name can still be typed into the model field's
 * sibling if one is ever needed.
 */
val VOICES = listOf("Aoede", "Charon", "Fenrir", "Kore", "Leda", "Orus", "Puck", "Zephyr")
