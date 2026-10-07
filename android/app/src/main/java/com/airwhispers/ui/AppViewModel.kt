package com.airwhispers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.airwhispers.AppContainer
import com.airwhispers.core.AppError
import com.airwhispers.core.AppResult
import com.airwhispers.data.model.CallAssistSettings
import com.airwhispers.data.model.CallDetectionCapability
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.SpeechOutput
import com.airwhispers.data.model.SpeechSettings
import com.airwhispers.data.prefs.SettingsStore
import com.airwhispers.data.remote.RelayState
import com.airwhispers.data.remote.ServerInfoDto
import com.airwhispers.data.repo.IdentityState
import com.airwhispers.data.repo.TypingSignal
import com.airwhispers.domain.tts.TtsQueueSnapshot
import com.airwhispers.service.Notifier
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Single view-model for the whole app.
 *
 * The UI is a thin projection of repository state; every action is a one-liner
 * that forwards to the repository or to Call Assist. That keeps Compose code
 * declarative and free of business rules.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.repository

    val session: StateFlow<IdentityState> = repository.session
    val conversations: StateFlow<List<Conversation>> = repository.conversations
    val messages: StateFlow<Map<String, List<Message>>> = repository.messages
    val sessionErrors: StateFlow<Map<String, String>> = repository.lastMessageErrors
    val typing: StateFlow<TypingSignal?> = repository.typing

    val callAssist: StateFlow<CallAssistSettings> = container.settingsStore.callAssist
    val speech: StateFlow<SpeechSettings> = container.settingsStore.speech
    val backendUrl: StateFlow<String> = container.settingsStore.backendUrl
    val queue: StateFlow<TtsQueueSnapshot> = container.ttsQueue.state
    val callStatus: StateFlow<CallStatus> = container.callDetector.status
    val capability: StateFlow<CallDetectionCapability> = container.callDetector.capability
    val relayState: StateFlow<RelayState> = container.realtime.state

    val audioRoute: String get() = container.audioRouter.currentRouteDescription()
    val deviceId: String get() = container.settingsStore.deviceId

    /** The code other people type to reach this phone. */
    val myCode: String? get() = repository.myCode ?: container.settingsStore.myCode

    /** `k7m2pq` → `K7M 2PQ`. */
    val myPrettyCode: String
        get() = myCode?.uppercase()?.replace(Regex("(.{3})(.{3})"), "$1 $2") ?: "······"

    init {
        container.callDetector.refreshCapability()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onVisible() {
        container.onUiVisible()
        viewModelScope.launch { container.repository.restoreIfNeeded() }
    }

    fun onHidden() = container.onUiHidden()

    // ---------------------------------------------------------------------- setup

    fun configureBackend(url: String) = container.settingsStore.setBackendUrl(url)

    /**
     * "Is this an AirWhispers server?" — checks /healthz and then asks the server
     * what it expects. Two calls, no authentication, so a typo in the address
     * fails here instead of confusing the user later.
     */
    fun checkServer(onResult: (ServerInfoDto?, AppError?) -> Unit) {
        viewModelScope.launch {
            when (val health = container.api.health()) {
                is AppResult.Err -> onResult(null, health.error)
                is AppResult.Ok -> when (val info = container.api.serverInfo()) {
                    is AppResult.Ok -> onResult(info.value, null)
                    is AppResult.Err -> onResult(null, info.error)
                }
            }
        }
    }

    /** Registers this device (or resumes it) and starts the realtime pipeline. */
    fun completeSetup(displayName: String, onResult: (AppError?) -> Unit) {
        viewModelScope.launch {
            val state = repository.ensureRegistered(displayName.trim().ifBlank { null })
            if (state.status == com.airwhispers.data.repo.IdentityStatus.READY && state.me != null) {
                container.startRepositoryPipeline()
                onResult(null)
            } else {
                onResult(state.error ?: AppError.unknown("Could not register this device"))
            }
        }
    }

    fun setDisplayName(name: String, onResult: (AppError?) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.setDisplayName(name)) }
    }

    /** Forget this identity and register a new one: a brand-new code. */
    fun requestNewCode(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.regenerateIdentity()
            container.startRepositoryPipeline()
            onDone()
        }
    }

    // ------------------------------------------------------------------ messaging

    fun openChatWithCode(code: String, onResult: (String?, AppError?) -> Unit) {
        viewModelScope.launch {
            val (conversationId, error) = repository.openChatWithCode(code)
            onResult(conversationId, error)
        }
    }

    fun loadMessages(conversationId: String) =
        viewModelScope.launch { repository.loadMessages(conversationId) }

    fun markRead(conversationId: String) =
        viewModelScope.launch { repository.markConversationRead(conversationId) }

    fun send(conversationId: String, text: String, speakNow: Boolean) {
        if (text.isBlank()) return
        viewModelScope.launch { repository.sendMessage(conversationId, text, speakNow) }
    }

    fun retry(clientMessageId: String) = viewModelScope.launch { repository.retryMessage(clientMessageId) }

    fun refreshConversations() = viewModelScope.launch { repository.refreshConversations() }

    fun notifyTyping(conversationId: String) = repository.sendTyping(conversationId)

    // -------------------------------------------------------------------- consent

    /** "Let this person whisper to me" — the switch Call Assist depends on. */
    fun setSpeakAllowed(conversation: Conversation, allowed: Boolean) {
        viewModelScope.launch { repository.setSpeakAllowed(conversation, allowed) }
    }

    /** Allow/deny speech for every peer listed in a conversation set. */
    fun allowAllSpeakers(conversations: List<Conversation>, allowed: Boolean) {
        viewModelScope.launch {
            conversations.forEach { repository.setSpeakAllowed(it, allowed) }
        }
    }

    // ------------------------------------------------------------- Call Assist / TTS

    fun setCallAssistEnabled(enabled: Boolean) {
        container.settingsStore.updateCallAssist { it.copy(enabled = enabled) }
    }

    /** One tap from the Call Assist screen: listening on, speaking on. */
    fun startListening() {
        container.settingsStore.updateCallAssist { it.copy(enabled = true, speakMessages = true) }
    }

    fun stopListening() {
        container.settingsStore.updateCallAssist { it.copy(enabled = false) }
    }

    fun updateCallAssist(transform: (CallAssistSettings) -> CallAssistSettings) =
        container.settingsStore.updateCallAssist(transform)

    fun updateSpeech(transform: (SpeechSettings) -> SpeechSettings) =
        container.settingsStore.updateSpeech(transform)

    fun setManualCallOverride(inCall: Boolean) = container.callDetector.setManualOverride(inCall)

    fun pauseSpeech() = container.ttsQueue.pause()

    fun resumeSpeech() = container.ttsQueue.resume()

    fun skipSpeech() = container.ttsQueue.skip()

    fun stopSpeech() = container.ttsQueue.stop()

    fun clearQueue() = container.ttsQueue.clearPending()

    fun availableVoices(onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            val tag = container.settingsStore.speech.value.languageTag
            onResult(container.synthesizer.voices(tag))
        }
    }

    fun speakerAvailable(): Boolean = container.synthesizer.available

    /** Speaks the preview sentence with the current settings, no service needed. */
    fun testSpeak() {
        viewModelScope.launch {
            val current = container.settingsStore.speech.value
            if (container.synthesizer.prepare(current)) {
                container.synthesizer.speak("This is how a message will sound.", current)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
        }

        val EMOJI_LABEL: Map<EmojiMode, String> = mapOf(
            EmojiMode.IGNORE to "Ignore emojis",
            EmojiMode.DESCRIBE_IMPORTANT to "Describe important emojis",
            EmojiMode.READ_ALL to "Read all emojis",
        )

        val OUTPUT_LABEL: Map<SpeechOutput, String> = mapOf(
            SpeechOutput.SYSTEM_DEFAULT to "System default",
            SpeechOutput.PREFER_BLUETOOTH to "Bluetooth when connected",
            SpeechOutput.PHONE_SPEAKER to "Phone speaker",
        )

        fun notificationActionStart(): String = Notifier.ACTION_START

        /** Small helper so screens do not import SettingsStore directly. */
        fun normaliseBackend(raw: String): String = SettingsStore.normaliseBackendUrl(raw)
    }
}
