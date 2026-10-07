package com.airwhispers.push

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.airwhispers.AppContainer
import com.airwhispers.core.AppLog
import com.airwhispers.service.CallAssistController
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging transport (only in the `fcm` product flavor).
 *
 * Why it exists: the standalone flavor receives messages only while Call Assist
 * is armed and its socket is open. A high-priority cloud push can wake the app
 * even after it was killed, which is what makes "answer a WhatsApp call, receive a
 * message, hear it" work without pre-arming anything.
 *
 * Enable it by dropping `google-services.json` into `android/app/` and building
 * the `fcm` flavor; the standalone flavor never links Firebase at all.
 */
class AirWhispersMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppLog.i("Fcm", "token_refreshed", "chars" to token.length)
        val container = AppContainer.peekOrNull() ?: return
        container.appScope.launch { runCatching { container.repository.registerDevice() } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val container = AppContainer.peekOrNull()
        if (container == null) {
            AppLog.w("Fcm", "message_without_container")
            return
        }
        AppLog.i("Fcm", "message_received", "type" to data["type"], "highPriority" to message.priority)

        if (data["text"].isNullOrBlank()) {
            // Content-free push: fetch the message over the authenticated API.
            container.appScope.launch { fetchAndIngest(container, data["conversationId"], data["messageId"]) }
        } else {
            container.repository.onPushPayload(
                PushBridge.PushPayload(
                    conversationId = data["conversationId"],
                    messageId = data["messageId"],
                    clientMessageId = data["clientMessageId"],
                    senderId = data["senderId"],
                    senderName = data["senderName"],
                    text = data["text"],
                    priority = data["priority"],
                    createdAt = data["createdAt"],
                ),
            )
        }

        // Wake Call Assist so the message is spoken instead of only notified.
        runCatching { CallAssistController.start(this) }.onFailure { error ->
            AppLog.w("Fcm", "foreground_start_refused", error)
            container.notifier.problem(
                "Message waiting",
                "A message arrived but Android did not allow Call Assist to start in the background. Open AirWhispers to hear it.",
            )
        }
    }

    private suspend fun fetchAndIngest(container: AppContainer, conversationId: String?, messageId: String?) {
        if (conversationId == null) return
        val result = container.api.messages(conversationId, limit = 20)
        if (result is com.airwhispers.core.AppResult.Ok) {
            val dto = result.value.messages.lastOrNull { it.id == messageId } ?: result.value.messages.lastOrNull()
            val selfId = container.secretStore.userId().orEmpty()
            dto?.let { container.repository.ingest(it.toDomain(conversationId, selfId)) }
        }
    }
}

/** Registers the push handler before `Application.onCreate` runs. */
class FcmInitializerProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        PushBridge.handler = FcmHandler
        AppLog.i("Fcm", "provider_installed")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object FcmHandler : PushBridge.PushHandler {

    override fun isConfigured(context: Context): Boolean =
        runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    override suspend fun token(context: Context): String? {
        if (!isConfigured(context)) return null
        return runCatching { FirebaseMessaging.getInstance().token.awaitResult() }.getOrElse {
            AppLog.w("Fcm", "token_unavailable", it)
            null
        }
    }

    override fun onPush(context: Context, payload: PushBridge.PushPayload) {
        // The FCM service path handles delivery; kept so other providers can reuse
        // the same bridge without touching the core.
        AppContainer.peekOrNull()?.repository?.onPushPayload(payload)
    }
}

/** `Task<T>.await()` without pulling in play-services-coroutines. */
private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
