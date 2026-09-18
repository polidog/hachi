package dev.polidog.hachi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Every user-visible setting, in one SharedPreferences file. Credentials go through [secret] /
 * [setSecret], which wrap the value with an AES/GCM key held in the Android Keystore, so the raw
 * API key is never written to disk in the clear.
 */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(name: String, fallback: String = "") = prefs.getString(name, fallback) ?: fallback
    fun set(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    fun flag(name: String, fallback: Boolean = false) = prefs.getBoolean(name, fallback)
    fun setFlag(name: String, value: Boolean) { prefs.edit().putBoolean(name, value).apply() }

    val geminiKey get() = secret("geminiKey")

    /**
     * What Hachi is called: the name it answers to and the name [WakeWord] waits for.
     *
     * Unset means the name it was shipped with, so a fresh install still has something to be called
     * -- there is no state where the assistant has no name at all.
     */
    val assistantName: String
        get() = get("assistantName").trim().ifBlank { app.getString(R.string.speaker_assistant) }

    /**
     * What to call whoever is standing there, when Hachi is called by name and answers back.
     *
     * Blank is a real answer, not a missing one: a house with more than one person in it has nobody
     * in particular to name, and "なんだい？" on its own is what gets said then.
     */
    val userName: String get() = get("userName").trim()
    val model get() = get("model", DEFAULT_MODEL)
    val voice get() = get("voice", DEFAULT_VOICE)
    /** Where the weather is fetched for; null until one has been chosen. */
    val weatherPlace: Place?
        get() {
            val name = get("weatherPlace")
            val latitude = get("weatherLat").toDoubleOrNull()
            val longitude = get("weatherLon").toDoubleOrNull()
            return if (name.isNotBlank() && latitude != null && longitude != null) {
                Place(name, latitude, longitude)
            } else {
                null
            }
        }

    fun setWeatherPlace(place: Place) {
        set("weatherPlace", place.name)
        set("weatherLat", place.latitude.toString())
        set("weatherLon", place.longitude.toString())
    }

    /**
     * Calendars left off the wall and out of the model's answers, by provider id.
     *
     * Stored as the ones hidden rather than the ones shown, so a calendar added to the account later
     * turns up on its own instead of silently staying away.
     */
    var hiddenCalendars: Set<Long>
        get() = get("hiddenCalendars").split(",").mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = set("hiddenCalendars", value.joinToString(","))

    /** Yahoo! Japan application id for the rain radar; blank simply disables that half. */
    val yahooAppId get() = secret("yahooAppId")

    /** Where Home Assistant lives, and the long-lived token to talk to it with. */
    val homeAssistantUrl get() = get("homeAssistantUrl")
    val homeAssistantToken get() = secret("homeAssistantToken")

    /**
     * The house's tool list as it was last fetched, as the raw `tools` array.
     *
     * A conversation starts on the main thread and cannot wait for the network, so the tools offered
     * are the ones from last time. Only the very first conversation after setup goes without them.
     */
    var houseTools: String
        get() = get("houseTools")
        set(value) = set("houseTools", value)

    /** Dollars of Gemini spend allowed per day; 0 means no limit. */
    val dailyCapUsd get() = get("dailyCap", DEFAULT_DAILY_CAP).toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    /** Seconds of silence that end a conversation. */
    val silenceTimeout get() = get("silenceTimeout", "30").toLongOrNull()?.coerceIn(5, 600) ?: 30L
    /**
     * How loud a moment has to be before the wake word listener bothers decoding it.
     *
     * A calibration knob, not a feature: what counts as speech depends on this microphone's gain,
     * which no amount of code can work out on its own. Set it from a development machine after
     * reading the level line WakeWord logs. Too high and it never hears anything; too low only costs
     * some decoding, since the grammar throws out what is not the phrase.
     */
    val wakeLevel get() = get("wakeLevel", "80").toDoubleOrNull()?.coerceIn(10.0, 5000.0) ?: 80.0

    /** Whether saying [assistantName] out loud starts a conversation. */
    var wakeEnabled: Boolean
        get() = flag("wakeEnabled")
        set(value) = setFlag("wakeEnabled", value)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun secret(name: String): String {
        val saved = get(name)
        if (saved.isBlank()) return ""
        val parts = saved.split(":")
        if (parts.size != 2) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // A wiped or rotated Keystore key leaves undecryptable ciphertext behind; treat it as unset
            // rather than crashing the app on every read.
            ""
        }
    }

    fun setSecret(name: String, value: String) {
        if (value.isBlank()) { set(name, ""); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        set(
            name,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Aoede"
        const val DEFAULT_DAILY_CAP = "0.50"
        private const val KEY_ALIAS = "hachi-settings"
    }
}
