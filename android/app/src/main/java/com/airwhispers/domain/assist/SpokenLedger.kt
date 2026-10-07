package com.airwhispers.domain.assist

/**
 * Durable record of which messages have already been spoken aloud.
 *
 * This is the anti-duplication contract of the whole speech pipeline: a
 * `clientMessageId` may be spoken at most once, no matter how many times the
 * message reaches the device (push redelivery, socket replay, retry, restart).
 *
 * Implemented by the SQLite store on device; a fake is used in tests.
 */
interface SpokenLedger {

    enum class Claim { GRANTED, ALREADY_SPOKEN, UNKNOWN_MESSAGE }

    /**
     * Atomically claims the right to speak a message.
     *
     * @return [Claim.GRANTED] exactly once per message id.
     */
    suspend fun claimForSpeech(clientMessageId: String, now: Long): Claim

    /** Gives the claim back when queuing failed after the claim was taken. */
    suspend fun releaseSpeechClaim(clientMessageId: String)

    /** Diagnostics/UI helper. */
    suspend fun wasSpoken(clientMessageId: String): Boolean
}
