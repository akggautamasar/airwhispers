package com.airwhispers.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airwhispers.R
import com.airwhispers.config.ProductConfig
import com.airwhispers.data.model.Conversation
import com.airwhispers.data.model.Message

/**
 * All notifications in one place.
 *
 * Call Assist rule: while a message is being *spoken*, the notification stays
 * silent and collapsed — the user is listening, not reading. Only messages that
 * will not be spoken get an audible notification, so nothing is announced twice.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notif_channel_messages_desc) },
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL_ASSIST,
                context.getString(R.string.notif_channel_call_assist),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_call_assist_desc)
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ERRORS,
                context.getString(R.string.notif_channel_errors),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_errors_desc) },
        )
    }

    fun canNotify(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    fun messageNotification(message: Message, senderName: String, silent: Boolean): Notification {
        val contentIntent = openAppIntent(extraConversationId = message.conversationId)
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(senderName)
            .setContentText(message.text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text.take(600)))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_MESSAGES)

        if (silent) {
            builder.setSilent(true)
            builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }
        if (message.priority == com.airwhispers.data.model.MessagePriority.SPEAK_NOW) {
            builder.setSubText(context.getString(R.string.chat_speak_now))
        }
        return builder.build()
    }

    /**
     * Every notification goes through here.
     *
     * Posting on Android 13+ without POST_NOTIFICATIONS throws, so callers check
     * [canNotify] first; a notification is never important enough to crash the app,
     * hence the belt-and-braces `runCatching`. Lint cannot see through [canNotify],
     * so the permission suppression lives here — in exactly one place — rather than
     * being scattered over four call sites.
     */
    @SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    fun showMessage(id: String, message: Message, senderName: String, silent: Boolean) {
        if (!canNotify()) return
        notifySafely(id.hashCode(), messageNotification(message, senderName, silent))
    }

    fun callAssistNotification(
        text: String,
        subText: String?,
        paused: Boolean,
        queueDepth: Int,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_CALL_ASSIST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_assist_active))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(
                0,
                context.getString(if (paused) R.string.call_assist_resume else R.string.call_assist_pause),
                serviceAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, context.getString(R.string.call_assist_skip), serviceAction(ACTION_SKIP))
            .addAction(0, context.getString(R.string.call_assist_stop), serviceAction(ACTION_STOP))

        if (queueDepth > 0) {
            builder.setSubText(context.getString(R.string.call_assist_queue, queueDepth))
        }
        subText?.let { builder.setSubText(it) }
        return builder.build()
    }

    fun postCallAssist(notification: Notification) {
        if (canNotify()) notifySafely(CALL_ASSIST_NOTIFICATION_ID, notification)
    }

    fun problem(title: String, body: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifySafely(PROBLEM_NOTIFICATION_ID, notification)
    }

    fun clearCallAssist() = manager.cancel(CALL_ASSIST_NOTIFICATION_ID)

    fun clearMessages() = manager.cancelAll()

    /** "Tap to resume Call Assist" — used after a reboot, where Android forbids
     *  starting a media-playback foreground service directly. */
    fun bootReminder() {
        if (!canNotify()) return
        val intent = Intent(context, CallAssistService::class.java).apply {
            action = ACTION_START
        }
        val pending = PendingIntent.getForegroundService(
            context,
            31,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_ASSIST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.call_assist_title))
            .setContentText("Tap to resume ${ProductConfig.APP_NAME} Call Assist after restart.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notifySafely(BOOT_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(extraConversationId: String? = null): PendingIntent {
        val intent = Intent(context, com.airwhispers.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extraConversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            extraConversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(context, CallAssistService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun conversationSummary(conversation: Conversation): String =
        conversation.lastMessageText.orEmpty().take(120)

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CALL_ASSIST = "call_assist"
        const val CHANNEL_ERRORS = "errors"

        const val CALL_ASSIST_NOTIFICATION_ID = 1001
        const val PROBLEM_NOTIFICATION_ID = 1002
        const val BOOT_NOTIFICATION_ID = 1003
        const val GROUP_MESSAGES = "airwhispers.messages"

        const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val ACTION_START = "com.airwhispers.action.START"
        const val ACTION_STOP = "com.airwhispers.action.STOP"
        const val ACTION_PAUSE = "com.airwhispers.action.PAUSE"
        const val ACTION_RESUME = "com.airwhispers.action.RESUME"
        const val ACTION_SKIP = "com.airwhispers.action.SKIP"
        const val ACTION_CLEAR_QUEUE = "com.airwhispers.action.CLEAR_QUEUE"
        const val ACTION_REFRESH = "com.airwhispers.action.REFRESH"
        const val ACTION_TEST_SPEAK = "com.airwhispers.action.TEST_SPEAK"
    }
}
