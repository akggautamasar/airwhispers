package com.airwhispers.data.remote

import com.airwhispers.core.AppLog
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.random.Random

enum class RelayState { IDLE, CONNECTING, CONNECTED, AUTHENTICATED, BACKING_OFF }

/**
 * Realtime transport (WebSocket) used while the app is in the foreground or while
 * Call Assist is deliberately running.
 *
 * Battery contract: it is never started "just in case". The process keeps at most
 * one socket, opened only while a [RelayRequirement] is active, with capped
 * exponential backoff, and closed again the moment the requirement goes away.
 */
class RealtimeClient(
    private val api: ApiClient,
    private val deviceId: () -> String,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val _events = MutableSharedFlow<EventDto>(extraBufferCapacity = 128)
    val events: SharedFlow<EventDto> = _events.asSharedFlow()

    private val _state = MutableStateFlow(RelayState.IDLE)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var loop: Job? = null
    private val connecting = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch(dispatchers.default) {
            var attempt = 0
            while (true) {
                val connected = runCatching { connectOnce() }.getOrElse {
                    AppLog.w("Realtime", "connect_error", it)
                    false
                }
                attempt = if (connected) 0 else attempt + 1
                if (!connected) {
                    _state.value = RelayState.BACKING_OFF
                    val backoff = backoffFor(attempt)
                    AppLog.d("Realtime", "retry", "attempt" to attempt, "delayMs" to backoff)
                    delay(backoff)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "client_closing")
        socket = null
        connecting.set(false)
        _state.value = RelayState.IDLE
    }

    fun send(frame: JsonObject): Boolean = socket?.send(frame.toString()) ?: false

    /**
     * Opens one socket and suspends until it closes. Returns true when the socket
     * reached the authenticated state, so the caller can reset its backoff.
     */
    private suspend fun connectOnce(): Boolean {
        if (!connecting.compareAndSet(false, true)) return true
        var authenticated = false
        try {
            val token = api.currentAccessToken()
            if (token.isNullOrBlank()) {
                // No session yet — nothing to connect with; idle out quietly.
                _state.value = RelayState.IDLE
                delay(5_000)
                return true
            }
            val request = Request.Builder().url(api.webSocketUrl()).build()
            _state.value = RelayState.CONNECTING
            val closed = suspendCancellableCoroutine<Unit> { continuation ->
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _state.value = RelayState.CONNECTED
                        webSocket.send(authFrame(token))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleFrame(webSocket, text)?.let { authenticated = it }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        AppLog.w("Realtime", "socket_failure", t, "code" to response?.code)
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                val ws = api.rawHttpClient.newWebSocket(request, listener)
                socket = ws
                continuation.invokeOnCancellation { ws.cancel() }
            }
            closed
        } finally {
            connecting.set(false)
            socket = null
            if (_state.value != RelayState.IDLE) _state.value = RelayState.CONNECTING
        }
        return authenticated
    }

    private fun authFrame(token: String): String = buildJsonObject {
        put("type", JsonPrimitive("auth"))
        put(
            "data",
            buildJsonObject {
                put("accessToken", JsonPrimitive(token))
                put("deviceId", JsonPrimitive(deviceId()))
                put("client", JsonPrimitive("android"))
            },
        )
    }.toString()

    /** @return true when the frame proved authentication succeeded. */
    private fun handleFrame(webSocket: WebSocket, text: String): Boolean? {
        val event = runCatching { json.decodeFromString(EventDto.serializer(), text) }.getOrElse {
            AppLog.w("Realtime", "malformed_frame", it)
            return null
        }
        when (event.type) {
            EventTypes.AUTH_OK -> {
                _state.value = RelayState.AUTHENTICATED
                AppLog.i("Realtime", "authenticated")
                return true
            }
            EventTypes.AUTH_REQUIRED -> {
                // Server asks again (e.g. after a token refresh): reply once more.
                api.currentAccessToken()?.let { webSocket.send(authFrame(it)) }
                return null
            }
            EventTypes.PONG -> return null
        }
        _events.tryEmit(event)
        return null
    }

    private fun backoffFor(attempt: Int): Long {
        val base = min(60_000L, 1_000L shl min(attempt, 6))
        val jitter = Random.nextLong(0, 500L)
        return base + jitter
    }
}

/**
 * Reference-counted "something needs the relay open" signal.
 *
 * Call Assist (foreground service) and the UI each register a requirement; the
 * socket stays open only while at least one exists.
 */
class RelayRequirement(private val onChange: (Boolean) -> Unit) {
    private val holders = linkedSetOf<String>()

    @Synchronized
    fun acquire(reason: String) {
        val wasEmpty = holders.isEmpty()
        holders.add(reason)
        if (wasEmpty) onChange(true)
    }

    @Synchronized
    fun release(reason: String) {
        holders.remove(reason)
        if (holders.isEmpty()) onChange(false)
    }

    @Synchronized
    fun active(): Boolean = holders.isNotEmpty()

    @Synchronized
    fun snapshot(): Set<String> = holders.toSet()
}
