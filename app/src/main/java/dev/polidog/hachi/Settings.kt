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
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(name: String, fallback: String = "") = prefs.getString(name, fallback) ?: fallback
    fun set(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    fun flag(name: String, fallback: Boolean = false) = prefs.getBoolean(name, fallback)
    fun setFlag(name: String, value: Boolean) { prefs.edit().putBoolean(name, value).apply() }

    val geminiKey get() = secret("geminiKey")
    val model get() = get("model", DEFAULT_MODEL)
    val voice get() = get("voice", DEFAULT_VOICE)
    /** Seconds of silence that end a conversation. */
    val silenceTimeout get() = get("silenceTimeout", "30").toLongOrNull()?.coerceIn(5, 600) ?: 30L
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
        private const val KEY_ALIAS = "hachi-settings"
    }
}
