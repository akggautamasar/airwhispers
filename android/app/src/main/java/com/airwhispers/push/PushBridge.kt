package com.airwhispers.push

import android.content.Context

/**
 * Boundary between the app core and an *optional* cloud push provider.
 *
 * The `standalone` flavor has no provider at all: messages arrive over the
 * realtime socket while Call Assist is armed. The `fcm` flavor registers a
 * handler here at startup, which lets AirWhispers speak a message even when it
 * was killed — the cloud push wakes the app, and (Android permitting) a
 * high-priority push may start the Call Assist foreground service.
 *
 * Keeping this as a tiny bridge means the core never depends on Firebase.
 */
object PushBridge {

    /** Set by a flavor that has a push provider wired up. */
    @Volatile
    var handler: PushHandler? = null

    /** Serializable payload handed to the core: ids, tiny metadata, no secrets. */
    data class PushPayload(
        val conversationId: String?,
        val messageId: String?,
        val clientMessageId: String?,
        val senderId: String?,
        val senderName: String?,
        val text: String?,
        val priority: String?,
        val createdAt: String?,
    )

    interface PushHandler {
        /** True when a push provider (e.g. Firebase) is present and initialised. */
        fun isConfigured(context: Context): Boolean

        /** Current registration token, if any, so the backend can target devices. */
        suspend fun token(context: Context): String?

        /** Called from the provider's background service. Must be fast. */
        fun onPush(context: Context, payload: PushPayload)
    }

    val isAvailable: Boolean get() = handler != null
}
