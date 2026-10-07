package com.airwhispers.data.remote

import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Identity
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import com.airwhispers.data.model.Peer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract shared with the backend (`backend/src/app.ts`).
 *
 * Every field except the identifiers has a default so that adding fields on the
 * server never breaks an installed client.
 */

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(
    val code: String = "internal_error",
    val message: String = "Unexpected error",
    @SerialName("retryAfterMs") val retryAfterMs: Long? = null,
)

@Serializable
data class TokensDto(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("expiresIn") val expiresIn: Long = 3600L,
    @SerialName("tokenType") val tokenType: String = "Bearer",
)

@Serializable
data class UserDto(
    val id: String,
    val code: String = "",
    @SerialName("displayName") val displayName: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
    /** Only present on conversation peers. */
    val online: Boolean = false,
) {
    fun toIdentity(): Identity = Identity(
        id = id,
        code = code,
        displayName = displayName.ifBlank { "This phone" },
        createdAt = TimeParse.toEpochMillis(createdAt),
    )

    fun toPeer(allowsSpeak: Boolean, conversationId: String?): Peer = Peer(
        id = id,
        code = code,
        displayName = displayName.ifBlank { "Someone" },
        allowsSpeak = allowsSpeak,
        conversationId = conversationId,
    )
}

/** Response of both /device/register and /device/token. */
@Serializable
data class DeviceResponse(
    val user: UserDto,
    val tokens: TokensDto,
    /** Only ever returned once, when the identity is first created. */
    @SerialName("deviceSecret") val deviceSecret: String? = null,
)

@Serializable
data class DeviceRegisterRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceSecret") val deviceSecret: String? = null,
    @SerialName("displayName") val displayName: String? = null,
    val platform: String = "android",
    @SerialName("appVersion") val appVersion: String? = null,
)

@Serializable
data class DeviceSecretRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceSecret") val deviceSecret: String,
)

@Serializable
data class MeResponse(val user: UserDto)

@Serializable
data class UpdateProfileRequest(@SerialName("displayName") val displayName: String)

@Serializable
data class ServerInfoDto(
    val name: String = "",
    val version: String = "",
    val apiVersion: String = "v1",
    @SerialName("realtimePath") val realtimePath: String = "/api/v1/realtime",
    @SerialName("registrationOpen") val registrationOpen: Boolean = true,
    /** "device" — no accounts on this server. */
    val registration: String = "device",
    @SerialName("codeLength") val codeLength: Int = 6,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("clientMessageId") val clientMessageId: String? = null,
    @SerialName("conversationId") val conversationId: String,
    @SerialName("senderId") val senderId: String,
    @SerialName("recipientId") val recipientId: String,
    val text: String,
    @SerialName("createdAt") val createdAt: String? = null,
    val priority: String = "NORMAL",
    @SerialName("deliveryStatus") val deliveryStatus: String = "sent",
    @SerialName("readStatus") val readStatus: String = "unread",
    /** The recipient's phone actually spoke it. */
    val spoken: Boolean = false,
    /** The recipient allows this sender to whisper to them. */
    @SerialName("speakEligible") val speakEligible: Boolean = false,
) {
    fun toDomain(conversationIdFallback: String, selfId: String): Message = Message(
        id = id,
        clientMessageId = clientMessageId ?: id,
        conversationId = conversationId.ifBlank { conversationIdFallback },
        senderId = senderId,
        recipientId = recipientId,
        text = text,
        createdAt = TimeParse.toEpochMillis(createdAt),
        state = MessageState.SENT,
        deliveryState = when (readStatus.lowercase()) {
            "read" -> DeliveryState.READ
            "delivered" -> DeliveryState.DELIVERED
            else -> DeliveryState.NONE
        },
        priority = if (priority.equals("SPEAK_NOW", true)) MessagePriority.SPEAK_NOW else MessagePriority.NORMAL,
        isMine = senderId == selfId,
        spokenByRecipient = spoken,
        speakEligible = speakEligible,
    )
}

@Serializable
data class ConversationDto(
    val id: String,
    @SerialName("peer") val peer: UserDto,
    @SerialName("lastMessage") val lastMessage: MessageDto? = null,
    @SerialName("unreadCount") val unreadCount: Int = 0,
    /** May this peer's messages be spoken on this device? */
    @SerialName("youAllowSpeak") val youAllowSpeak: Boolean = false,
    /** May this device's messages be spoken on theirs? */
    @SerialName("peerAllowsSpeak") val peerAllowsSpeak: Boolean = false,
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        peerId = peer.id,
        peerCode = peer.code,
        peerDisplayName = peer.displayName.ifBlank { "Someone" },
        peerOnline = peer.online,
        lastMessageText = lastMessage?.text,
        lastMessageAt = lastMessage?.createdAt?.let { TimeParse.toEpochMillis(it) },
        unreadCount = unreadCount,
        youAllowSpeak = youAllowSpeak,
        peerAllowsSpeak = peerAllowsSpeak,
    )
}

@Serializable
data class ConversationListResponse(val conversations: List<ConversationDto> = emptyList())

@Serializable
data class ConversationResponse(val conversation: ConversationDto)

@Serializable
data class MessageListResponse(val messages: List<MessageDto> = emptyList())

@Serializable
data class MessageResponse(val message: MessageDto)

@Serializable
data class CreateConversationRequest(val code: String)

@Serializable
data class TrustRequest(val trusted: Boolean)

@Serializable
data class SendMessageRequest(
    @SerialName("clientMessageId") val clientMessageId: String,
    val text: String,
    val priority: String = "NORMAL",
)

@Serializable
data class DeviceRequest(
    @SerialName("deviceId") val deviceId: String,
    val platform: String = "android",
    @SerialName("pushToken") val pushToken: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String = "unknown",
    val version: String = "",
    val time: String = "",
)

/**
 * A single realtime frame. `type` is the discriminator; `data` is decoded by the
 * consumer with the concrete serializer, which keeps the event schema stable and
 * forward compatible.
 */
@Serializable
data class EventDto(
    val type: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("eventId") val eventId: String? = null,
    @SerialName("sentAt") val sentAt: String? = null,
)

/** `presence.updated` — a chat partner came online or went offline. */
@Serializable
data class PresenceDto(
    @SerialName("userId") val userId: String,
    val online: Boolean = false,
)

/** `typing` — the other side is writing right now. */
@Serializable
data class TypingDto(
    @SerialName("conversationId") val conversationId: String,
    @SerialName("userId") val userId: String = "",
)

/** `peer.updated` — the other side renamed themselves or changed consent. */
@Serializable
data class PeerUpdatedDto(
    @SerialName("userId") val userId: String,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("allowsSpeak") val allowsSpeak: Boolean? = null,
)

object EventTypes {
    const val MESSAGE_CREATED = "message.created"
    const val MESSAGE_UPDATED = "message.updated"
    const val MESSAGE_READ = "message.read"
    const val CONVERSATION_UPDATED = "conversation.updated"
    const val PEER_UPDATED = "peer.updated"
    const val PRESENCE_UPDATED = "presence.updated"
    const val TYPING = "typing"
    const val AUTH_REQUIRED = "auth.required"
    const val AUTH_OK = "auth.ok"
    const val PONG = "pong"
    const val ERROR = "error"
}

/** ISO-8601 (server) ⇄ epoch millis (client). */
object TimeParse {
    private val isoRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?(Z|[+-]\d{2}:\d{2})?$""")

    fun toEpochMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return System.currentTimeMillis()
        iso.toLongOrNull()?.let { return it }
        val match = isoRegex.matchEntire(iso) ?: return System.currentTimeMillis()
        val (y, mo, d, h, mi, s, frac, zone) = match.destructured
        val millis = frac.padEnd(3, '0').toLongOrNull() ?: 0L
        val offsetMinutes = when {
            zone.isBlank() || zone == "Z" -> 0
            else -> {
                val sign = if (zone.startsWith("-")) -1 else 1
                val (zh, zm) = zone.substring(1).split(":")
                sign * (zh.toInt() * 60 + zm.toInt())
            }
        }
        val localUtc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        }.timeInMillis + millis
        return localUtc - offsetMinutes * 60_000L
    }

    fun toIso(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMillis))
    }
}
