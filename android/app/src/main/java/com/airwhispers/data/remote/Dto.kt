package com.airwhispers.data.remote

import com.airwhispers.data.model.Account
import com.airwhispers.data.model.Contact
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.DeliveryState
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.data.model.MessageState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract shared with the backend (`backend/src/schemas.ts`).
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
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresIn: Long = 900L,
    @SerialName("tokenType") val tokenType: String = "Bearer",
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("displayName") val displayName: String = "",
    val handle: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
) {
    fun toAccount(): Account = Account(
        id = id,
        email = email,
        displayName = displayName.ifBlank { email.substringBefore('@') },
        handle = handle,
        createdAt = TimeParse.toEpochMillis(createdAt),
    )

    fun toContact(isTrusted: Boolean, conversationId: String?): Contact = Contact(
        id = id,
        userId = id,
        displayName = displayName.ifBlank { email.substringBefore('@') },
        email = email,
        isTrusted = isTrusted,
        conversationId = conversationId,
    )
}

@Serializable
data class AuthResponse(
    val user: UserDto,
    val tokens: TokensDto,
)

@Serializable
data class MeResponse(val user: UserDto)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("deviceId") val deviceId: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("deviceId") val deviceId: String? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refreshToken") val refreshToken: String)

@Serializable
data class UpdateProfileRequest(@SerialName("displayName") val displayName: String)

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
    @SerialName("speakEligible") val speakEligible: Boolean = true,
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
    )
}

@Serializable
data class ConversationDto(
    val id: String,
    @SerialName("peer") val peer: UserDto,
    @SerialName("lastMessage") val lastMessage: MessageDto? = null,
    @SerialName("unreadCount") val unreadCount: Int = 0,
    @SerialName("peerTrusted") val peerTrusted: Boolean = false,
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        peerId = peer.id,
        peerDisplayName = peer.displayName.ifBlank { peer.email.substringBefore('@') },
        peerEmail = peer.email,
        lastMessageText = lastMessage?.text,
        lastMessageAt = lastMessage?.createdAt?.let { TimeParse.toEpochMillis(it) },
        unreadCount = unreadCount,
        peerTrusted = peerTrusted,
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
data class CreateConversationRequest(@SerialName("peerUserId") val peerUserId: String)

@Serializable
data class SendMessageRequest(
    @SerialName("clientMessageId") val clientMessageId: String,
    val text: String,
    val priority: String = "NORMAL",
)

@Serializable
data class ContactDto(
    val id: String,
    @SerialName("user") val user: UserDto,
    @SerialName("isTrusted") val isTrusted: Boolean = false,
    @SerialName("conversationId") val conversationId: String? = null,
) {
    fun toDomain(): Contact = user.toContact(isTrusted = isTrusted, conversationId = conversationId)
}

@Serializable
data class ContactListResponse(val contacts: List<ContactDto> = emptyList())

@Serializable
data class ContactResponse(val contact: ContactDto)

@Serializable
data class AddContactRequest(val email: String)

@Serializable
data class UpdateContactRequest(
    @SerialName("isTrusted") val isTrusted: Boolean? = null,
    @SerialName("displayName") val displayName: String? = null,
)

@Serializable
data class SettingsDto(
    @SerialName("speakMessages") val speakMessages: Boolean = false,
    @SerialName("onlyDuringCalls") val onlyDuringCalls: Boolean = true,
    @SerialName("trustedContactsOnly") val trustedContactsOnly: Boolean = true,
    @SerialName("preferBluetooth") val preferBluetooth: Boolean = true,
    @SerialName("languageTag") val languageTag: String = "en-IN",
    @SerialName("speechRate") val speechRate: Float = 1.0f,
    @SerialName("pitch") val pitch: Float = 1.0f,
    @SerialName("emojiMode") val emojiMode: String = "DESCRIBE_IMPORTANT",
)

@Serializable
data class SettingsResponse(val settings: SettingsDto)

@Serializable
data class UpdateSettingsRequest(
    @SerialName("speakMessages") val speakMessages: Boolean? = null,
    @SerialName("onlyDuringCalls") val onlyDuringCalls: Boolean? = null,
    @SerialName("trustedContactsOnly") val trustedContactsOnly: Boolean? = null,
    @SerialName("preferBluetooth") val preferBluetooth: Boolean? = null,
    @SerialName("languageTag") val languageTag: String? = null,
    @SerialName("speechRate") val speechRate: Float? = null,
    @SerialName("pitch") val pitch: Float? = null,
    @SerialName("emojiMode") val emojiMode: String? = null,
)

@Serializable
data class DeviceRequest(
    @SerialName("deviceId") val deviceId: String,
    val platform: String = "android",
    @SerialName("pushToken") val pushToken: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
)

@Serializable
data class MarkReadResponse(val ok: Boolean = true)

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

object EventTypes {
    const val MESSAGE_CREATED = "message.created"
    const val MESSAGE_UPDATED = "message.updated"
    const val MESSAGE_READ = "message.read"
    const val CONVERSATION_UPDATED = "conversation.updated"
    const val CONTACT_UPDATED = "contact.updated"
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
