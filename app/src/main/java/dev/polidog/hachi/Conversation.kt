package dev.polidog.hachi

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * One voice conversation: wires the microphone and speaker to a [GeminiLiveClient] and ends the
 * session after [Settings.silenceTimeout] seconds without either side saying anything.
 */
class Conversation(
    private val context: Context,
    private val settings: Settings,
    private val ui: Ui,
) {
    interface Ui {
        fun onState(state: State)
        /** Spend so far this calendar month, already formatted. */
        fun onSpend(label: String)
        fun onUserText(text: String)
        fun onAssistantText(text: String)
        fun onError(message: String)
    }

    enum class State { CONNECTING, LISTENING, ENDED }

    private val main = Handler(Looper.getMainLooper())
    private val audioMode = AudioMode(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private val speaker = SpeakerStream()
    private val usage = Usage(context)
    // Half-duplex gate: nothing is sent upstream while the assistant's own voice is coming out of
    // the speaker. The device has no hardware echo canceller, so without this the model hears itself,
    // transcribes it as the user, and answers its own reply.
    // ponytail: costs barge-in. Tapping the screen hushes the reply instead; a real software AEC
    // (WebRTC AudioProcessing) is the upgrade path if talking over Hachi turns out to matter.
    private val mic = MicStream { pcm -> if (!speaker.busy()) client?.sendAudio(pcm) }
    private var client: GeminiLiveClient? = null
    private var stopped = false
    private val silenceTimer = Runnable { stop() }

    val active get() = client != null && !stopped

    /** True while the assistant is actually speaking, so the UI can offer to cut it off. */
    val speaking get() = speaker.busy(tailMs = 0)

    /** Drops the rest of the spoken reply without ending the conversation. */
    fun hush() {
        speaker.flush()
    }

    fun start() {
        if (client != null) return
        val key = settings.geminiKey
        if (key.isBlank()) {
            ui.onError(context.getString(R.string.error_no_api_key))
            return
        }
        stopped = false
        ui.onState(State.CONNECTING)
        audioMode.enter()
        speaker.start()
        client = GeminiLiveClient(
            apiKey = key,
            model = settings.model,
            voice = settings.voice,
            languageCode = languageCode(),
            systemInstruction = systemInstruction(),
            tools = emptyList(),
            listener = object : GeminiLiveClient.Listener {
                override fun onReady() { main.post {
                    mic.start()
                    ui.onState(State.LISTENING)
                    restartSilenceTimer()
                } }

                override fun onUserText(text: String) { main.post {
                    restartSilenceTimer()
                    ui.onUserText(text)
                } }

                override fun onAssistantText(text: String) { main.post {
                    restartSilenceTimer()
                    ui.onAssistantText(text)
                } }

                override fun onAudio(pcm: ByteArray) {
                    speaker.write(pcm)
                }

                override fun onInterrupted() {
                    speaker.flush()
                }

                override fun onTurnComplete() { main.post { restartSilenceTimer() } }

                override fun onToolCall(id: String, name: String, args: JSONObject) {
                    // Phase 3 wires the tool registry in here.
                    Log.i(TAG, "unhandled tool call: $name $args")
                    client?.sendToolResult(id, name, JSONObject().put("error", "not_implemented"))
                }

                override fun onUsage(metadata: JSONObject) { main.post {
                    usage.observe(settings.model, metadata)
                    ui.onSpend(usage.monthLabel())
                } }

                override fun onClosed(reason: String?) { main.post {
                    if (!stopped) reason?.let { ui.onError(it) }
                    stop()
                } }
            },
        ).also { it.connect() }
    }

    fun stop() {
        if (stopped && client == null) return
        stopped = true
        main.removeCallbacks(silenceTimer)
        mic.stop()
        speaker.stop()
        audioMode.leave()
        client?.close()
        client = null
        usage.endSession()
        ui.onState(State.ENDED)
    }

    private fun restartSilenceTimer() {
        main.removeCallbacks(silenceTimer)
        main.postDelayed(silenceTimer, settings.silenceTimeout * 1000L)
    }

    private fun languageCode() = if (Locale.getDefault().language == "ja") "ja-JP" else "en-US"

    private fun systemInstruction(): String = context.getString(R.string.system_instruction)

    private companion object {
        const val TAG = "Hachi"
    }
}
