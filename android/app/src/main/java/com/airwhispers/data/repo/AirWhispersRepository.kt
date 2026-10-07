package com.airwhispers.data.repo

import android.content.Context
import com.airwhispers.BuildConfig
import com.airwhispers.core.AppError
import com.airwhispers.core.AppErrorKind
import com.airwhispers.core.AppLog
import com.airwhispers.core.AppResult
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.core.TimeSource
import com.airwhispers.data.local.LocalStore
import com.airwhispers.data.model.Account
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.Contact
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState as ModelDeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.data.remote.ApiClient
import com.airwhispers.data.remote.ContactDto
import com.airwhispers.data.remote.ConversationDto
import com.airwhispers.data.remote.EventDto
import com.airwhispers.data.remote.EventTypes
import com.airwhispers.data.remote.MessageDto
import com.airwhispers.data.remote.RealtimeClient
import com.airwhispers.data.remote.TimeParse
import com.airwhispers.data.remote.UpdateSettingsRequest
import com.airwhispers.data.remote.UserDto
import com.airwhispers.data.prefs.SecretStore
import com.airwhispers.data.prefs.SettingsStore
import com.airwhispers.domain.assist.CallAssistEngine
import com.airwhispers.domain.tts.TtsQueue
import com.airwhispers.push.PushBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class SessionStatus { UNKNOWN, SIGNED_OUT, SIGNED_IN }

data class SessionState(
    val status: SessionStatus = SessionStatus.UNKNOWN,
    val account: Account? = null,
    val error: AppError? = null,
    val busy: Boolean = false,
)

/**
 * Single entry point for the app's data needs.
 *
 * Owns: session lifecycle, the local cache, the realtime event pipeline, the
 * outbox (send/retry), and the hand-off into Call Assist. The UI talks to this
 * class only, which keeps Compose code free of networking concerns.
 */
class AirWhispersRepository(
    private val context: Context,
    private val api: ApiClient,
    private val realtime: RealtimeClient,
    private val local: LocalStore,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val engine: CallAssistEngine,
    private val queue: TtsQueue,
    private val dispatchers: DispatcherProvider,
    private val time: TimeSource = TimeSource.SYSTEM,
    /** Live call state, supplied by the foreground service that owns detection. */
    private val callStatusProvider: () -> CallStatus = { CallStatus() },
    private val onIncomingMessage: suspend (Message, String) -> Unit = { _, _ -> },
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _lastMessageErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastMessageErrors: StateFlow<Map<String, String>> = _lastMessageErrors.asStateFlow()

    private val _speechDecision = MutableStateFlow<CallAssistEngine.Outcome?>(null)
    val lastSpeechDecision: StateFlow<CallAssistEngine.Outcome?> = _speechDecision.asStateFlow()

    private val outboxMutex = Mutex()
    private var outboxJob: Job? = null
    private var eventJob: Job? = null

    val currentUserId: String? get() = secrets.userId()
    val deviceId: String get() = settings.deviceId

    // ------------------------------------------------------------------ session

    private var restoreAttempted = false

    /** Called by the UI on first composition; runs at most once per process. */
    suspend fun restoreIfNeeded() {
        if (restoreAttempted) return
        restoreAttempted = true
        restoreSession()
    }

    suspend fun restoreSession(): SessionState = withSession(UNKNOWN_PLACEHOLDER) {
        if (secrets.accessToken().isNullOrBlank()) {
            _session.value = SessionState(status = SessionStatus.SIGNED_OUT)
            return@withSession
        }
        when (val result = api.me()) {
            is AppResult.Ok -> enterSession(result.value.user.toAccount())
            is AppResult.Err -> {
                if (result.error.kind == AppErrorKind.UNAUTHORIZED || result.error.kind == AppErrorKind.NETWORK) {
                    // Offline start: keep the cached identity, refresh later.
                    val cachedId = secrets.userId()
                    if (cachedId != null && result.error.kind == AppErrorKind.NETWORK) {
                        enterSession(Account(cachedId, "", "", null, time.nowMillis()))
                    } else {
                        secrets.clearSession()
                        _session.value = SessionState(status = SessionStatus.SIGNED_OUT, error = result.error)
                    }
                } else {
                    _session.value = SessionState(status = SessionStatus.SIGNED_OUT, error = result.error)
                }
            }
        }
    }

    suspend fun signIn(email: String, password: String): AppError? = withSession(null) {
        when (val result = api.login(email.trim(), password)) {
            is AppResult.Ok -> {
                enterSession(result.value.user.toAccount())
                null
            }
            is AppResult.Err -> {
                _session.value = _session.value.copy(error = result.error)
                result.error
            }
        }
    }

    suspend fun register(email: String, password: String, displayName: String): AppError? =
        withSession(null) {
            when (val result = api.register(email.trim(), password, displayName.trim())) {
                is AppResult.Ok -> {
                    enterSession(result.value.user.toAccount())
                    null
                }
                is AppResult.Err -> {
                    _session.value = _session.value.copy(error = result.error)
                    result.error
                }
            }
        }

    suspend fun signOut() {
        stopPipeline()
        api.logout()
        secrets.clearSession()
        local.purgeAll()
        queue.reset()
        engine.resetAnnouncementState()
        _session.value = SessionState(status = SessionStatus.SIGNED_OUT)
        _conversations.value = emptyList()
        _contacts.value = emptyList()
        _messages.value = emptyMap()
    }

    /** Kicks off initial loading, the realtime subscription and the outbox. */
    fun startPipeline(scope: CoroutineScope) {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            realtime.events.collect { event -> onEvent(event) }
        }
        outboxJob = scope.launch(dispatchers.io) { runOutbox() }
        scope.launch(dispatchers.io) {
            refreshConversations()
            refreshContacts()
            registerDevice()
        }
    }

    fun stopPipeline() {
        eventJob?.cancel()
        outboxJob?.cancel()
        eventJob = null
        outboxJob = null
    }

    // ------------------------------------------------------------------- loading

    suspend fun refreshConversations(): AppResult<List<Conversation>> {
        val result = api.conversations()
        return when (result) {
            is AppResult.Ok -> {
                val domain = result.value.conversations.map { it.toDomain() }
                local.upsertConversations(domain)
                _conversations.value = domain.sortedByDescending { it.lastMessageAt ?: 0L }
                AppResult.Ok(domain)
            }
            is AppResult.Err -> {
                // Fall back to cache so the app is usable offline.
                val cached = local.conversations()
                if (cached.isNotEmpty()) _conversations.value = cached
                result
            }
        }
    }

    suspend fun refreshContacts(): AppResult<List<Contact>> {
        val result = api.contacts()
        return when (result) {
            is AppResult.Ok -> {
                val domain = result.value.contacts.map(ContactDto::toDomain)
                local.upsertContacts(domain)
                _contacts.value = domain
                AppResult.Ok(domain)
            }
            is AppResult.Err -> {
                val cached = local.contacts()
                if (cached.isNotEmpty()) _contacts.value = cached
                result
            }
        }
    }

    suspend fun loadMessages(conversationId: String, limit: Int = 100): AppResult<List<Message>> {
        val cached = local.messages(conversationId)
        if (cached.isNotEmpty()) publishMessages(conversationId, cached)
        return when (val result = api.messages(conversationId, limit)) {
            is AppResult.Ok -> {
                val selfId = currentUserId.orEmpty()
                val domain = result.value.messages.map { it.toDomain(conversationId, selfId) }
                local.upsertMessages(domain)
                val merged = local.messages(conversationId)
                publishMessages(conversationId, merged)
                AppResult.Ok(merged)
            }
            is AppResult.Err -> result
        }
    }

    suspend fun markConversationRead(conversationId: String) {
        val messages = local.messages(conversationId)
            .filter { !it.isMine && it.deliveryState != ModelDeliveryState.READ }
        local.resetUnread(conversationId)
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) it.copy(unreadCount = 0) else it
        }
        messages.forEach { api.markRead(it.id) }
    }

    // -------------------------------------------------------------------- sending

    /** Queues a message locally and returns immediately (optimistic UI). */
    suspend fun sendMessage(conversationId: String, text: String, speakNow: Boolean): Message {
        val selfId = currentUserId.orEmpty()
        val conversation = local.conversation(conversationId)
        val recipientId = conversation?.peerId.orEmpty()
        val message = Message(
            id = "",
            clientMessageId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = selfId,
            recipientId = recipientId,
            text = text.trim(),
            createdAt = time.nowMillis(),
            state = MessageState.PENDING,
            deliveryState = ModelDeliveryState.NONE,
            priority = if (speakNow) MessagePriority.SPEAK_NOW else MessagePriority.NORMAL,
            isMine = true,
        )
        local.upsertMessage(message)
        local.markConversationPreview(conversationId, message.text, message.createdAt)
        publishMessages(conversationId, local.messages(conversationId))
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) it.copy(lastMessageText = message.text, lastMessageAt = message.createdAt) else it
        }
        // Try immediately; the outbox retries on failure.
        deliver(message)
        return message
    }

    suspend fun retryMessage(clientMessageId: String) {
        val conversationId = local.pendingOutbox().firstOrNull { it.clientMessageId == clientMessageId }?.conversationId
            ?: return
        val message = local.messages(conversationId).firstOrNull { it.clientMessageId == clientMessageId } ?: return
        local.markMessageState(clientMessageId, MessageState.PENDING)
        deliver(message)
    }

    private suspend fun deliver(message: Message) = outboxMutex.withLock {
        when (
            val result = api.sendMessage(
                conversationId = message.conversationId,
                clientMessageId = message.clientMessageId,
                text = message.text,
                speakNow = message.priority == MessagePriority.SPEAK_NOW,
            )
        ) {
            is AppResult.Ok -> {
                val selfId = currentUserId.orEmpty()
                local.upsertMessage(result.value.message.toDomain(message.conversationId, selfId))
                local.markMessageState(message.clientMessageId, MessageState.SENT)
                _lastMessageErrors.value = _lastMessageErrors.value - message.clientMessageId
            }
            is AppResult.Err -> {
                val retryable = result.error.retryable
                local.markMessageState(
                    message.clientMessageId,
                    if (retryable) MessageState.PENDING else MessageState.FAILED,
                    failure = result.error.message,
                )
                if (!retryable) {
                    _lastMessageErrors.value = _lastMessageErrors.value + (message.clientMessageId to result.error.message)
                }
            }
        }
        publishMessages(message.conversationId, local.messages(message.conversationId))
    }

    /** Retries pending messages with backoff; also flushes on reconnect. */
    private suspend fun runOutbox() {
        var delayMs = 2_000L
        while (true) {
            val pending = local.pendingOutbox()
            if (pending.isEmpty()) {
                delayMs = 2_000L
                delay(3_000)
                continue
            }
            pending.forEach { message -> deliver(message) }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(60_000L)
        }
    }

    // ------------------------------------------------------------------- contacts

    suspend fun addContact(email: String): AppError? = when (val result = api.addContact(email.trim())) {
        is AppResult.Ok -> {
            val contact = result.value.contact.toDomain()
            local.upsertContacts(listOf(contact))
            _contacts.value = (_contacts.value + contact).distinctBy { it.userId }
            refreshConversations()
            null
        }
        is AppResult.Err -> result.error
    }

    suspend fun setTrusted(contact: Contact, trusted: Boolean): AppError? {
        local.setContactTrust(contact.userId, trusted)
        local.setConversationTrust(contact.userId, trusted)
        _contacts.value = _contacts.value.map { if (it.userId == contact.userId) it.copy(isTrusted = trusted) else it }
        _conversations.value = _conversations.value.map {
            if (it.peerId == contact.userId) it.copy(peerTrusted = trusted) else it
        }
        return when (val result = api.updateContact(contact.id, isTrusted = trusted, displayName = null)) {
            is AppResult.Ok -> {
                val updated = result.value.contact.toDomain()
                local.upsertContacts(listOf(updated))
                null
            }
            is AppResult.Err -> result.error.also {
                AppLog.w("Repo", "trust_sync_failed", it.cause, "kind" to it.kind)
            }
        }
    }

    suspend fun deleteContact(contact: Contact): AppError? {
        local.deleteContact(contact.userId)
        _contacts.value = _contacts.value.filterNot { it.userId == contact.userId }
        return (api.deleteContact(contact.id) as? AppResult.Err)?.error
    }

    suspend fun openConversationWith(peerUserId: String): String? =
        when (val result = api.createConversation(peerUserId)) {
            is AppResult.Ok -> {
                val conversation = result.value.conversation.toDomain()
                local.upsertConversation(conversation)
                _conversations.value = (_conversations.value + conversation)
                    .distinctBy { it.id }
                    .sortedByDescending { it.lastMessageAt ?: 0L }
                conversation.id
            }
            is AppResult.Err -> null
        }

    // --------------------------------------------------------------- realtime in

    suspend fun onEvent(event: EventDto) {
        when (event.type) {
            EventTypes.MESSAGE_CREATED -> event.data?.let { data ->
                decode<MessageDto>(data)?.let { dto ->
                    val selfId = currentUserId.orEmpty()
                    val message = dto.toDomain(dto.conversationId, selfId)
                    ingest(message, senderName = null)
                }
            }
            EventTypes.MESSAGE_UPDATED -> event.data?.let { data ->
                decode<MessageDto>(data)?.let { dto ->
                    val selfId = currentUserId.orEmpty()
                    val message = dto.toDomain(dto.conversationId, selfId)
                    local.upsertMessage(message)
                    publishMessages(message.conversationId, local.messages(message.conversationId))
                }
            }
            EventTypes.MESSAGE_READ -> Unit // delivery ticks are optimistic locally
            EventTypes.CONVERSATION_UPDATED -> refreshConversations()
            EventTypes.CONTACT_UPDATED -> refreshContacts()
        }
    }

    /** Entry point shared by the socket and by cloud push. */
    suspend fun ingest(message: Message, senderName: String? = null, notify: Boolean = true) {
        local.upsertMessage(message)
        local.bumpUnread(message.conversationId)
        val conversation = local.conversation(message.conversationId)
        _conversations.value = (
            _conversations.value.filterNot { it.id == message.conversationId } +
                (conversation ?: return)
            ).sortedByDescending { it.lastMessageAt ?: 0L }
        publishMessages(message.conversationId, local.messages(message.conversationId))

        val name = senderName ?: conversation?.peerDisplayName ?: "Someone"
        val trusted = conversation?.peerTrusted ?: local.isTrustedSender(message.senderId)
        val outcome = engine.evaluate(
            message = message,
            senderName = name,
            senderTrusted = trusted,
            settings = settings.callAssist.value,
            callStatus = callStatusProvider(),
            emojiMode = settings.speech.value.emojiMode,
        )
        _speechDecision.value = outcome
        if (notify) onIncomingMessage(message, name)
        if (outcome.action == CallAssistEngine.Action.DROP && outcome.reason == "not_persisted") {
            AppLog.w("Repo", "speech_claim_missing_message", null, "id" to AppLog.fingerprint(message.clientMessageId))
        }
    }

    // ---------------------------------------------------------------- push bridge

    fun onPushPayload(payload: PushBridge.PushPayload) {
        val conversationId = payload.conversationId ?: return
        val selfId = currentUserId.orEmpty()
        val text = payload.text ?: return
        val message = Message(
            id = payload.messageId.orEmpty(),
            clientMessageId = payload.clientMessageId ?: payload.messageId ?: return,
            conversationId = conversationId,
            senderId = payload.senderId.orEmpty(),
            recipientId = selfId,
            text = text,
            createdAt = TimeParse.toEpochMillis(payload.createdAt),
            state = MessageState.SENT,
            deliveryState = ModelDeliveryState.NONE,
            priority = if (payload.priority.equals("SPEAK_NOW", true)) MessagePriority.SPEAK_NOW else MessagePriority.NORMAL,
            isMine = false,
        )
        // Fire-and-forget: this runs from a push service callback.
        _pendingPush.value = _pendingPush.value + (message to payload.senderName)
    }

    /** Pushes decoded from a cloud message, waiting to be processed by a scope. */
    private val _pendingPush = MutableStateFlow<List<Pair<Message, String?>>>(emptyList())
    val pendingPushMessages: StateFlow<List<Pair<Message, String?>>> = _pendingPush.asStateFlow()

    // ------------------------------------------------------------------- settings

    suspend fun syncSettingsFromServer(): AppError? {
        val result = api.settings()
        return when (result) {
            is AppResult.Ok -> {
                val dto = result.value.settings
                settings.updateCallAssist {
                    it.copy(
                        speakMessages = dto.speakMessages,
                        onlyDuringCalls = dto.onlyDuringCalls,
                        trustedContactsOnly = dto.trustedContactsOnly,
                        preferBluetooth = dto.preferBluetooth,
                    )
                }
                settings.updateSpeech {
                    it.copy(
                        languageTag = dto.languageTag,
                        speechRate = dto.speechRate,
                        pitch = dto.pitch,
                        emojiMode = runCatching {
                            com.airwhispers.data.model.EmojiMode.valueOf(dto.emojiMode)
                        }.getOrDefault(it.emojiMode),
                    )
                }
                null
            }
            is AppResult.Err -> result.error
        }
    }

    fun pushSettingsToServer(scope: CoroutineScope) {
        val callAssist = settings.callAssist.value
        val speech = settings.speech.value
        scope.launch(dispatchers.io) {
            api.updateSettings(
                UpdateSettingsRequest(
                    speakMessages = callAssist.speakMessages,
                    onlyDuringCalls = callAssist.onlyDuringCalls,
                    trustedContactsOnly = callAssist.trustedContactsOnly,
                    preferBluetooth = callAssist.preferBluetooth,
                    languageTag = speech.languageTag,
                    speechRate = speech.speechRate,
                    pitch = speech.pitch,
                    emojiMode = speech.emojiMode.name,
                ),
            )
        }
    }

    suspend fun registerDevice(): AppError? {
        val token = runCatching { PushBridge.handler?.token(context) }.getOrNull()
        return (api.registerDevice(token, BuildConfig.VERSION_NAME) as? AppResult.Err)?.error
    }

    // ------------------------------------------------------------------- internals

    private suspend fun enterSession(account: Account) {
        _session.value = SessionState(status = SessionStatus.SIGNED_IN, account = account)
        AppLog.i("Repo", "session_started", "user" to AppLog.fingerprint(account.id))
    }

    private fun publishMessages(conversationId: String, messages: List<Message>) {
        _messages.value = _messages.value + (conversationId to messages)
    }

    private inline fun <reified T> decode(data: kotlinx.serialization.json.JsonElement): T? =
        runCatching {
            json.decodeFromJsonElement(
                kotlinx.serialization.serializer<T>(),
                data as? JsonObject ?: return@runCatching null,
            )
        }.getOrNull()

    private suspend fun <T> withSession(fallback: T, block: suspend () -> T): T {
        _session.value = _session.value.copy(busy = true, error = null)
        val result = try {
            block()
        } finally {
            _session.value = _session.value.copy(busy = false)
        }
        return result
    }

    private companion object {
        val UNKNOWN_PLACEHOLDER = SessionState(status = SessionStatus.UNKNOWN)
    }
}
