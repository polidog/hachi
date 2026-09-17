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

/** The loudest sample in a PCM16 chunk, 0..32767. Silence from a dead microphone reads as 0. */
fun loudest(pcm: ByteArray): Int {
    var peak = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
        if (magnitude > peak) peak = magnitude
        i += 2
    }
    return peak
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

    /**
     * Wall-clock time at which everything handed to the track will have finished playing.
     *
     * This deliberately does not consult AudioTrack.playbackHeadPosition: flush() does not reliably
     * reset it, which left the written-frames comparison stuck reporting "not playing" for the rest
     * of the session -- the microphone then stayed open through every reply and the model answered
     * its own voice.
     */
    @Volatile private var playUntil = 0L

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
        val durationMs = pcm.size / 2 * 1000L / SPEAKER_RATE // PCM16 mono: 2 bytes per frame
        // Chunks arrive faster than real time, so each one extends the end of whatever is already
        // queued; a chunk arriving after a gap starts from now instead.
        playUntil = maxOf(playUntil, System.currentTimeMillis()) + durationMs
    }

    /**
     * True while audio is still playing, and for [tailMs] afterwards.
     *
     * The tail covers two things at once: the room keeps ringing for a moment after the speaker goes
     * quiet, and the track's own buffer means audio actually reaches the speaker slightly later than
     * it was written. ponytail: 600 ms is a guess tuned by ear -- raise it if a tail of the reply
     * still leaks back in.
     */
    fun busy(tailMs: Long = 600): Boolean =
        track != null && System.currentTimeMillis() < playUntil + tailMs

    /** Drops whatever is still queued, so an interrupted reply stops speaking immediately. */
    fun flush() {
        track?.runCatching { pause(); flush(); play() }
        playUntil = 0L
    }

    fun stop() {
        track?.runCatching { pause(); flush(); stop(); release() }
        track = null
        playUntil = 0L
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
