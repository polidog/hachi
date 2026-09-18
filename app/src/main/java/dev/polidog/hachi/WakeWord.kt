package dev.polidog.hachi

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Listens for Hachi's own name and says so, so a conversation can be started without the button.
 *
 * Detection is the bundled Julius run as a child process -- not the platform recogniser, which this
 * device does not have: it is a LineageOS tablet with no Google apps, so `SpeechRecognizer` silently
 * resolves to nothing. This is butler's arrangement, brought over almost unchanged, and it means the
 * room is never sent anywhere: nothing leaves the device until the name has been heard.
 *
 * No JNI. Android 10 onwards only lets a file be executed out of `nativeLibraryDir`, so Julius is
 * packaged as though it were a library, extracted there on install, and spoken to over two loopback
 * sockets: audio down its adinnet port, recognition results back up its module port.
 *
 * It holds the microphone while it listens, so a conversation has to [stop] it first and start it
 * again afterwards -- see MainActivity.
 */
class WakeWord(
    private val context: Context,
    private val settings: Settings,
    private val onHeard: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val binary = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE)

    @Volatile private var stopped = true
    @Volatile private var listening: String? = null
    private var worker: Thread? = null
    private var micFree = CountDownLatch(0)

    /** Whether there is anything on this device that could listen at all. */
    val available get() = binary.exists()

    /**
     * Starts listening for [forName], or goes on listening if that is already the name.
     *
     * Everything slow happens on the worker: the model is 12 MB to unpack the first time and Julius
     * takes a moment to come up, and neither may hold the screen.
     */
    fun start(forName: String) {
        if (!available) {
            Log.w(TAG, "no Julius binary at ${binary.absolutePath}; wake word off")
            return
        }
        val dict = WakeGrammar.dict(forName)
        val word = WakeGrammar.word(forName)
        if (dict == null || word == null) {
            Log.i(TAG, "cannot work out how \"$forName\" is said; wake word off")
            return
        }
        if (listening == forName && worker?.isAlive == true) return
        stop()
        stopped = false
        listening = forName
        micFree = CountDownLatch(1)
        worker = thread(name = "hachi-wake") { run(word, dict) }
    }

    /**
     * Gives up the microphone and takes Julius down with it. Safe to call when not listening.
     *
     * This waits for the microphone to come back and no longer, because the caller is about to record
     * with it. Killing the child process and reaping its threads can take a second or two and happens
     * on the worker, where it holds nothing up.
     */
    fun stop() {
        stopped = true
        listening = null
        runCatching { micFree.await(MIC_RELEASE_MS, TimeUnit.MILLISECONDS) }
        worker = null
    }

    @SuppressLint("MissingPermission") // The caller holds RECORD_AUDIO; checked before this starts.
    private fun run(word: String, dict: String) {
        var julius: Julius? = null
        var mic: AudioRecord? = null
        try {
            julius = Julius(context, word, dict, settings.wakeLevel)
            val min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "AudioRecord reported no usable buffer size" }
            mic = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min * 2, CHUNK * 4),
            )
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord did not initialize" }
            mic.startRecording()
            Log.i(TAG, "listening for \"$word\"")
            val buffer = ShortArray(CHUNK)
            var chunks = 0
            while (!stopped) {
                val count = mic.read(buffer, 0, buffer.size)
                if (count <= 0) break
                if (stopped) break
                // A microphone delivering zeros and one delivering a voice look identical from
                // Julius's silence, and a threshold set too high looks like both. Once a second,
                // say what actually arrived and whether any of it was loud enough to send on.
                if (++chunks % LEVEL_EVERY == 0) julius.logLevel()
                if (julius.accept(buffer, count)) {
                    Log.i(TAG, "woken by \"$word\"")
                    // Whoever takes over wants the microphone, and the finally below is about to let
                    // go of it -- so hand over on the main thread, after this loop, not inside it.
                    main.post { if (!stopped) onHeard() }
                    break
                }
            }
        } catch (e: Exception) {
            if (!stopped) Log.w(TAG, "wake word listener stopped: $e")
        } catch (e: LinkageError) {
            if (!stopped) Log.w(TAG, "wake word listener stopped: $e")
        } finally {
            // The microphone first and the latch immediately after it: that is what stop() waits on.
            mic?.runCatching { stop(); release() }
            micFree.countDown()
            julius?.close()
        }
    }

    private companion object {
        const val TAG = "Hachi"
        const val EXECUTABLE = "libjulius-bin.so"
        /** 100 ms of 16 kHz mono, the chunk the VAD and the adinnet packets are both happy with. */
        const val CHUNK = 1600
        /** Chunks between level lines; CHUNK is 100 ms, so this is once every 10 seconds. */
        const val LEVEL_EVERY = 100
        const val MIC_RELEASE_MS = 1500L
    }
}

/**
 * One running Julius: the process, its two sockets, and the threads draining them.
 *
 * Julius does not cut silence out of adinnet input, so [WakeVad] chops the continuous microphone
 * stream into spans of speech and only those are sent down. Results come back up the module socket and
 * are judged by [JuliusWake]; a hit sets a flag that [accept] picks up and clears.
 */
private class Julius(context: Context, word: String, dict: String, rmsThreshold: Double) : AutoCloseable {
    private val recognition = JuliusWake(word, JuliusWake.THRESHOLD, JuliusWake.MAX_FILLERS)
    private val threshold = rmsThreshold
    private val woken = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var broken = false
    private val threads = mutableListOf<Thread>()

    private val process: Process
    private val moduleSocket: Socket
    private val adinnetSocket: Socket
    private val adinnetOut: OutputStream

    // The VAD hands over 10 ms at a time; batch those up rather than writing a 320-byte packet per frame.
    private val sendBuffer = ShortArray(1600)
    private var sendFill = 0

    private val vad = WakeVad(
        onSegmentStart = {},
        onSegmentAudio = { samples, count -> buffer(samples, count) },
        onSegmentEnd = { flush(); sendEnd() },
        rmsThreshold = rmsThreshold,
    )

    /** What the microphone has been delivering, against the level a frame has to reach to be sent on. */
    fun logLevel() {
        Log.i("Hachi", "wake level: loudest frame ${vad.loudestFrame.toInt()}, sending at ${threshold.toInt()}," +
            " ${vad.segments} segments so far")
        vad.loudestFrame = 0.0
    }

    init {
        val assets = AssetUnpacker.unpack(context, ASSET_DIR, "$ASSET_DIR:${BuildConfig.VERSION_CODE}", "Julius assets")
        // Written every time rather than unpacked: the phrase follows the name in Settings, and the
        // automaton beside it never mentions a word, so only this file has to change.
        File(assets, "grammar/wake.dict").writeText(dict)
        val hmm = File(assets, "model/jnas-tri-3k16-gid.binhmm")
        val hlist = File(assets, "model/logicalTri-3k16-gid.bin")
        val grammar = File(assets, "grammar/wake")
        val binary = File(context.applicationInfo.nativeLibraryDir, "libjulius-bin.so")
        val adPort = freePort()
        val modulePort = freePort()
        val command = listOf(
            binary.absolutePath,
            "-h", hmm.absolutePath,
            "-hlist", hlist.absolutePath,
            "-gram", grammar.absolutePath,
            "-input", "adinnet",
            "-adport", adPort.toString(),
            "-module", modulePort.toString(),
            "-nocutsilence",
            "-n", "1",
            "-output", "1",
            "-penalty1", "-0.8",
            "-penalty2", "-0.8",
        )
        try {
            process = ProcessBuilder(command).start()
            threads += drain(process.inputStream, "julius/out")
            threads += drain(process.errorStream, "julius/err")
            // Julius blocks waiting for a module client before it even opens its adinnet server, so
            // the order of these two is not a preference.
            moduleSocket = connect(modulePort)
            adinnetSocket = connect(adPort)
            adinnetOut = adinnetSocket.getOutputStream()
        } catch (e: Exception) {
            runCatching { process.destroy() }
            throw e
        }
        threads += thread(name = "hachi-julius-module") { readModule() }
        threads += thread(name = "hachi-julius-adcmd") { readCommands() }
    }

    /** Feeds one chunk of microphone audio and reports whether the phrase has been heard since the last call. */
    fun accept(samples: ShortArray, count: Int): Boolean {
        alive()
        vad.accept(samples, count)
        alive()
        return woken.compareAndSet(true, false)
    }

    private fun alive() {
        if (!process.isAlive || broken) throw IOException("Julius is no longer running")
    }

    private fun freePort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

    private fun connect(port: Int): Socket {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw IOException("Julius exited before connecting (exit=${process.exitValue()})")
            try {
                return Socket(LOOPBACK, port).apply { tcpNoDelay = true }
            } catch (e: IOException) {
                last = e
                Thread.sleep(CONNECT_RETRY_MS)
            }
        }
        throw IOException("Timed out connecting to Julius on port $port", last)
    }

    /** A child whose pipes fill up stops dead, so both are read whether or not anyone wants the lines. */
    private fun drain(stream: InputStream, tag: String): Thread = thread(name = "hachi-$tag") {
        var lastLog = 0L
        try {
            BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                val now = System.currentTimeMillis()
                if (now - lastLog > LOG_INTERVAL_MS) { Log.d("Hachi", "$tag: $line"); lastLog = now }
            }
        } catch (_: IOException) { /* closed on shutdown */ }
    }

    private fun buffer(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val take = minOf(sendBuffer.size - sendFill, count - i)
            System.arraycopy(samples, i, sendBuffer, sendFill, take)
            sendFill += take; i += take
            if (sendFill == sendBuffer.size) { send(sendBuffer, sendBuffer.size); sendFill = 0 }
        }
    }

    private fun flush() {
        if (sendFill > 0) { send(sendBuffer, sendFill); sendFill = 0 }
    }

    @Synchronized private fun send(samples: ShortArray, count: Int) {
        if (closed) return
        val packet = ByteBuffer.allocate(4 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(count * 2)
        for (i in 0 until count) packet.putShort(samples[i])
        write(packet.array())
    }

    /** A zero-length packet is how adinnet says the utterance is over and may be decoded. */
    @Synchronized private fun sendEnd() {
        if (closed) return
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
    }

    private fun write(bytes: ByteArray) {
        try {
            adinnetOut.write(bytes); adinnetOut.flush()
        } catch (e: IOException) {
            broken = true
            throw e
        }
    }

    private fun readCommands() {
        try {
            val input = adinnetSocket.getInputStream()
            val buf = ByteArray(64)
            // Julius writes single pause/resume bytes back on this socket; nothing here acts on them.
            while (input.read(buf) >= 0) Unit
            if (!closed) broken = true
        } catch (_: IOException) { if (!closed) broken = true }
    }

    private fun readModule() {
        try {
            val reader = BufferedReader(InputStreamReader(moduleSocket.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                // What it thought each utterance was, with the confidence it gave. Without this, a
                // phrase that never wakes the house cannot be told from one that is never heard.
                if (BuildConfig.DEBUG && line.trim().startsWith("<WHYPO")) Log.d("Hachi", "heard: ${line.trim()}")
                if (recognition.feed(line)) woken.set(true)
            }
            if (!closed) broken = true
        } catch (_: IOException) { if (!closed) broken = true }
    }

    override fun close() {
        closed = true
        runCatching { adinnetOut.close() }
        runCatching { adinnetSocket.close() }
        runCatching { moduleSocket.close() }
        runCatching { process.destroy() }
        for (t in threads) runCatching { t.join(2000) }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }

    private companion object {
        const val ASSET_DIR = "julius"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val CONNECT_RETRY_MS = 100L
        const val LOG_INTERVAL_MS = 500L
        /**
         * Explicit IPv4 loopback, never InetAddress.getLoopbackAddress(): on this device that resolves
         * to ::1, and Julius binds IPv4 only, so every connection is refused and it looks like a hang.
         */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
