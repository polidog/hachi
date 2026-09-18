package dev.polidog.hachi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.polidog.hachi.tools.ToolRegistry
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Runs one tool from the development machine and logs what it answered, without a conversation.
 *
 * ```
 * adb shell am broadcast -n dev.polidog.hachi/.DebugToolReceiver \
 *   -a dev.polidog.hachi.TOOL -e name get_calendar -e args '{"days":"7"}'
 * ```
 *
 * A tool's answer is otherwise only ever seen by the model, which makes "is it actually reading the
 * calendar" a question that needs a microphone and a paid session to ask. Debug builds only.
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val args = runCatching { JSONObject(intent.getStringExtra("args") ?: "{}") }
            .getOrElse { JSONObject() }
        val app = context.applicationContext
        val done = goAsync()
        // Tools do network I/O and are documented as never running on the main thread.
        thread {
            try {
                val result = ToolRegistry(app, Settings(app)).run(name, args)
                Log.i("Hachi", "debug tool $name -> $result")
            } finally {
                done.finish()
            }
        }
    }
}
