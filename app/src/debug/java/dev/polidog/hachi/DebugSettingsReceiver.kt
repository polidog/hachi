package dev.polidog.hachi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Types a setting in from the development machine, for the ones too long to enter on a 5" screen by
 * hand -- API keys and access tokens.
 *
 * ```
 * adb shell am broadcast -n dev.polidog.hachi/.DebugSettingsReceiver \
 *   -a dev.polidog.hachi.SET -e name yahooAppId -e value 'xxxxx'
 * ```
 *
 * Names in [SECRETS] are stored encrypted, exactly as the settings screen stores them; anything else
 * is written as plain text, and only names that already mean something are accepted so a typo fails
 * loudly instead of writing a setting nothing reads.
 *
 * Debug builds only (see this source set's manifest). The value travels as a shell argument, so it
 * is visible to anything that can read the device's process list -- fine for a machine on the desk,
 * not a way to hand someone a key.
 */
class DebugSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val value = intent.getStringExtra("value").orEmpty()
        val settings = Settings(context)
        val result = when {
            name.isBlank() -> "name is required"
            name in SECRETS -> {
                settings.setSecret(name, value)
                "$name set (${value.length} chars, encrypted)"
            }
            name in PLAIN -> {
                settings.set(name, value)
                "$name = $value"
            }
            else -> "unknown setting: $name (known: ${(SECRETS + PLAIN).sorted().joinToString(", ")})"
        }
        Log.i("Hachi", "debug settings: $result")
        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
    }

    private companion object {
        val SECRETS = setOf("geminiKey", "yahooAppId", "homeAssistantToken", "immichKey")
        val PLAIN = setOf(
            "model", "voice", "silenceTimeout", "dailyCap",
            "weatherPlace", "weatherLat", "weatherLon",
            "homeAssistantUrl", "immichUrl", "immichAlbum", "debugScene",
        )
    }
}
