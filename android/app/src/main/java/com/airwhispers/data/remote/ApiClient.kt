package com.airwhispers.data.remote

import com.airwhispers.BuildConfig
import com.airwhispers.config.ProductConfig
import com.airwhispers.core.AppError
import com.airwhispers.core.AppErrorKind
import com.airwhispers.core.AppLog
import com.airwhispers.core.AppResult
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.data.prefs.SecretStore
import com.airwhispers.data.prefs.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTPS client for the AirWhispers API.
 *
 * Responsibilities kept intentionally narrow: build requests, carry the bearer
 * token, transparently mint a new one from the device secret on 401 (this is the
 * "no login" trick — there is no sign-in screen to fall back to, and there never
 * has to be one), and translate failures into [AppError]s.
 */
class ApiClient(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
    /** Called when even the device secret no longer works: the app re-registers. */
    private val onIdentityLost: () -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val refreshMutex = Mutex()

    val baseUrl: String get() = settings.backendUrl.value.trimEnd('/')

    val rawHttpClient: OkHttpClient get() = http
    val jsonFormat: Json get() = json

    /** Read-only access for the realtime transport's auth frame. */
    fun currentAccessToken(): String? = secrets.accessToken()

    /**
     * A usable access token, minted silently from the device secret when the cached
     * one is missing (first run) or already expired.
     */
    suspend fun ensureAccessToken(): String? {
        secrets.accessToken()?.takeIf { it.isNotBlank() }?.let { return it }
        return if (refreshIdentity()) secrets.accessToken() else null
    }

    fun webSocketUrl(): String {
        val base = baseUrl
        val schemeAdjusted = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$schemeAdjusted/api/${ProductConfig.DEFAULT_API_VERSION}/realtime"
    }

    // ------------------------------------------------------------------ identity

    /**
     * Registers this installation (or resumes it).
     *
     *  - no stored secret → the server hands out a fresh code (first launch),
     *  - stored secret     → the same identity answers with a fresh token.
     */
    suspend fun registerDevice(displayName: String? = null): AppResult<DeviceResponse> {
        val body = json.encodeToString(
            DeviceRegisterRequest.serializer(),
            DeviceRegisterRequest(
                deviceId = settings.deviceId,
                deviceSecret = secrets.deviceSecret(),
                displayName = displayName,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        val result = request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/device/register", body, authenticated = false)
        return when (result) {
            is AppResult.Err -> result
            is AppResult.Ok -> decode(result.value, DeviceResponse.serializer()).also { storeIdentity(it) }
        }
    }

    /** Throws the identity away on the server and asks for a brand-new code. */
    suspend fun forgetDevice(): AppResult<Unit> {
        val secret = secrets.deviceSecret()
        if (secret.isNullOrBlank()) return AppResult.Ok(Unit)
        val body = json.encodeToString(
            DeviceSecretRequest.serializer(),
            DeviceSecretRequest(deviceId = settings.deviceId, deviceSecret = secret),
        )
        val result = request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/device/forget", body, authenticated = false)
        secrets.clearSession()
        return expectNoContent(result)
    }

    suspend fun me(): AppResult<MeResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/me", MeResponse.serializer())

    suspend fun updateProfile(displayName: String): AppResult<MeResponse> {
        val body = json.encodeToString(UpdateProfileRequest.serializer(), UpdateProfileRequest(displayName))
        return decode(
            request("PATCH", "/api/${ProductConfig.DEFAULT_API_VERSION}/me", body, authenticated = true),
            MeResponse.serializer(),
        )
    }

    // ----------------------------------------------------------- messaging api

    suspend fun conversations(): AppResult<ConversationListResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/conversations", ConversationListResponse.serializer())

    /** Opens (or re-opens) the chat with the device that owns [code]. */
    suspend fun createConversation(code: String): AppResult<ConversationResponse> {
        val body = json.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest(code))
        return decode(
            request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/conversations", body, authenticated = true),
            ConversationResponse.serializer(),
        )
    }

    suspend fun messages(conversationId: String, limit: Int = 100): AppResult<MessageListResponse> =
        get(
            "/api/${ProductConfig.DEFAULT_API_VERSION}/conversations/$conversationId/messages?limit=$limit",
            MessageListResponse.serializer(),
        )

    suspend fun sendMessage(
        conversationId: String,
        clientMessageId: String,
        text: String,
        speakNow: Boolean,
    ): AppResult<MessageResponse> {
        val body = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(
                clientMessageId = clientMessageId,
                text = text,
                priority = if (speakNow) "SPEAK_NOW" else "NORMAL",
            ),
        )
        return decode(
            request(
                "POST",
                "/api/${ProductConfig.DEFAULT_API_VERSION}/conversations/$conversationId/messages",
                body,
                authenticated = true,
            ),
            MessageResponse.serializer(),
        )
    }

    /** Speech consent: "this person may be whispered to on my phone". */
    suspend fun setTrust(conversationId: String, trusted: Boolean): AppResult<ConversationResponse> {
        val body = json.encodeToString(TrustRequest.serializer(), TrustRequest(trusted))
        return decode(
            request(
                "PATCH",
                "/api/${ProductConfig.DEFAULT_API_VERSION}/conversations/$conversationId/trust",
                body,
                authenticated = true,
            ),
            ConversationResponse.serializer(),
        )
    }

    suspend fun markRead(messageId: String): AppResult<Unit> = expectNoContent(
        request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/messages/$messageId/read", null, authenticated = true),
    )

    /** Feedback loop: tells the sender their message was actually spoken here. */
    suspend fun markSpoken(messageId: String): AppResult<Unit> = expectNoContent(
        request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/messages/$messageId/spoken", null, authenticated = true),
    )

    // --------------------------------------------------------------- devices api

    suspend fun registerPushToken(pushToken: String?): AppResult<Unit> {
        val body = json.encodeToString(
            DeviceRequest.serializer(),
            DeviceRequest(
                deviceId = settings.deviceId,
                pushToken = pushToken,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        return expectNoContent(
            request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/devices", body, authenticated = true),
        )
    }

    suspend fun unregisterPushToken(): AppResult<Unit> = expectNoContent(
        request("DELETE", "/api/${ProductConfig.DEFAULT_API_VERSION}/devices/${settings.deviceId}", null, authenticated = true),
    )

    // ------------------------------------------------------------------- setup

    /** Reachability probe used by the server-setup screen (no auth required). */
    suspend fun health(): AppResult<HealthResponse> =
        decode(request("GET", "/healthz", null, authenticated = false), HealthResponse.serializer())

    /** "Are you an AirWhispers server?" — shown as a friendly check during setup. */
    suspend fun serverInfo(): AppResult<ServerInfoDto> =
        decode(request("GET", "/api/${ProductConfig.DEFAULT_API_VERSION}/server", null, authenticated = false), ServerInfoDto.serializer())

    // ------------------------------------------------------------------ plumbing

    private suspend fun <T> get(path: String, serializer: DeserializationStrategy<T>): AppResult<T> =
        decode(request("GET", path, null, authenticated = true), serializer)

    /**
     * Unwraps the transport result and parses it. Errors travel through untouched so
     * callers can distinguish "network down" from "malformed response".
     */
    private fun <T> decode(raw: AppResult<String?>, serializer: DeserializationStrategy<T>): AppResult<T> =
        when (raw) {
            is AppResult.Err -> raw
            is AppResult.Ok -> decode(raw.value, serializer)
        }

    private fun <T> decode(raw: String?, serializer: DeserializationStrategy<T>): AppResult<T> {
        if (raw.isNullOrBlank()) {
            return AppResult.Err(AppError.server("Empty response from server", status = 200))
        }
        return runCatching { AppResult.Ok(json.decodeFromString(serializer, raw)) }
            .getOrElse { AppResult.Err(AppError.unknown("Malformed server response", it)) }
    }

    private fun expectNoContent(result: AppResult<String?>): AppResult<Unit> = when (result) {
        is AppResult.Ok -> AppResult.Ok(Unit)
        is AppResult.Err -> result
    }

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        authenticated: Boolean,
        isRetryAfterRefresh: Boolean = false,
    ): AppResult<String?> = withContext(dispatchers.io) {
        if (baseUrl.isBlank()) {
            return@withContext AppResult.Err(
                AppError(AppErrorKind.BAD_REQUEST, "No server configured yet"),
            )
        }
        val url = baseUrl + path
        val builder = Request.Builder().url(url).method(method, body?.toRequestBody(JSON_MEDIA) ?: emptyBody(method))
        if (authenticated) {
            val token = secrets.accessToken()
            if (token.isNullOrBlank()) {
                // No token yet (or it was cleared): try the device secret once.
                if (refreshIdentity()) {
                    return@withContext request(method, path, body, authenticated = true, isRetryAfterRefresh = true)
                }
                onIdentityLost()
                return@withContext AppResult.Err(AppError.unauthorized("This device is not registered yet"))
            }
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("Accept", "application/json")
        builder.header("X-Client", "airwhispers-android")

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (io: IOException) {
            AppLog.w("Api", "transport_error", io, "method" to method, "path" to sanitizePath(path))
            return@withContext AppResult.Err(AppError.network("Can’t reach the server", io))
        }

        response.use { res ->
            val rawBody = res.body?.string()
            when {
                res.isSuccessful -> AppResult.Ok(rawBody)

                res.code == 401 && authenticated && !isRetryAfterRefresh -> {
                    if (refreshIdentity()) {
                        request(method, path, body, authenticated = true, isRetryAfterRefresh = true)
                    } else {
                        onIdentityLost()
                        AppResult.Err(AppError.unauthorized("This device is no longer recognised"))
                    }
                }

                else -> AppResult.Err(errorFrom(res, rawBody))
            }
        }
    }

    /**
     * Body-less requests. OkHttp refuses `null` bodies for POST/PUT/PATCH, so those
     * get a zero-length JSON body — the API parses an empty body as `{}` (that is
     * what makes body-less actions like the `spoken` receipt work).
     */
    private fun emptyBody(method: String): RequestBody? = when (method.uppercase()) {
        "POST", "PUT", "PATCH" -> ByteArray(0).toRequestBody(JSON_MEDIA)
        else -> null
    }

    private fun errorFrom(response: Response, rawBody: String?): AppError {
        val detail = rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ApiErrorBody.serializer(), it).error }.getOrNull() }
        val message = detail?.message ?: "Request failed (${response.code})"
        val kind = when (response.code) {
            400, 422 -> AppErrorKind.BAD_REQUEST
            401 -> AppErrorKind.UNAUTHORIZED
            403 -> AppErrorKind.FORBIDDEN
            404 -> AppErrorKind.NOT_FOUND
            409 -> AppErrorKind.CONFLICT
            429 -> AppErrorKind.RATE_LIMITED
            in 500..599 -> AppErrorKind.SERVER
            else -> AppErrorKind.UNKNOWN
        }
        return AppError(kind, message, httpStatus = response.code)
    }

    /**
     * The whole "login" of this product: exchange the device secret for a fresh
     * access token. Single-flight so a burst of 401s causes one call.
     */
    private suspend fun refreshIdentity(): Boolean = refreshMutex.withLock {
        val secret = secrets.deviceSecret() ?: return@withLock false
        val body = json.encodeToString(
            DeviceSecretRequest.serializer(),
            DeviceSecretRequest(deviceId = settings.deviceId, deviceSecret = secret),
        )
        withContext(dispatchers.io) {
            val refreshRequest = Request.Builder()
                .url(baseUrl + "/api/${ProductConfig.DEFAULT_API_VERSION}/device/token")
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Accept", "application/json")
                .build()
            runCatching {
                http.newCall(refreshRequest).execute().use { res ->
                    val raw = res.body?.string()
                    if (!res.isSuccessful || raw.isNullOrBlank()) {
                        false
                    } else {
                        val tokens = json.decodeFromString(DeviceResponse.serializer(), raw)
                        secrets.putAccessToken(tokens.tokens.accessToken)
                        secrets.putUserId(tokens.user.id)
                        settings.rememberIdentity(tokens.user.code, tokens.user.displayName)
                        true
                    }
                }
            }.getOrElse {
                AppLog.w("Api", "identity_refresh_failed", it)
                false
            }
        }
    }

    private fun storeIdentity(result: AppResult<DeviceResponse>) {
        if (result is AppResult.Ok) {
            secrets.putAccessToken(result.value.tokens.accessToken)
            secrets.putUserId(result.value.user.id)
            result.value.deviceSecret?.let { secrets.putDeviceSecret(it) }
            settings.rememberIdentity(result.value.user.code, result.value.user.displayName)
        }
    }

    /** Keeps message ids out of diagnostics while still allowing correlation. */
    private fun sanitizePath(path: String): String =
        path.replace(Regex("/[0-9a-fA-F-]{8,}"), "/{id}")

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
