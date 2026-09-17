package dev.polidog.hachi

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A single Gemini Live API session over its BidiGenerateContent WebSocket.
 *
 * The protocol is: connect, send one `setup` message, wait for `setupComplete`, then stream
 * `realtimeInput` audio chunks up while `serverContent` messages come down carrying the reply audio,
 * both transcripts, and barge-in notifications. Server-side VAD handles turn taking, so the client
 * never has to decide when the user stopped speaking.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val languageCode: String,
    private val systemInstruction: String,
    private val tools: List<JSONObject>,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        /** What the user said, as the model transcribed it. Arrives in fragments. */
        fun onUserText(text: String)
        /** What the model is saying, as text. Arrives in fragments. */
        fun onAssistantText(text: String)
        fun onAudio(pcm: ByteArray)
        /** The user spoke over the reply: whatever is queued for playback must be dropped. */
        fun onInterrupted()
        fun onTurnComplete()
        fun onToolCall(id: String, name: String, args: JSONObject)
        /** The session's running token counts, which is what the spend display is built from. */
        fun onUsage(metadata: JSONObject)
        fun onClosed(reason: String?)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // a live session is idle-but-open between turns
        .build()
    private var socket: WebSocket? = null
    @Volatile private var ready = false
    /** The reply's audio format is logged once per session: a model that streams at something other
     * than 24 kHz would play back at the wrong speed and throw off the speaker's busy estimate. */
    private var loggedFormat = false

    fun connect() {
        val request = Request.Builder().url("$ENDPOINT?key=$apiKey").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            // The server frames its JSON as binary, so both callbacks have to be handled.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "live socket failed (${response?.code})", t)
                ready = false
                listener.onClosed(t.message ?: response?.message)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The server closing first only reaches onClosed if the close is acknowledged.
                Log.i(TAG, "live socket closing: $code ${reason.ifBlank { "(no reason)" }}")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "live socket closed: $code ${reason.ifBlank { "(no reason)" }}")
                ready = false
                listener.onClosed(reason.ifBlank { "closed ($code)" })
            }
        })
    }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        val chunk = JSONObject()
            .put("mimeType", "audio/pcm;rate=$MIC_RATE")
            .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
        send(JSONObject().put("realtimeInput", JSONObject().put("audio", chunk)))
    }

    fun sendToolResult(id: String, name: String, result: JSONObject) {
        val response = JSONObject().put("id", id).put("name", name).put("response", result)
        send(
            JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(response)),
            )
        )
    }

    /** Nudges the model with out-of-band text, e.g. to make it say goodbye before hanging up. */
    fun sendText(text: String) {
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        send(
            JSONObject().put(
                "clientContent",
                JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true),
            )
        )
    }

    fun close() {
        ready = false
        socket?.close(1000, null)
        socket = null
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun setupMessage(): JSONObject {
        val speech = JSONObject()
            .put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))
            .put("languageCode", languageCode)
        val setup = JSONObject()
            .put("model", "models/$model")
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speech),
            )
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
            )
            // Both transcripts drive the on-screen captions; without these the session is audio-only
            // and there is nothing to show.
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (tools.isNotEmpty()) {
            val declarations = JSONArray().apply { tools.forEach { put(it) } }
            setup.put(
                "tools",
                JSONArray().put(JSONObject().put("functionDeclarations", declarations)),
            )
        }
        return JSONObject().put("setup", setup)
    }

    private fun handle(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "unparsable live message: ${raw.take(200)}")
            return
        }
        if (message.has("setupComplete")) {
            ready = true
            listener.onReady()
            return
        }
        message.optJSONObject("serverContent")?.let { content ->
            if (content.optBoolean("interrupted")) listener.onInterrupted()
            content.optJSONObject("inputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let(listener::onUserText)
            content.optJSONObject("outputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let(listener::onAssistantText)
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    part.optString("text").takeIf { it.isNotEmpty() }?.let(listener::onAssistantText)
                    val inline = part.optJSONObject("inlineData")
                    val data = inline?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        if (!loggedFormat) {
                            loggedFormat = true
                            Log.i(TAG, "reply audio format: ${inline.optString("mimeType")}")
                        }
                        listener.onAudio(Base64.decode(data, Base64.NO_WRAP))
                    }
                }
            }
            if (content.optBoolean("turnComplete")) listener.onTurnComplete()
        }
        message.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                listener.onToolCall(
                    call.optString("id"),
                    call.optString("name"),
                    call.optJSONObject("args") ?: JSONObject(),
                )
            }
        }
        message.optJSONObject("usageMetadata")?.let {
            // Logged raw so the cumulative-vs-delta assumption in Usage can be checked on the device.
            Log.i(TAG, "usage: $it")
            listener.onUsage(it)
        }
        message.optJSONObject("goAway")?.let {
            // The server is about to drop the session (it caps how long one can run).
            Log.i(TAG, "live session going away: $it")
            listener.onClosed("goAway")
        }
    }

    private companion object {
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val TAG = "Hachi"
    }
}
