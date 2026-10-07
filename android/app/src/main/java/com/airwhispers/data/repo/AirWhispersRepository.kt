package com.airwhispers.data.repo

import android.content.Context
import com.airwhispers.core.AppError
import com.airwhispers.core.AppLog
import com.airwhispers.core.AppResult
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.core.TimeSource
import com.airwhispers.data.local.LocalStore
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState as ModelDeliveryState
import com.airwhispers.data.model.Identity
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.data.model.Peer
import com.airwhispers.data.remote.ApiClient
import com.airwhispers.data.remote.ConversationDto
import com.airwhispers.data.remote.EventDto
import com.airwhispers.data.remote.EventTypes
import com.airwhispers.data.remote.MessageDto
import com.airwhispers.data.remote.PeerUpdatedDto
import com.airwhispers.data.remote.PresenceDto
import com.airwhispers.data.remote.RealtimeClient
import com.airwhispers.data.remote.TimeParse
import com.airwhispers.data.remote.TypingDto
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

/**
 * Whether this device has an identity yet.
 *
 * There is no third state: either the server has handed out a code (READY) or the
 * app is still asking for one (SETUP).
 */
enum class IdentityStatus { SETUP, READY }

data class IdentityState(
    val status: IdentityStatus = IdentityStatus.SETUP,
    val me: Identity? = null,
    val error: AppError? = null,
    val busy: Boolean = false,
)

/** Someone in a chat is typing right now (ephemeral, never persisted). */
data class TypingSignal(val conversationId: String, val userId: String, val at: Long)

/**
 * Single entry point for the app's data needs.
 *
 * Owns: the device identity (register / resume / rotate), the local cache, the
 * realtime event pipeline, the outbox (send/retry), and the hand-off into Call
 * Assist. The UI talks to this class only, which keeps Compose code free of
 * networking concerns.
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

    private val _session = MutableStateFlow(IdentityState())
    val session: StateFlow<IdentityState> = _session.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _lastMessageErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastMessageErrors: StateFlow<Map<String, String>> = _lastMessageErrors.asStateFlow()

    private val _speechDecision = MutableStateFlow<CallAssistEngine.Outcome?>(null)
    val lastSpeechDecision: StateFlow<CallAssistEngine.Outcome?> = _speechDecision.asStateFlow()

    private val _typing = MutableStateFlow<TypingSignal?>(null)
    val typing: StateFlow<TypingSignal?> = _typing.asStateFlow()

    private val outboxMutex = Mutex()
    private var outboxJob: Job? = null
    private var eventJob: Job? = null
    private var typingJob: Job? = null
    private var scope: CoroutineScope? = null

    val currentUserId: String? get() = secrets.userId()
    val deviceId: String get() = settings.deviceId

    /** The code on this device, if the server has ever given us one. */
    val myCode: String? get() = _session.value.me?.code ?: settings.myCode

    // ------------------------------------------------------------------ identity

    private var registerAttempted = false

    /** Called by the UI on first composition; runs at most once per process. */
    suspend fun restoreIfNeeded(displayName: String? = null) {
        if (registerAttempted) return
        registerAttempted = true
        ensureRegistered(displayName)
    }

    /**
     * Makes sure this device has an identity.
     *
     * First launch: the server generates a code. Later launches: the stored device
     * secret is exchanged for a fresh token — the user is never asked for anything.
     */
    suspend fun ensureRegistered(displayName: String? = null): IdentityState = withSession {
        when (val result = api.registerDevice(displayName)) {
            is AppResult.Ok -> enterIdentity(result.value.user.toIdentity())
            is AppResult.Err -> {
                // Offline start with a cached code: show it, keep trying in the background.
                val cachedCode = settings.myCode
                val cachedId = secrets.userId()
                if (cachedCode != null && cachedId != null) {
                    _session.value = IdentityState(
                        status = IdentityStatus.READY,
                        me = Identity(cachedId, cachedCode, settings.myDisplayName ?: "This phone", time.nowMillis()),
                        error = result.error,
                    )
                } else {
                    _session.value = IdentityState(status = IdentityStatus.SETUP, error = result.error)
                }
            }
        }
        _session.value
    }

    suspend fun setDisplayName(name: String): AppError? = when (val result = api.updateProfile(name.trim())) {
        is AppResult.Ok -> {
            val identity = result.value.user.toIdentity()
            _session.value = _session.value.copy(me = identity)
            settings.rememberIdentity(identity.code, identity.displayName)
            null
        }
        is AppResult.Err -> result.error
    }

    /**
     * "Give me a new code": forgets this identity everywhere and registers afresh.
     * The old chats are left behind with the old code, by design.
     */
    suspend fun regenerateIdentity(): IdentityState {
        stopPipeline()
        api.forgetDevice()
        secrets.clearSession()
        local.purgeAll()
        queue.reset()
        engine.resetAnnouncementState()
        settings.forgetIdentity()
        _conversations.value = emptyList()
        _messages.value = emptyMap()
        registerAttempted = true
        return ensureRegistered()
    }

    /** Kicks off initial loading, the realtime subscription and the outbox. */
    fun startPipeline(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        if (eventJob?.isActive == true) return
        eventJob = coroutineScope.launch {
            realtime.events.collect { event -> onEvent(event) }
        }
        outboxJob = coroutineScope.launch(dispatchers.io) { runOutbox() }
        coroutineScope.launch(dispatchers.io) {
            refreshConversations()
            registerForPush()
        }
    }

    fun stopPipeline() {
        eventJob?.cancel()
        outboxJob?.cancel()
        typingJob?.cancel()
        eventJob = null
        outboxJob = null
        typingJob = null
        _typing.value = null
    }

    // ------------------------------------------------------------------- loading

    suspend fun refreshConversations(): AppResult<List<Conversation>> {
        val result = api.conversations()
        return when (result) {
            is AppResult.Ok -> {
                val domain = result.value.conversations.map(ConversationDto::toDomain)
                local.upsertConversations(domain)
                // Keep the offline trust fallback in step with the server.
                local.upsertPeers(domain.map { it.toPeer() })
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

    private fun Conversation.toPeer(): Peer = Peer(
        id = peerId,
        code = peerCode,
        displayName = peerDisplayName,
        allowsSpeak = youAllowSpeak,
        conversationId = id,
    )

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
            speakEligible = conversation?.peerAllowsSpeak ?: false,
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

    // ---------------------------------------------------------------------- chats

    /** "New chat": the only address this product has is a friend's code. */
    suspend fun openChatWithCode(code: String): Pair<String?, AppError?> =
        when (val result = api.createConversation(code)) {
            is AppResult.Ok -> {
                val conversation = result.value.conversation.toDomain()
                local.upsertConversation(conversation)
                local.upsertPeers(listOf(conversation.toPeer()))
                _conversations.value = (_conversations.value.filterNot { it.id == conversation.id } + conversation)
                    .sortedByDescending { it.lastMessageAt ?: 0L }
                conversation.id to null
            }
            is AppResult.Err -> null to result.error
        }

    /** Speech consent for one peer: may their messages be spoken on this device? */
    suspend fun setSpeakAllowed(conversation: Conversation, allowed: Boolean): AppError? {
        local.setSpeakPermission(conversation.peerId, allowed)
        local.setPeerAllowsSpeakPermission(conversation.peerId, allowed)
        _conversations.value = _conversations.value.map {
            if (it.id == conversation.id) it.copy(youAllowSpeak = allowed) else it
        }
        return when (val result = api.setTrust(conversation.id, allowed)) {
            is AppResult.Ok -> {
                val updated = result.value.conversation.toDomain()
                local.upsertConversation(updated)
                null
            }
            is AppResult.Err -> result.error.also {
                // The server keeps the truth; a failed sync is retried on refresh.
                AppLog.w("Repo", "trust_sync_failed", it.cause, "kind" to it.kind)
            }
        }
    }

    /** Tell the other side we are writing. Fire-and-forget, heavily rate limited. */
    fun sendTyping(conversationId: String) {
        realtime.sendTyping(conversationId)
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
            EventTypes.MESSAGE_READ -> {
                // Delivery ticks are optimistic locally; refresh to be sure.
                refreshConversations()
            }
            EventTypes.CONVERSATION_UPDATED -> refreshConversations()
            EventTypes.PEER_UPDATED -> event.data?.let { data ->
                val update = decode<PeerUpdatedDto>(data)
                if (update != null && update.userId == currentUserId) {
                    // The other side changed how they treat us.
                    if (update.allowsSpeak != null) {
                        _conversations.value = _conversations.value.map {
                            if (it.peerId == update.userId) it.copy(peerAllowsSpeak = update.allowsSpeak) else it
                        }
                    }
                }
                refreshConversations()
            }
            EventTypes.PRESENCE_UPDATED -> event.data?.let { data ->
                val presence = decode<PresenceDto>(data) ?: return@let
                _conversations.value = _conversations.value.map {
                    if (it.peerId == presence.userId) it.copy(peerOnline = presence.online) else it
                }
            }
            EventTypes.TYPING -> event.data?.let { data ->
                val typing = decode<TypingDto>(data) ?: return@let
                showTyping(typing)
            }
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
        // Consent local to *this* device decides whether a message may be spoken;
        // the sender's "whisper now" can never override it.
        val senderAllowed = conversation?.youAllowSpeak ?: local.allowsSpeaker(message.senderId)
        val outcome = engine.evaluate(
            message = message,
            senderName = name,
            senderTrusted = senderAllowed,
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

    private fun showTyping(typing: TypingDto) {
        val signal = TypingSignal(typing.conversationId, typing.userId, time.nowMillis())
        _typing.value = signal
        val activeScope = scope ?: return
        typingJob?.cancel()
        typingJob = activeScope.launch {
            delay(TYPING_LIFETIME_MS)
            if (_typing.value == signal) _typing.value = null
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

    // ------------------------------------------------------------------- devices

    suspend fun registerForPush(): AppError? {
        val token = runCatching { PushBridge.handler?.token(context) }.getOrNull()
        if (token == null && PushBridge.handler == null) return null
        return (api.registerPushToken(token) as? AppResult.Err)?.error
    }

    // ------------------------------------------------------------------- internals

    private suspend fun enterIdentity(identity: Identity) {
        settings.rememberIdentity(identity.code, identity.displayName)
        _session.value = IdentityState(status = IdentityStatus.READY, me = identity)
        AppLog.i("Repo", "identity_ready")
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

    private suspend fun <T> withSession(block: suspend () -> T): T {
        _session.value = _session.value.copy(busy = true, error = null)
        val result = try {
            block()
        } finally {
            _session.value = _session.value.copy(busy = false)
        }
        return result
    }

    private companion object {
        /** How long "typing…" stays on screen after the last keystroke signal. */
        const val TYPING_LIFETIME_MS = 4_000L
    }
}
