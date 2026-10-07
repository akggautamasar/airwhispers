package com.airwhispers.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airwhispers.AppContainer
import com.airwhispers.core.AppLog

/**
 * After a reboot (or an app update) Call Assist cannot simply resume: Android 15+
 * forbids starting a media-playback foreground service from a boot broadcast.
 *
 * Instead we post a notification the user can tap, which starts the service from
 * the foreground — the only path the platform allows, and the honest one.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val container = AppContainer.peekOrNull() ?: run {
            AppLog.d("Boot", "container_unavailable")
            return
        }
        val wanted = container.settingsStore.callAssistWantedAfterBoot &&
            container.settingsStore.callAssist.value.enabled
        if (!wanted) {
            AppLog.d("Boot", "call_assist_not_armed")
            return
        }
        AppLog.i("Boot", "reminder_posted", "action" to action)
        container.notifier.bootReminder()
    }
}
