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
        /** A tool has just run, so whatever it changed in the world is worth redrawing. */
        fun onToolUsed(name: String)
        /** Devices to pick from by number, or empty once the question is settled. */
        fun onChoices(names: List<String>)
        /** The model wants one of the screens seen -- one of [dev.polidog.hachi.tools.ShowScreenTool.SCREENS]. */
        fun onShow(screen: String)
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

    /**
     * Opens a session. [greet] is set when the wake word was what started this, and makes Hachi say
     * something first -- being called by name and answering nothing is the one thing a name is for.
     * [said] is a request that was already spoken along with the name ("はーいミラ、電気消して"): it is
     * sent as the first thing heard, and answered in place of the greeting.
     */
    fun start(greet: Boolean = false, said: ByteArray? = null) {
        if (client != null) return
        val key = settings.geminiKey
        if (key.isBlank()) {
            ui.onError(context.getString(R.string.error_no_api_key))
            // Refused before it began is still an ending: whoever gave up the microphone for this
            // -- the wake word listener -- is waiting to be told it can have it back.
            ui.onState(State.ENDED)
            return
        }
        val cap = settings.dailyCapUsd
        if (usage.overDailyCap(cap)) {
            ui.onError(context.getString(R.string.error_daily_cap, String.format("%.2f", cap)))
            ui.onState(State.ENDED)
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
                    // Before the live microphone, so it arrives in the order it was said; the room's
                    // quiet after it is what the server's VAD takes as the end of the request.
                    if (said != null) {
                        for (at in said.indices step SAID_CHUNK) {
                            client?.sendAudio(said.copyOfRange(at, minOf(at + SAID_CHUNK, said.size)))
                        }
                        Log.i(TAG, "sent ${said.size / 32}ms said with the name")
                    }
                    mic.start()
                    ui.onState(State.LISTENING)
                    restartSilenceTimer()
                    // The model will not speak until something has been said to it, so being called
                    // by name is handed over as the first turn.
                    if (greet && said == null) client?.sendText(greeting())
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
                        val choices = result.optJSONArray("choices")
                            ?.let { list -> (0 until list.length()).map(list::optString) }.orEmpty()
                        val shown = result.optString("shown")
                        main.post {
                            ui.onToolUsed(name)
                            ui.onChoices(choices)
                            if (shown.isNotEmpty()) ui.onShow(shown)
                        }
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

    /** The turn that stands in for the name that was just called across the room. */
    private fun greeting(): String {
        val user = settings.userName
        return if (user.isBlank()) context.getString(R.string.wake_greeting_anon)
        else context.getString(R.string.wake_greeting, user)
    }

    private fun systemInstruction(): String =
        context.getString(R.string.system_instruction, settings.assistantName)

    private companion object {
        const val TAG = "Hachi"
        /** 100 ms of 16 kHz PCM16: a request goes up in pieces, not as one eight-second message. */
        const val SAID_CHUNK = 3200
    }
}
