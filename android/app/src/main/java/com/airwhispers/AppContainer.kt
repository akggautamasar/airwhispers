package com.airwhispers

import android.app.Application
import com.airwhispers.core.AppLog
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.core.TimeSource
import com.airwhispers.data.local.LocalStore
import com.airwhispers.data.prefs.SecretStore
import com.airwhispers.data.prefs.SettingsStore
import com.airwhispers.data.remote.ApiClient
import com.airwhispers.data.remote.RealtimeClient
import com.airwhispers.data.repo.AirWhispersRepository
import com.airwhispers.domain.assist.CallAssistEngine
import com.airwhispers.domain.tts.SpeechSynthesizer
import com.airwhispers.domain.tts.TtsQueue
import com.airwhispers.push.PushBridge
import com.airwhispers.service.AndroidTtsSynthesizer
import com.airwhispers.service.AudioRouter
import com.airwhispers.service.CallDetector
import com.airwhispers.service.Notifier
import com.airwhispers.service.SpeechPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal hand-rolled dependency graph.
 *
 * A DI framework would add a code generator and build time for a single-module
 * app; a plain container is easier to reason about and keeps startup fast. Every
 * dependency is constructor-injected, so tests can build the pieces they need
 * without Android.
 */
class AppContainer(
    private val app: Application,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
    private val time: TimeSource = TimeSource.SYSTEM,
) {

    /** Outlives the UI: holds the socket, outbox and push processing. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(app)
    val secretStore = SecretStore(app)
    val localStore = LocalStore(app, dispatchers)
    val notifier = Notifier(app)
    val audioRouter = AudioRouter(app)

    val synthesizer: SpeechSynthesizer = AndroidTtsSynthesizer(app, dispatchers)
    val ttsQueue = TtsQueue(time = time)
    val callDetector = CallDetector(app, audioRouter, dispatchers, time)
    val assistEngine = CallAssistEngine(localStore, ttsQueue, time)

    val api: ApiClient = ApiClient(
        settings = settingsStore,
        secrets = secretStore,
        dispatchers = dispatchers,
        onSessionExpired = {
            // The UI reacts by returning to the sign-in screen.
            appScope.launch { runCatching { repository.signOut() } }
        },
    )

    val realtime: RealtimeClient = RealtimeClient(
        api = api,
        deviceId = { settingsStore.deviceId },
        dispatchers = dispatchers,
    )

    /** Opens the socket only while something needs it (UI or Call Assist). */
    val relayRequirement = com.airwhispers.data.remote.RelayRequirement { needed ->
        if (needed) realtime.start(appScope) else realtime.stop()
    }

    lateinit var repository: AirWhispersRepository
        private set

    val speechPlayer: SpeechPlayer = SpeechPlayer(
        queue = ttsQueue,
        synthesizer = synthesizer,
        audioRouter = audioRouter,
        settingsStore = settingsStore,
        dispatchers = dispatchers,
        onUtteranceFinished = { item, spoken, error ->
            if (spoken) {
                AppLog.i("Player", "spoken", "id" to AppLog.fingerprint(item.id))
                if (item.messageId.isNotBlank()) {
                    // Best-effort "your message was heard" feedback for the sender.
                    runCatching { api.markSpoken(item.messageId) }
                }
            } else if (error != null) {
                AppLog.w("Player", "speak_failed", null, "reason" to error)
                notifier.problem("Call Assist", error)
            }
        },
    )

    init {
        notifier.ensureChannels()
        repository = AirWhispersRepository(
            context = app,
            api = api,
            realtime = realtime,
            local = localStore,
            secrets = secretStore,
            settings = settingsStore,
            engine = assistEngine,
            queue = ttsQueue,
            dispatchers = dispatchers,
            time = time,
            callStatusProvider = { callDetector.status.value },
            onIncomingMessage = { message, senderName ->
                // Silent exactly when the message is being spoken: the user is
                // listening, so the notification must not also chirp at them.
                val willBeSpoken = repository.lastSpeechDecision.value
                    ?.takeIf { it.action == CallAssistEngine.Action.SPEAK }
                    ?.item?.id == message.clientMessageId
                notifier.showMessage(
                    id = message.clientMessageId,
                    message = message,
                    senderName = senderName,
                    silent = willBeSpoken || ttsQueue.state.value.current?.id == message.clientMessageId,
                )
            },
        )
        // Cloud pushes (fcm flavor) land here.
        appScope.launch {
            repository.pendingPushMessages.collect { pending ->
                if (pending.isEmpty()) return@collect
                pending.forEach { (message, senderName) ->
                    repository.ingest(message, senderName = senderName)
                }
            }
        }
    }

    fun startRepositoryPipeline() {
        repository.startPipeline(appScope)
    }

    /** Called by UI screens while they are visible. */
    fun onUiVisible() {
        relayRequirement.acquire(RELAY_REASON_UI)
        startRepositoryPipeline()
        appScope.launch { repository.refreshConversations() }
    }

    fun onUiHidden() {
        relayRequirement.release(RELAY_REASON_UI)
    }

    companion object {
        const val RELAY_REASON_UI = "ui"
        const val RELAY_REASON_CALL_ASSIST = "call_assist"

        @Volatile
        private var instance: AppContainer? = null

        fun install(container: AppContainer) {
            instance = container
            // Let an optional push provider reach the core.
            PushBridge.handler?.let { AppLog.i("App", "push_provider_available") }
        }

        fun get(): AppContainer = instance
            ?: error("AppContainer not initialised — AirWhispersApplication must run first")

        fun peekOrNull(): AppContainer? = instance
    }
}
