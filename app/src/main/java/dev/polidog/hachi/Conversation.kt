package dev.polidog.hachi

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.polidog.hachi.tools.ToolRegistry
import org.json.JSONObject
import kotlin.concurrent.thread
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
    private val tools = ToolRegistry(context, settings)
    // Half-duplex gate: nothing is sent upstream while the assistant's own voice is coming out of
    // the speaker. The device has no hardware echo canceller, so without this the model hears itself,
    // transcribes it as the user, and answers its own reply.
    // ponytail: costs barge-in. Tapping the screen hushes the reply instead; a real software AEC
    // (WebRTC AudioProcessing) is the upgrade path if talking over Hachi turns out to matter.
    // Counted rather than logged per chunk: what matters afterwards is whether the microphone was
    // delivering anything at all, and whether the half-duplex gate was what swallowed it.
    private var sent = 0
    private var gated = 0
    // The chunk counter cannot tell a microphone that is delivering a voice from one delivering
    // zeros, and both look identical from the model's silence. The loudest sample of each second is
    // what separates them.
    private var peak = 0
    private val mic = MicStream { pcm ->
        peak = maxOf(peak, loudest(pcm))
        if (speaker.busy()) gated++ else { sent++; client?.sendAudio(pcm) }
        if ((sent + gated) % 50 == 0) {
            Log.i(TAG, "mic level: peak=$peak of 32767")
            peak = 0
        }
    }
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
        val cap = settings.dailyCapUsd
        if (usage.overDailyCap(cap)) {
            ui.onError(context.getString(R.string.error_daily_cap, String.format("%.2f", cap)))
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
            tools = tools.declarations,
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
                    Log.i(TAG, "tool call: $name $args")
                    // Tools do network I/O, and this arrives on the socket's reader thread.
                    thread {
                        val result = tools.run(name, args)
                        client?.sendToolResult(id, name, result)
                    }
                }

                override fun onUsage(metadata: JSONObject) { main.post {
                    usage.observe(settings.model, metadata)
                    ui.onSpend(usage.label())
                    // The cap can be crossed mid-sentence; end the session as soon as it is.
                    val limit = settings.dailyCapUsd
                    if (usage.overDailyCap(limit)) {
                        ui.onError(context.getString(R.string.error_daily_cap, String.format("%.2f", limit)))
                        stop()
                    }
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
        Log.i(TAG, "conversation ending: ${sent * 20}ms sent, ${gated * 20}ms gated by the speaker")
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
