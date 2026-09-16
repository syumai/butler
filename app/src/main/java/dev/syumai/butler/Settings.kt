package dev.syumai.butler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The on-device wake-word engine. Julius (default since 2026-09-16) is a phone-loop grammar decoder run
 * as a child process; on-device verification that day judged it good enough to become the default. Vosk
 * is a continuous small-vocabulary ASR restricted to a runtime grammar, and remains selectable as the
 * alternative. See `LocalWakeWordEngine.kt`'s `VoskWakeDecoder`/`JuliusWakeDecoder` and
 * `third_party/julius/README.md` for the 2026-09-15 offline comparison between the two engines. */
enum class WakeEngine(val id: String, val labelRes: Int) {
    VOSK("vosk", R.string.wake_engine_vosk),
    JULIUS("julius", R.string.wake_engine_julius);

    companion object {
        val DEFAULT = JULIUS
        fun fromId(id: String?): WakeEngine {
            val normalized = id?.trim()?.lowercase()
            if (normalized.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}

class Settings(context: Context) {
    companion object {
        // Set by SettingsActivity after a change that MainActivity's home screen needs to reflect
        // (background image, weather region, weather-linked background toggle). In-memory only,
        // process-lifetime; MainActivity clears it after rebuilding the home screen in onResume.
        @Volatile var dirty = false
    }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        // Remove obsolete provider credentials and model after migration.
        prefs.edit().remove("picovoice").remove("sensitivity").remove("wakePhrase")
            .remove("wakeThreshold").apply()
        File(context.filesDir, "butler.ppn").delete()
    }
    val background = File(context.filesDir, "background.jpg")
    fun get(name: String, fallback: String = "") = prefs.getString(name, fallback) ?: fallback
    fun set(name: String, value: String) { prefs.edit().putString(name, value).apply() }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var weatherBackground: Boolean
        get() = prefs.getBoolean("weatherBackground", false)
        set(value) { prefs.edit().putBoolean("weatherBackground", value).apply() }
    // Wake phrase selection is not exposed to the user yet; fixed to Hello Butler. Hey Butler was
    // tried first but caused too many false wakes, so the longer Hello Butler phrase was adopted.
    // Vosk's Japanese model detects the Japanese pronunciation ("ハロー、バトラー") only.
    val wakePhrase get() = WakePhrase.HELLO_BUTLER
    // Default is Julius, since 2026-09-16, after on-device verification; Vosk stays selectable in
    // Settings -> Wake as the alternative (see WakeEngine's doc comment).
    var wakeEngine: WakeEngine
        get() = WakeEngine.fromId(get("wakeEngine"))
        set(value) = set("wakeEngine", value.id)
    val timeoutSeconds get() = get("timeout", "30").toLongOrNull()?.coerceIn(5, 600) ?: 30L
    val voice get() = Voice.fromId(get("voice"))
    /** Like [voice], but falls back to [api]'s default when the stored voice isn't valid for [api]. */
    fun voice(api: VoiceApi): Voice = Voice.fromId(get("voice"), api)
    fun setVoice(value: Voice) = set("voice", value.id)
    val voiceApi get() = VoiceApi.fromId(get("voiceApi"))
    fun setVoiceApi(value: VoiceApi) = set("voiceApi", value.id)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("butler-settings", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("butler-settings", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun secret(name: String): String {
        val saved = get(name)
        if (saved.isBlank()) return ""
        val parts = saved.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun setSecret(name: String, value: String) {
        if (value.isBlank()) { set(name, ""); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        set(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
    }
}
