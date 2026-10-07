package com.airwhispers.domain.tts

import com.airwhispers.core.AppError
import com.airwhispers.data.model.SpeechSettings

/** Result of a single utterance. */
sealed interface SpeakResult {
    /** The engine finished the utterance normally. */
    data object Completed : SpeakResult

    /** Playback was aborted by the user (skip / stop) or by the engine. */
    data object Stopped : SpeakResult

    data class Failed(val error: AppError) : SpeakResult
}

/**
 * Thin abstraction over a text-to-speech engine.
 *
 * Implemented by [com.airwhispers.service.AndroidTtsSynthesizer] on top of
 * `android.speech.tts.TextToSpeech`. The app prefers the *device* engine so that
 * speech keeps working offline and nothing leaves the phone — no cloud TTS.
 */
interface SpeechSynthesizer {

    /** True once [prepare] succeeded and an engine is usable. */
    val available: Boolean

    /** Initialises/validates the engine for the requested settings. */
    suspend fun prepare(settings: SpeechSettings): Boolean

    /** Speaks [text] and suspends until it finishes, is stopped, or fails. */
    suspend fun speak(text: String, settings: SpeechSettings): SpeakResult

    /** Interrupts whatever is being spoken right now. */
    fun stop()

    /** Languages installed on the device, as BCP-47 tags. */
    suspend fun languages(): List<String>

    /** Voices available for a language tag (engine-specific names). */
    suspend fun voices(languageTag: String): List<String>

    fun shutdown()
}
