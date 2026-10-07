package com.airwhispers.domain.assist

import com.airwhispers.core.AppLog
import com.airwhispers.core.TimeSource
import com.airwhispers.data.model.CallAssistSettings
import com.airwhispers.data.model.CallStatus
import com.airwhispers.data.model.EmojiMode
import com.airwhispers.data.model.Message
import com.airwhispers.data.model.MessagePriority
import com.airwhispers.domain.tts.SpeechItem
import com.airwhispers.domain.tts.TextNormalizer
import com.airwhispers.domain.tts.TtsQueue

/**
 * The decision pipeline from the product spec, in order:
 *
 * ```
 * incoming message
 *   → Call Assist enabled?
 *   → user actually in a call?
 *   → sender allowed?
 *   → anything worth saying?
 *   → normalise text
 *   → claim (idempotent!)
 *   → enqueue for sequential speech
 * ```
 *
 * Everything questionable resolves to "notify, don't speak" — the app must never
 * surprise the user by talking.
 */
class CallAssistEngine(
    private val ledger: SpokenLedger,
    private val queue: TtsQueue,
    private val time: TimeSource = TimeSource.SYSTEM,
) {

    enum class Action { SPEAK, NOTIFY_ONLY, DROP }

    data class Outcome(
        val action: Action,
        val reason: String,
        val item: SpeechItem? = null,
        val spokenText: String? = null,
    )

    /** Sender announced only when it changes, so bursts do not repeat the name. */
    @Volatile
    private var lastAnnouncedSender: String? = null

    suspend fun evaluate(
        message: Message,
        senderName: String,
        senderTrusted: Boolean,
        settings: CallAssistSettings,
        callStatus: CallStatus,
        emojiMode: EmojiMode = EmojiMode.DESCRIBE_IMPORTANT,
    ): Outcome {
        if (message.isMine && !settings.speakOwnMessages) {
            return Outcome(Action.DROP, "own_message")
        }
        if (!settings.enabled || !settings.speakMessages) {
            return Outcome(Action.NOTIFY_ONLY, "call_assist_off")
        }
        if (settings.onlyDuringCalls && !callStatus.inProgress) {
            return Outcome(Action.NOTIFY_ONLY, "not_in_call")
        }
        if (settings.trustedContactsOnly && !senderTrusted) {
            return Outcome(Action.NOTIFY_ONLY, "sender_not_trusted")
        }

        val normalized = TextNormalizer.normalize(message.text, emojiMode)
        if (!TextNormalizer.isSpeakable(normalized.text)) {
            AppLog.d("CallAssist", "not_speakable", "id" to AppLog.fingerprint(message.clientMessageId))
            return Outcome(Action.NOTIFY_ONLY, "nothing_to_speak")
        }

        val priority = message.priority
        if (!queue.canAccept(priority)) {
            return Outcome(Action.NOTIFY_ONLY, "queue_full")
        }

        // Durable idempotency: exactly one caller ever wins this claim.
        when (ledger.claimForSpeech(message.clientMessageId, time.nowMillis())) {
            SpokenLedger.Claim.ALREADY_SPOKEN -> {
                AppLog.d("CallAssist", "duplicate_suppressed", "id" to AppLog.fingerprint(message.clientMessageId))
                return Outcome(Action.DROP, "already_spoken")
            }
            SpokenLedger.Claim.UNKNOWN_MESSAGE -> {
                // Programming error: callers must persist before evaluating.
                AppLog.e("CallAssist", "claim_without_message", null, "id" to AppLog.fingerprint(message.clientMessageId))
                return Outcome(Action.DROP, "not_persisted")
            }
            SpokenLedger.Claim.GRANTED -> Unit
        }

        val announce = priority == MessagePriority.SPEAK_NOW || senderName != lastAnnouncedSender
        val item = SpeechItem(
            id = message.clientMessageId,
            messageId = message.id,
            conversationId = message.conversationId,
            senderName = senderName,
            body = normalized.text,
            priority = priority,
            createdAt = message.createdAt,
            announceSender = announce,
        )
        if (!queue.enqueue(item)) {
            // Lost an in-memory race (rare): release the claim so a later attempt
            // can still speak it rather than losing the message entirely.
            ledger.releaseSpeechClaim(message.clientMessageId)
            return Outcome(Action.DROP, "duplicate_in_queue")
        }
        lastAnnouncedSender = senderName
        AppLog.i(
            "CallAssist",
            "queued_for_speech",
            "id" to AppLog.fingerprint(message.clientMessageId),
            "priority" to priority,
            "chars" to normalized.text.length,
            "emojiSpoken" to normalized.describedEmoji,
            "depth" to queue.state.value.depth,
        )
        return Outcome(Action.SPEAK, "queued", item, normalized.text)
    }

    /** Called when Call Assist is switched off or the user signs out. */
    fun resetAnnouncementState() {
        lastAnnouncedSender = null
    }
}
