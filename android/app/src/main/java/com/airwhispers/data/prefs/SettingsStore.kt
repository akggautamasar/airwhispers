package com.airwhispers.data.prefs

import android.content.Context
import com.airwhispers.data.model.CallAssistSettings
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.data.model.SpeechOutput
import com.airwhispers.data.model.SpeechSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Local, observable settings. Everything here is non-sensitive configuration;
 * secrets live in [SecretStore].
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _callAssist = MutableStateFlow(readCallAssist())
    val callAssist: StateFlow<CallAssistSettings> = _callAssist.asStateFlow()

    private val _speech = MutableStateFlow(readSpeech())
    val speech: StateFlow<SpeechSettings> = _speech.asStateFlow()

    private val _backendUrl = MutableStateFlow(readBackendUrl())
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** Set when the user has dismissed the in-app rationale for a permission. */
    var seenNotificationRationale: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_RATIONALE, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_RATIONALE, value).apply()

    fun setBackendUrl(url: String) {
        val normalised = normaliseBackendUrl(url)
        prefs.edit().putString(KEY_BACKEND_URL, normalised).apply()
        _backendUrl.value = normalised
    }

    fun updateCallAssist(transform: (CallAssistSettings) -> CallAssistSettings) {
        val next = transform(_callAssist.value)
        _callAssist.value = next
        prefs.edit()
            .putBoolean(KEY_CA_ENABLED, next.enabled)
            .putBoolean(KEY_CA_SPEAK, next.speakMessages)
            .putBoolean(KEY_CA_ONLY_CALLS, next.onlyDuringCalls)
            .putBoolean(KEY_CA_TRUSTED, next.trustedContactsOnly)
            .putBoolean(KEY_CA_BLUETOOTH, next.preferBluetooth)
            .putBoolean(KEY_CA_OWN, next.speakOwnMessages)
            .putBoolean(KEY_CA_NOTIFY, next.notifyWhileSpeaking)
            .apply()
    }

    fun updateSpeech(transform: (SpeechSettings) -> SpeechSettings) {
        val next = transform(_speech.value)
        _speech.value = next
        prefs.edit()
            .putString(KEY_SP_LANG, next.languageTag)
            .putFloat(KEY_SP_RATE, next.speechRate)
            .putFloat(KEY_SP_PITCH, next.pitch)
            .putString(KEY_SP_VOICE, next.voiceName)
            .putString(KEY_SP_OUTPUT, next.output.name)
            .putString(KEY_SP_EMOJI, next.emojiMode.name)
            .putLong(KEY_SP_PAUSE, next.pauseBetweenMessagesMs)
            .putBoolean(KEY_SP_WHISPER, next.whisperMode)
            .apply()
    }

    /** True once the user has finished the Call Assist introduction. */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /** Call Assist was on when the device shut down / the app was killed. */
    var callAssistWantedAfterBoot: Boolean
        get() = prefs.getBoolean(KEY_CA_WANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CA_WANTED, value).apply()

    private fun readCallAssist() = CallAssistSettings(
        enabled = prefs.getBoolean(KEY_CA_ENABLED, false),
        // Privacy-first default: nothing is ever spoken until the user opts in.
        speakMessages = prefs.getBoolean(KEY_CA_SPEAK, false),
        onlyDuringCalls = prefs.getBoolean(KEY_CA_ONLY_CALLS, true),
        trustedContactsOnly = prefs.getBoolean(KEY_CA_TRUSTED, true),
        preferBluetooth = prefs.getBoolean(KEY_CA_BLUETOOTH, true),
        speakOwnMessages = prefs.getBoolean(KEY_CA_OWN, false),
        notifyWhileSpeaking = prefs.getBoolean(KEY_CA_NOTIFY, false),
    )

    private fun readSpeech() = SpeechSettings(
        languageTag = prefs.getString(KEY_SP_LANG, null) ?: defaultLanguageTag(),
        speechRate = prefs.getFloat(KEY_SP_RATE, 1.0f),
        pitch = prefs.getFloat(KEY_SP_PITCH, 1.0f),
        voiceName = prefs.getString(KEY_SP_VOICE, null),
        output = prefs.getString(KEY_SP_OUTPUT, null)
            ?.let { runCatching { SpeechOutput.valueOf(it) }.getOrNull() }
            ?: SpeechOutput.SYSTEM_DEFAULT,
        emojiMode = prefs.getString(KEY_SP_EMOJI, null)
            ?.let { runCatching { EmojiMode.valueOf(it) }.getOrNull() }
            ?: EmojiMode.DESCRIBE_IMPORTANT,
        pauseBetweenMessagesMs = prefs.getLong(KEY_SP_PAUSE, 700L),
        whisperMode = prefs.getBoolean(KEY_SP_WHISPER, false),
    )

    private fun readBackendUrl(): String =
        prefs.getString(KEY_BACKEND_URL, null)
            ?: BuildConfigBackendUrl.value

    private fun defaultLanguageTag(): String {
        val locale = java.util.Locale.getDefault()
        val tag = locale.language
        return when (tag) {
            "hi" -> "hi-IN"
            "en" -> "en-IN"
            else -> tag
        }
    }

    companion object {
        private const val PREFS_NAME = "airwhispers.settings"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_CA_WANTED = "call_assist_wanted"
        private const val KEY_NOTIF_RATIONALE = "notif_rationale_seen"
        private const val KEY_CA_ENABLED = "ca_enabled"
        private const val KEY_CA_SPEAK = "ca_speak"
        private const val KEY_CA_ONLY_CALLS = "ca_only_calls"
        private const val KEY_CA_TRUSTED = "ca_trusted_only"
        private const val KEY_CA_BLUETOOTH = "ca_bluetooth"
        private const val KEY_CA_OWN = "ca_own"
        private const val KEY_CA_NOTIFY = "ca_notify"
        private const val KEY_SP_LANG = "sp_lang"
        private const val KEY_SP_RATE = "sp_rate"
        private const val KEY_SP_PITCH = "sp_pitch"
        private const val KEY_SP_VOICE = "sp_voice"
        private const val KEY_SP_OUTPUT = "sp_output"
        private const val KEY_SP_EMOJI = "sp_emoji"
        private const val KEY_SP_PAUSE = "sp_pause"
        private const val KEY_SP_WHISPER = "sp_whisper"

        /** Normalises user input into an origin we can append `/api/v1` to. */
        fun normaliseBackendUrl(raw: String): String {
            var value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return value
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "https://$value"
            }
            // Tolerate people pasting the full API path.
            value = value.removeSuffix("/api/${com.airwhispers.config.ProductConfig.DEFAULT_API_VERSION}")
                .removeSuffix("/api")
            return value.trimEnd('/')
        }
    }
}

/** Default backend URL baked into the APK at build time (may be empty). */
internal object BuildConfigBackendUrl {
    val value: String
        get() = com.airwhispers.BuildConfig.DEFAULT_BACKEND_URL
}
