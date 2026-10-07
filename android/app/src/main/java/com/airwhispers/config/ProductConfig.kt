package com.airwhispers.config

/**
 * Central branding / product configuration.
 *
 * Nothing else in the code base hard-codes the product name: swapping the brand
 * later (Whisper, EchoChat, Hush, …) is a change to this file plus res/values.
 */
object ProductConfig {
    const val APP_NAME: String = "AirWhispers"
    const val SUPPORT_EMAIL: String = "support@airwhispers.app"
    const val DEFAULT_API_VERSION: String = "v1"

    /** Feature flags — keep advanced behaviour opt-in while it matures. */
    const val WHISPER_MODE_ENABLED: Boolean = false
    const val VOICE_REPLY_ENABLED: Boolean = false
    const val SMART_TTS_ENABLED: Boolean = false

    /** Hard ceiling for a single spoken utterance (characters). Long messages are
     *  shortened intelligently instead of being read for a minute. */
    const val MAX_SPOKEN_CHARS: Int = 320

    /** Maximum number of messages kept in the speech queue. Oldest non-priority
     *  items are dropped beyond this to protect memory and battery. */
    const val MAX_QUEUE_DEPTH: Int = 20
}
