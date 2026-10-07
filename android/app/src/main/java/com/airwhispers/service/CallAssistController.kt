package com.airwhispers.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The only way the rest of the app is allowed to talk to [CallAssistService].
 *
 * Every call here is a *user action*: Android forbids starting a foreground
 * service from the background, so nothing in the app starts Call Assist on its
 * own — with one documented exception (a high-priority cloud push while the
 * service is already armed, handled inside the push provider).
 */
object CallAssistController {

    fun start(context: Context) = send(context, Notifier.ACTION_START, foreground = true)

    fun stop(context: Context) = send(context, Notifier.ACTION_STOP)

    fun pause(context: Context) = send(context, Notifier.ACTION_PAUSE)

    fun resume(context: Context) = send(context, Notifier.ACTION_RESUME)

    fun skip(context: Context) = send(context, Notifier.ACTION_SKIP)

    fun clearQueue(context: Context) = send(context, Notifier.ACTION_CLEAR_QUEUE)

    /** Speaks the preview sentence inside the running session. */
    fun testSpeak(context: Context) = send(context, Notifier.ACTION_TEST_SPEAK)

    private fun send(context: Context, action: String, foreground: Boolean = false) {
        val intent = Intent(context, CallAssistService::class.java).apply { this.action = action }
        if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
