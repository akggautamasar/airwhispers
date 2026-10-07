package com.airwhispers.data.model

/** Pure Kotlin domain models — no Android types, so they are unit-testable. */

/**
 * This device's identity on a server.
 *
 * There is no account, no email and no password: the first time the app reaches a
 * server it is handed a random six-character [code], which is the only thing a
 * friend needs in order to whisper to this phone.
 */
data class Identity(
    val id: String,
    val code: String,
    val displayName: String,
    val createdAt: Long,
) {
    /** `k7m2pq` → `K7M 2PQ`: easier to read out loud. */
    val prettyCode: String
        get() = code.uppercase().replace(Regex("(.{3})(.{3})"), "$1 $2")
}

/** Someone this device can talk to, addressed by their code. */
data class Peer(
    val id: String,
    val code: String,
    val displayName: String,
    /** This device lets their messages be whispered during a call. */
    val allowsSpeak: Boolean,
    val conversationId: String?,
)

enum class MessageState { PENDING, SENT, FAILED }

enum class DeliveryState { NONE, DELIVERED, READ }

/** Priority is set by the *sender* (the "Whisper now" affordance). */
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
    /** The recipient's phone reported that it spoke this message. */
    val spokenByRecipient: Boolean = false,
    /** The recipient currently allows this sender to whisper to them. */
    val speakEligible: Boolean = false,
    /** Local failure reason, shown in the transcript bubble. */
    val failureReason: String? = null,
)

data class Conversation(
    val id: String,
    val peerId: String,
    val peerCode: String,
    val peerDisplayName: String,
    /** Live presence from the realtime socket (false when unknown/offline). */
    val peerOnline: Boolean = false,
    val lastMessageText: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    /** I let this peer's messages be spoken on this device. */
    val youAllowSpeak: Boolean,
    /** They let my messages be spoken on their device. */
    val peerAllowsSpeak: Boolean,
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
    /** Speak more quietly: meant for earbuds on a call, not for the room. */
    val whisperMode: Boolean = true,
)

data class CallAssistSettings(
    val enabled: Boolean = false,
    val speakMessages: Boolean = false,
    val onlyDuringCalls: Boolean = true,
    /** When on, only people the user explicitly allowed may be spoken. */
    val trustedContactsOnly: Boolean = true,
    val preferBluetooth: Boolean = true,
    val speakOwnMessages: Boolean = false,
    val notifyWhileSpeaking: Boolean = false,
)
