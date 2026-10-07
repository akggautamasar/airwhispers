package com.airwhispers.data.model

/** Pure Kotlin domain models — no Android types, so they are unit-testable. */

data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val handle: String?,
    val createdAt: Long,
)

data class Contact(
    val id: String,
    val userId: String,
    val displayName: String,
    val email: String,
    /** Allowed to trigger automatic speech on this device. */
    val isTrusted: Boolean,
    val conversationId: String?,
)

enum class MessageState { PENDING, SENT, FAILED }

enum class DeliveryState { NONE, DELIVERED, READ }

/** Priority is set by the *sender* (the "Speak Now" affordance). */
enum class MessagePriority { NORMAL, SPEAK_NOW }

data class Message(
    /** Server id. Empty while the message only exists locally. */
    val id: String,
    /** Client generated id — the idempotency key for the whole pipeline. */
    val clientMessageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val text: String,
    val createdAt: Long,
    val state: MessageState,
    val deliveryState: DeliveryState,
    val priority: MessagePriority,
    val isMine: Boolean,
    /** Wall-clock time this device finished speaking the message, if it did. */
    val spokenAt: Long? = null,
    /** Local failure reason, shown in the transcript bubble. */
    val failureReason: String? = null,
)

data class Conversation(
    val id: String,
    val peerId: String,
    val peerDisplayName: String,
    val peerEmail: String,
    val lastMessageText: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val peerTrusted: Boolean,
)

/** What the phone is doing right now. */
enum class CallState { IDLE, RINGING, IN_CALL, IN_COMMUNICATION }

/**
 * How confident we are about [CallState].
 *
 *  - [TELEPHONY]  : read from the platform telephony callback (very reliable).
 *  - [AUDIO_MODE] : the system audio mode / microphone-in-use indicates a VoIP
 *                   or video session started by another app. Reliable in
 *                   practice for WhatsApp/Meet/Signal/Discord style apps, but
 *                   it is a heuristic: OEM behaviour can differ.
 *  - [MANUAL]     : the user toggled Call Assist themselves.
 *  - [NONE]       : nothing detected.
 */
enum class CallDetectionSource { NONE, TELEPHONY, AUDIO_MODE, MICROPHONE_IN_USE, MANUAL }

data class CallStatus(
    val state: CallState = CallState.IDLE,
    val source: CallDetectionSource = CallDetectionSource.NONE,
    val changedAt: Long = 0L,
) {
    val inProgress: Boolean
        get() = state == CallState.IN_CALL || state == CallState.IN_COMMUNICATION
}

/**
 * What the app can actually do on this device. Surfaced in the UI so the
 * product never promises more than the platform allows.
 */
data class CallDetectionCapability(
    val telephonyReadable: Boolean,
    val audioModeReadable: Boolean,
    val microphoneUsageReadable: Boolean,
    val bluetoothRoutingInspectable: Boolean,
    val notes: List<String> = emptyList(),
) {
    /** True when at least one *automatic* signal is available. */
    val automaticDetectionAvailable: Boolean
        get() = telephonyReadable || audioModeReadable || microphoneUsageReadable
}

enum class SpeechOutput { SYSTEM_DEFAULT, PREFER_BLUETOOTH, PHONE_SPEAKER }

enum class EmojiMode { IGNORE, DESCRIBE_IMPORTANT, READ_ALL }

data class SpeechSettings(
    val languageTag: String = "en-IN",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceName: String? = null,
    val output: SpeechOutput = SpeechOutput.SYSTEM_DEFAULT,
    val emojiMode: EmojiMode = EmojiMode.DESCRIBE_IMPORTANT,
    val pauseBetweenMessagesMs: Long = 700L,
    val whisperMode: Boolean = false,
)

data class CallAssistSettings(
    val enabled: Boolean = false,
    val speakMessages: Boolean = false,
    val onlyDuringCalls: Boolean = true,
    val trustedContactsOnly: Boolean = true,
    val preferBluetooth: Boolean = true,
    val speakOwnMessages: Boolean = false,
    val notifyWhileSpeaking: Boolean = false,
)
