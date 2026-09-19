package dev.polidog.hachi

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Kitchen timers, set and cancelled by voice.
 *
 * Kept in the process rather than handed to the Clock app: asking DeskClock to set one opens its
 * activity over this one, which pauses the screen and with it the conversation that asked.
 * ponytail: lost if the process dies. The wall display never leaves the screen, so that has not
 * mattered; AlarmManager plus a receiver is the upgrade path if it does.
 */
object Timers {
    class Timer(val label: String, val endsAt: Long) {
        fun secondsLeft() = maxOf(0L, (endsAt - SystemClock.elapsedRealtime() + 999) / 1000)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val timers = mutableListOf<Timer>()
    private var ringtone: Ringtone? = null
    private val quiet = Runnable { silence() }

    /** Set by the screen while it is up: the bell is also a reason to light it. Main thread. */
    var onRing: (() -> Unit)? = null

    val ringing get() = ringtone?.isPlaying == true

    @Synchronized fun pending(): List<Timer> = timers.sortedBy { it.endsAt }

    @Synchronized fun set(context: Context, seconds: Long, label: String): Timer {
        val timer = Timer(label, SystemClock.elapsedRealtime() + seconds * 1000)
        timers += timer
        val app = context.applicationContext
        // The timer itself is the token, so cancelling one removes exactly its callback.
        main.postAtTime({ ring(app, timer) }, timer, timer.endsAt)
        return timer
    }

    /** Cancels the timers whose label contains [label], or every timer when it is blank. */
    @Synchronized fun cancel(label: String): List<Timer> {
        val gone = timers.filter { label.isBlank() || it.label.contains(label) }
        gone.forEach { main.removeCallbacksAndMessages(it) }
        timers -= gone.toSet()
        return gone
    }

    /** Stops the bell. Main thread. */
    fun silence() {
        main.removeCallbacks(quiet)
        ringtone?.stop()
        ringtone = null
    }

    private fun ring(context: Context, timer: Timer) {
        synchronized(this) { timers -= timer }
        Log.i("Hachi", "timer done: ${timer.label}")
        onRing?.invoke()
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
        // Nobody home should not mean ringing until someone is.
        main.postDelayed(quiet, RING_MS)
    }

    private const val RING_MS = 60_000L
}
