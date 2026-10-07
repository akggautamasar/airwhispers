package com.airwhispers.service

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.airwhispers.core.AppError
import com.airwhispers.core.AppErrorKind
import com.airwhispers.core.AppLog
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.data.model.SpeechSettings
import com.airwhispers.domain.tts.SpeakResult
import com.airwhispers.domain.tts.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `android.speech.tts.TextToSpeech` implementation.
 *
 * Deliberately the *device* engine: free, offline, private (no message text
 * leaves the phone) and lowest latency — which matters because the user is mid
 * conversation. A cloud engine can be added later behind [SpeechSynthesizer]
 * without touching the queue, the assist engine or the UI.
 */
class AndroidTtsSynthesizer(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
) : SpeechSynthesizer {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SpeakResult>>()
    private val utteranceCounter = AtomicInteger(0)

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initResult: Boolean? = null

    @Volatile
    override var available: Boolean = false
        private set

    override suspend fun prepare(settings: SpeechSettings): Boolean {
        val tts = ensureEngine() ?: return false
        return withContext(dispatchers.main) {
            val locale = Locale.forLanguageTag(settings.languageTag.ifBlank { "en-IN" })
            var result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                AppLog.w("Tts", "language_unsupported", null, "tag" to settings.languageTag)
                // Fall back to the device default rather than staying silent.
                result = tts.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    available = false
                    return@withContext false
                }
            }
            settings.voiceName?.let { name ->
                runCatching {
                    tts.voices?.firstOrNull { it.name == name }?.let { voice -> tts.voice = voice }
                }.onFailure { AppLog.w("Tts", "voice_unavailable", it) }
            }
            tts.setSpeechRate(settings.speechRate.coerceIn(0.3f, 2.0f))
            tts.setPitch(settings.pitch.coerceIn(0.5f, 2.0f))
            available = true
            true
        }
    }

    override suspend fun speak(text: String, settings: SpeechSettings): SpeakResult {
        val tts = ensureEngine()
            ?: return SpeakResult.Failed(AppError(AppErrorKind.SPEECH_UNAVAILABLE, "No text-to-speech engine"))
        if (!available && !prepare(settings)) {
            return SpeakResult.Failed(AppError(AppErrorKind.SPEECH_UNAVAILABLE, "Speech engine unavailable"))
        }
        if (text.isBlank()) return SpeakResult.Completed

        val utteranceId = "aw-${utteranceCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<SpeakResult>()
        pending[utteranceId] = deferred
        return try {
            val accepted = withContext(dispatchers.main) {
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, if (settings.whisperMode) 0.45f else 1.0f)
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) == TextToSpeech.SUCCESS
            }
            if (accepted) {
                deferred.await()
            } else {
                SpeakResult.Failed(AppError(AppErrorKind.SPEECH_UNAVAILABLE, "Engine rejected utterance"))
            }
        } catch (cancelled: CancellationException) {
            // Skip/stop: silence the engine immediately, then propagate.
            runCatching { tts.stop() }
            throw cancelled
        } finally {
            pending.remove(utteranceId)
        }
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override suspend fun languages(): List<String> = withContext(dispatchers.default) {
        val tts = ensureEngine() ?: return@withContext emptyList()
        runCatching { tts.availableLanguages.map { locale -> locale.toLanguageTag() }.sorted() }
            .getOrDefault(emptyList())
    }

    override suspend fun voices(languageTag: String): List<String> = withContext(dispatchers.default) {
        val tts = ensureEngine() ?: return@withContext emptyList()
        val prefix = languageTag.substringBefore('-').lowercase(Locale.US)
        runCatching {
            tts.voices
                ?.filter { voice: Voice -> voice.locale.language.lowercase(Locale.US) == prefix }
                ?.map { voice -> voice.name }
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    override fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
        available = false
        initResult = null
    }

    /** Creates the engine once, installs one progress listener, waits for init. */
    private suspend fun ensureEngine(): TextToSpeech? {
        engine?.let { if (initResult == true) return it }
        if (initResult == false) return null
        return withContext(dispatchers.main) {
            if (engine == null) {
                val tts = TextToSpeech(appContext) { status ->
                    initResult = status == TextToSpeech.SUCCESS
                    available = initResult == true
                    if (initResult == false) AppLog.e("Tts", "init_failed", null, "status" to status)
                }
                tts.setOnUtteranceProgressListener(progressListener)
                engine = tts
            }
            var waited = 0L
            while (initResult == null && waited < INIT_TIMEOUT_MS) {
                delay(50)
                waited += 50
            }
            if (initResult == true) engine else null
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { id -> pending.remove(id)?.complete(SpeakResult.Completed) }
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { id ->
                pending.remove(id)?.complete(
                    SpeakResult.Failed(AppError(AppErrorKind.SPEECH_UNAVAILABLE, "Speech engine error")),
                )
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            AppLog.w("Tts", "utterance_error", null, "code" to errorCode)
            utteranceId?.let { id ->
                pending.remove(id)?.complete(
                    SpeakResult.Failed(AppError(AppErrorKind.SPEECH_UNAVAILABLE, "Speech engine error ($errorCode)")),
                )
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { id -> pending.remove(id)?.complete(SpeakResult.Stopped) }
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
    }
}
