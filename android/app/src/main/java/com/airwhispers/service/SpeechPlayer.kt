package com.airwhispers.service

import com.airwhispers.core.AppLog
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.data.model.SpeechSettings
import com.airwhispers.data.prefs.SettingsStore
import com.airwhispers.domain.tts.SpeakResult
import com.airwhispers.domain.tts.SpeechItem
import com.airwhispers.domain.tts.SpeechSynthesizer
import com.airwhispers.domain.tts.TtsQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drains [TtsQueue] through the speech engine, one utterance at a time.
 *
 * Handles audio focus per utterance, one engine re-preparation+retry on failure,
 * the pause between messages, and instant interruption (skip/stop) through the
 * queue's interruption channel.
 */
class SpeechPlayer(
    private val queue: TtsQueue,
    private val synthesizer: SpeechSynthesizer,
    private val audioRouter: AudioRouter,
    private val settingsStore: SettingsStore,
    private val onUtteranceFinished: suspend (item: SpeechItem, spoken: Boolean, error: String?) -> Unit =
        { _, _, _ -> },
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
) {

    private var loopJob: Job? = null
    private var interruptJob: Job? = null

    @Volatile
    private var pendingUtterance: Deferred<SpeakResult>? = null

    @Volatile
    private var activeItemId: String? = null

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        interruptJob = scope.launch {
            queue.interruptions.collect { interruptedId ->
                if (interruptedId == activeItemId) {
                    AppLog.i("Player", "interrupt", "id" to AppLog.fingerprint(interruptedId))
                    synthesizer.stop()
                    pendingUtterance?.cancel()
                }
            }
        }
        loopJob = scope.launch(dispatchers.default) {
            while (isActive) {
                // awaitNext() only returns null if the queue is closed for good.
                val item = queue.awaitNext() ?: break
                activeItemId = item.id
                val outcome = speak(item, settingsStore.speech.value)
                activeItemId = null
                queue.completeCurrent(item.id, error = outcome.second)
                onUtteranceFinished(item, outcome.second == null, outcome.second)
                if (outcome.second == null) {
                    val pause = settingsStore.speech.value.pauseBetweenMessagesMs
                    if (pause > 0) delay(pause)
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        interruptJob?.cancel()
        loopJob = null
        interruptJob = null
        synthesizer.stop()
        audioRouter.abandonFocus()
    }

    /** Speaks one item, preparing the engine and retrying once on failure. */
    private suspend fun CoroutineScope.speak(item: SpeechItem, speech: SpeechSettings): Pair<SpeechItem, String?> {
        if (!synthesizer.available) synthesizer.prepare(speech)
        if (!synthesizer.available) {
            AppLog.e("Player", "engine_unavailable")
            return item to "Text-to-speech is unavailable on this device"
        }

        if (!audioRouter.requestFocus()) {
            // Keep going: being heard matters more than perfect manners.
            AppLog.w("Player", "speaking_without_focus")
        }
        return try {
            when (val first = runUtterance(item, speech)) {
                is SpeakResult.Completed -> item to null
                is SpeakResult.Stopped -> item to "stopped"
                is SpeakResult.Failed -> {
                    // The engine may have died (memory pressure, voice data removed).
                    AppLog.w("Player", "utterance_failed_retrying", first.error.cause)
                    synthesizer.shutdown()
                    if (synthesizer.prepare(speech)) {
                        when (val second = runUtterance(item, speech)) {
                            is SpeakResult.Completed -> item to null
                            is SpeakResult.Stopped -> item to "stopped"
                            is SpeakResult.Failed -> item to second.error.message
                        }
                    } else {
                        item to first.error.message
                    }
                }
            }
        } finally {
            audioRouter.abandonFocus()
        }
    }

    private suspend fun CoroutineScope.runUtterance(item: SpeechItem, speech: SpeechSettings): SpeakResult {
        val deferred = async { synthesizer.speak(item.utterance(), speech) }
        pendingUtterance = deferred
        return try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            // Distinguish "someone skipped this utterance" from "the service died".
            if (!currentCoroutineContext().isActive) throw cancelled
            SpeakResult.Stopped
        } finally {
            pendingUtterance = null
        }
    }
}
