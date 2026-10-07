package com.airwhispers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.airwhispers.AppContainer
import com.airwhispers.core.AppError
import com.airwhispers.data.model.CallDetectionCapability
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.CallAssistSettings
import com.airwhispers.data.model.Contact
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.SpeechOutput
import com.airwhispers.data.model.SpeechSettings
import com.airwhispers.data.prefs.SettingsStore
import com.airwhispers.data.remote.RelayState
import com.airwhispers.data.repo.SessionState
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

    val session: StateFlow<SessionState> = repository.session
    val conversations: StateFlow<List<Conversation>> = repository.conversations
    val contacts: StateFlow<List<Contact>> = repository.contacts
    val messages: StateFlow<Map<String, List<Message>>> = repository.messages
    val sessionErrors: StateFlow<Map<String, String>> = repository.lastMessageErrors

    val callAssist: StateFlow<CallAssistSettings> = container.settingsStore.callAssist
    val speech: StateFlow<SpeechSettings> = container.settingsStore.speech
    val backendUrl: StateFlow<String> = container.settingsStore.backendUrl
    val queue: StateFlow<TtsQueueSnapshot> = container.ttsQueue.state
    val callStatus: StateFlow<CallStatus> = container.callDetector.status
    val capability: StateFlow<CallDetectionCapability> = container.callDetector.capability
    val relayState: StateFlow<RelayState> = container.realtime.state

    val audioRoute: String get() = container.audioRouter.currentRouteDescription()
    val deviceId: String get() = container.settingsStore.deviceId

    init {
        container.callDetector.refreshCapability()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onVisible() {
        container.onUiVisible()
        viewModelScope.launch { container.repository.restoreIfNeeded() }
    }

    fun onHidden() = container.onUiHidden()

    // ----------------------------------------------------------------------- auth

    fun configureBackend(url: String) = container.settingsStore.setBackendUrl(url)

    fun checkServer(onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = container.api.health()
            onResult(result is com.airwhispers.core.AppResult.Ok, (result as? com.airwhispers.core.AppResult.Err)?.error?.message)
        }
    }

    fun signIn(email: String, password: String, onError: (AppError?) -> Unit) {
        viewModelScope.launch {
            val error = repository.signIn(email, password)
            if (error == null) container.startRepositoryPipeline()
            onError(error)
        }
    }

    fun register(email: String, password: String, displayName: String, onError: (AppError?) -> Unit) {
        viewModelScope.launch {
            val error = repository.register(email, password, displayName)
            if (error == null) container.startRepositoryPipeline()
            onError(error)
        }
    }

    fun signOut() = viewModelScope.launch { repository.signOut() }

    // -------------------------------------------------------------------- messaging

    fun openConversationWith(peerUserId: String, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            repository.openConversationWith(peerUserId)?.let(onOpened)
        }
    }

    /** "New chat" entry point: add the peer by email, then open the conversation. */
    fun startChatWithEmail(
        email: String,
        onOpened: (String?) -> Unit,
        onError: (AppError?) -> Unit,
    ) {
        viewModelScope.launch {
            val error = repository.addContact(email)
            if (error != null) {
                onError(error)
                return@launch
            }
            val contact = repository.contacts.value.firstOrNull { it.email.equals(email.trim(), true) }
            if (contact == null) {
                onOpened(null)
                return@launch
            }
            val conversationId = contact.conversationId ?: repository.openConversationWith(contact.userId)
            onOpened(conversationId)
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

    // --------------------------------------------------------------------- contacts

    fun addContact(email: String, onError: (AppError?) -> Unit) {
        viewModelScope.launch { onError(repository.addContact(email)) }
    }

    fun setTrusted(contact: Contact, trusted: Boolean) {
        viewModelScope.launch { repository.setTrusted(contact, trusted) }
    }

    fun deleteContact(contact: Contact) = viewModelScope.launch { repository.deleteContact(contact) }

    fun refreshContacts() = viewModelScope.launch { repository.refreshContacts() }

    // ------------------------------------------------------------- Call Assist / TTS

    fun setCallAssistEnabled(enabled: Boolean) {
        container.settingsStore.updateCallAssist { it.copy(enabled = enabled) }
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
