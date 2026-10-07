package com.airwhispers.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.airwhispers.AppContainer
import com.airwhispers.R
import com.airwhispers.core.AppLog
import com.airwhispers.data.model.CallState
import com.airwhispers.domain.tts.SpeechItem
import com.airwhispers.data.model.MessagePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The user-visible Call Assist session.
 *
 * Responsibilities:
 *  - keep the realtime connection open *only* while the user wants Call Assist;
 *  - own call detection;
 *  - drain the speech queue through [SpeechPlayer];
 *  - mirror the state into an ongoing notification with pause/skip/stop.
 *
 * It is started exclusively by an explicit user action (app button, quick-settings
 * tile, or notification action). Android 12+ forbids background-starting a
 * foreground service, and the product deliberately does not fight that rule.
 */
class CallAssistService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var container: AppContainer
    private lateinit var notifier: Notifier
    private lateinit var detector: CallDetector
    private lateinit var player: SpeechPlayer

    private var foregroundStarted = false
    private var stateJob: Job? = null
    private var idleJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get()
        notifier = container.notifier
        detector = container.callDetector
        player = container.speechPlayer
        notifier.ensureChannels()
        AppLog.i("CallAssist", "service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Notifier.ACTION_STOP -> {
                stopEverything(stopService = true)
                return START_NOT_STICKY
            }
            Notifier.ACTION_PAUSE -> {
                container.ttsQueue.pause()
                publishNotification()
                return START_STICKY
            }
            Notifier.ACTION_RESUME -> {
                container.ttsQueue.resume()
                publishNotification()
                return START_STICKY
            }
            Notifier.ACTION_SKIP -> {
                container.ttsQueue.skip()
                return START_STICKY
            }
            Notifier.ACTION_CLEAR_QUEUE -> {
                container.ttsQueue.clearPending()
                publishNotification()
                return START_STICKY
            }
            Notifier.ACTION_TEST_SPEAK -> {
                enqueueTestUtterance()
            }
        }

        startForegroundSafely()
        startAssist()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.i("CallAssist", "service_destroyed")
        stopEverything(stopService = false)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startForegroundSafely() {
        if (foregroundStarted) return
        val notification = notifier.callAssistNotification(
            text = describeState(),
            subText = null,
            paused = container.ttsQueue.state.value.paused,
            queueDepth = container.ttsQueue.state.value.depth,
        )
        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifier.CALL_ASSIST_NOTIFICATION_ID, notification, type)
            foregroundStarted = true
        }.onFailure {
            // Background start restrictions: keep the service as a plain
            // background service instead of crashing. The notification still tells
            // the user what is happening.
            AppLog.w("CallAssist", "foreground_start_denied", it)
            notifier.postCallAssist(notification)
        }
    }

    private fun startAssist() {
        container.settingsStore.updateCallAssist { it.copy(enabled = true) }
        container.settingsStore.callAssistWantedAfterBoot = true
        container.relayRequirement.acquire(AppContainer.RELAY_REASON_CALL_ASSIST)
        container.startRepositoryPipeline()

        detector.start(scope)
        player.start(scope)

        if (stateJob?.isActive != true) {
            stateJob = scope.launch {
                container.ttsQueue.state.collect { snapshot ->
                    publishNotification()
                    resetIdleTimer()
                    if (snapshot.current != null) {
                        AppLog.d("CallAssist", "speaking", "from" to snapshot.current.senderName)
                    }
                }
            }
        }
        if (idleJob?.isActive != true) {
            idleJob = scope.launch { autoStopWhenIdle() }
        }
        publishNotification()
    }

    private fun stopEverything(stopService: Boolean) {
        stateJob?.cancel()
        idleJob?.cancel()
        stateJob = null
        idleJob = null
        player.stop()
        detector.stop(scope)
        container.relayRequirement.release(AppContainer.RELAY_REASON_CALL_ASSIST)
        container.settingsStore.updateCallAssist { it.copy(enabled = false) }
        if (stopService) {
            notifier.clearCallAssist()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
    }

    // ------------------------------------------------------------------ behaviour

    private fun describeState(): String {
        val status = detector.status.value
        val queue = container.ttsQueue.state.value
        val callLabel = when (status.state) {
            CallState.IN_CALL -> "Phone call in progress"
            CallState.IN_COMMUNICATION -> "Call or communication session detected"
            CallState.RINGING -> "Incoming call"
            CallState.IDLE -> "Waiting for a call"
        }
        val speaking = queue.current?.let { "Speaking: ${it.senderName}" }
        return speaking ?: callLabel
    }

    private fun publishNotification() {
        if (!foregroundStarted) {
            // First publish also promotes the service to the foreground.
            startForegroundSafely()
            return
        }
        val queue = container.ttsQueue.state.value
        notifier.postCallAssist(
            notifier.callAssistNotification(
                text = describeState(),
                subText = null,
                paused = queue.paused,
                queueDepth = queue.depth,
            ),
        )
    }

    private fun enqueueTestUtterance() {
        val item = SpeechItem(
            id = "test-${System.currentTimeMillis()}",
            messageId = "",
            conversationId = "",
            senderName = "AirWhispers",
            body = getString(R.string.settings_tts_preview_text),
            priority = MessagePriority.SPEAK_NOW,
            createdAt = System.currentTimeMillis(),
            announceSender = false,
        )
        container.ttsQueue.enqueue(item)
    }

    /** Long sessions should not silently drain the battery forever. */
    private var lastActivityAt = System.currentTimeMillis()

    private fun resetIdleTimer() {
        lastActivityAt = System.currentTimeMillis()
    }

    private suspend fun autoStopWhenIdle() {
        while (true) {
            delay(60_000)
            val idleFor = System.currentTimeMillis() - lastActivityAt
            val inCall = detector.status.value.inProgress
            if (!inCall && idleFor > AUTO_STOP_AFTER_MS && !container.ttsQueue.state.value.isActive) {
                AppLog.i("CallAssist", "auto_stop_idle", "idleMinutes" to idleFor / 60_000)
                notifier.problem(
                    getString(R.string.call_assist_title),
                    "Call Assist turned off automatically after a long idle period to save battery.",
                )
                stopEverything(stopService = true)
                return
            }
        }
    }

    private companion object {
        /** 3 hours with no call and nothing spoken. */
        const val AUTO_STOP_AFTER_MS = 3 * 60 * 60 * 1_000L
    }
}
