package dev.polidog.hachi

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/** The Live API's fixed audio formats: 16 kHz PCM16 in, 24 kHz PCM16 out, mono, little-endian. */
const val MIC_RATE = 16000
const val SPEAKER_RATE = 24000

/**
 * Reads the microphone as 16 kHz PCM16 and hands each chunk to [onChunk] on its own thread.
 *
 * The device has no hardware echo canceller, so the platform [AcousticEchoCanceler] effect is
 * attached when it is available at all; whether barge-in actually works depends on playback volume,
 * which needs checking on the device.
 */
class MicStream(private val onChunk: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission") // The caller holds RECORD_AUDIO; checked before the service starts.
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = MIC_RATE / 50 * 2 // 20 ms of PCM16
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, chunk * 4),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did not initialize")
            r.release()
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        }
        // Whether this device has an echo canceller at all decides whether the half-duplex gate in
        // Conversation is load-bearing or just belt and braces.
        Log.i(TAG, "mic effects: aec=${aec?.enabled == true} (available=${AcousticEchoCanceler.isAvailable()})" +
            " ns=${ns?.enabled == true}")
        record = r
        running = true
        r.startRecording()
        thread(name = "hachi-mic") {
            val buf = ByteArray(chunk)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onChunk(buf.copyOf(n)) else if (n < 0) break
            }
        }
    }

    fun stop() {
        running = false
        aec?.release(); aec = null
        ns?.release(); ns = null
        record?.runCatching { stop(); release() }
        record = null
    }
}

/**
 * Plays the 24 kHz PCM16 the model streams back, with [flush] for barge-in.
 *
 * [busy] reports whether anything written is still coming out of the speaker, which is what lets the
 * microphone be gated shut while the assistant talks -- without that, this device (no hardware echo
 * canceller) hears its own voice and answers itself.
 */
class SpeakerStream {
    private var track: AudioTrack? = null
    /** Frames handed to the track since the last flush; compared against the playback head. */
    @Volatile private var written = 0L
    @Volatile private var quietSince = 0L

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Routed as voice communication so the platform echo canceller has a reference
                    // signal to subtract. ponytail: if barge-in is unusable on the device, try
                    // USAGE_MEDIA plus a lower volume instead -- that is what worked for butler.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SPEAKER_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SPEAKER_RATE)) // ~0.5 s of headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    fun write(pcm: ByteArray) {
        val t = track ?: return
        t.write(pcm, 0, pcm.size)
        written += pcm.size / 2 // PCM16 mono: 2 bytes per frame
    }

    /**
     * True while audio is still playing, and for [tailMs] afterwards -- the room keeps ringing for a
     * moment after the speaker goes quiet, and that tail is enough to wake the far-end VAD.
     */
    fun busy(tailMs: Long = 400): Boolean {
        val t = track ?: return false
        val playing = written > t.playbackHeadPosition.toLong()
        if (playing) {
            quietSince = 0L
            return true
        }
        if (quietSince == 0L) quietSince = System.currentTimeMillis()
        return System.currentTimeMillis() - quietSince < tailMs
    }

    /** Drops whatever is still queued, so an interrupted reply stops speaking immediately. */
    fun flush() {
        // AudioTrack.flush() resets the playback head, so the written-frame count has to reset with it.
        track?.runCatching { pause(); flush(); play() }
        written = 0L
        quietSince = 0L
    }

    fun stop() {
        track?.runCatching { pause(); flush(); stop(); release() }
        track = null
        written = 0L
        quietSince = 0L
    }
}

/** Puts the audio system in communication mode for the duration of a conversation. */
class AudioMode(private val manager: AudioManager) {
    private var previous = AudioManager.MODE_NORMAL

    fun enter() {
        previous = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.isSpeakerphoneOn = true
    }

    fun leave() {
        manager.mode = previous
    }
}

private const val TAG = "Hachi"
