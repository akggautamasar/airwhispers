package com.airwhispers.data.remote

import com.airwhispers.core.AppError
import com.airwhispers.core.AppErrorKind
import com.airwhispers.core.AppLog
import com.airwhispers.core.AppResult
import com.airwhispers.config.ProductConfig
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTPS client for the AirWhispers API.
 *
 * Responsibilities kept intentionally narrow: build requests, carry the bearer
 * token, transparently refresh it once on 401, and translate failures into
 * [AppError]s. Retry/backoff for *delivery* lives in the repository layer.
 */
class ApiClient(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
    private val onSessionExpired: () -> Unit = {},
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

    fun webSocketUrl(): String {
        val base = baseUrl
        val schemeAdjusted = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$schemeAdjusted/api/${ProductConfig.DEFAULT_API_VERSION}/realtime"
    }

    // ------------------------------------------------------------------ auth api

    suspend fun register(email: String, password: String, displayName: String): AppResult<AuthResponse> {
        val body = json.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(email = email, password = password, displayName = displayName, deviceId = settings.deviceId),
        )
        val result = request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/auth/register", body, authenticated = false)
        return when (result) {
            is AppResult.Err -> result
            is AppResult.Ok -> decode(result.value, AuthResponse.serializer()).also { storeTokens(it) }
        }
    }

    suspend fun login(email: String, password: String): AppResult<AuthResponse> {
        val body = json.encodeToString(
            LoginRequest.serializer(),
            LoginRequest(email = email, password = password, deviceId = settings.deviceId),
        )
        val result = request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/auth/login", body, authenticated = false)
        return when (result) {
            is AppResult.Err -> result
            is AppResult.Ok -> decode(result.value, AuthResponse.serializer()).also { storeTokens(it) }
        }
    }

    suspend fun logout(): AppResult<Unit> {
        val refresh = secrets.refreshToken()
        val body = refresh?.let { json.encodeToString(RefreshRequest.serializer(), RefreshRequest(it)) }
        val result = request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/auth/logout", body, authenticated = true)
        secrets.clearSession()
        return when (result) {
            is AppResult.Ok -> AppResult.Ok(Unit)
            // A failed server-side logout must not trap the user in the app.
            is AppResult.Err -> AppResult.Ok(Unit)
        }
    }

    suspend fun me(): AppResult<MeResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/users/me", MeResponse.serializer())

    suspend fun updateProfile(displayName: String): AppResult<MeResponse> {
        val body = json.encodeToString(
            UpdateProfileRequest.serializer(),
            UpdateProfileRequest(displayName),
        )
        return decode(
            request("PATCH", "/api/${ProductConfig.DEFAULT_API_VERSION}/users/me", body, authenticated = true),
            MeResponse.serializer(),
        )
    }

    // ----------------------------------------------------------- messaging api

    suspend fun conversations(): AppResult<ConversationListResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/conversations", ConversationListResponse.serializer())

    suspend fun createConversation(peerUserId: String): AppResult<ConversationResponse> {
        val body = json.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest(peerUserId))
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

    suspend fun markRead(messageId: String): AppResult<Unit> = expectNoContent(
        request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/messages/$messageId/read", null, authenticated = true),
    )

    /** Feedback loop: tells the sender their message was actually spoken here. */
    suspend fun markSpoken(messageId: String): AppResult<Unit> = expectNoContent(
        request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/messages/$messageId/spoken", null, authenticated = true),
    )

    // ------------------------------------------------------------- contacts api

    suspend fun contacts(): AppResult<ContactListResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/contacts", ContactListResponse.serializer())

    suspend fun addContact(email: String): AppResult<ContactResponse> {
        val body = json.encodeToString(AddContactRequest.serializer(), AddContactRequest(email))
        return decode(
            request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/contacts", body, authenticated = true),
            ContactResponse.serializer(),
        )
    }

    suspend fun updateContact(contactId: String, isTrusted: Boolean?, displayName: String?): AppResult<ContactResponse> {
        val body = json.encodeToString(
            UpdateContactRequest.serializer(),
            UpdateContactRequest(isTrusted = isTrusted, displayName = displayName),
        )
        return decode(
            request("PATCH", "/api/${ProductConfig.DEFAULT_API_VERSION}/contacts/$contactId", body, authenticated = true),
            ContactResponse.serializer(),
        )
    }

    suspend fun deleteContact(contactId: String): AppResult<Unit> = expectNoContent(
        request("DELETE", "/api/${ProductConfig.DEFAULT_API_VERSION}/contacts/$contactId", null, authenticated = true),
    )

    // ------------------------------------------------------------- settings api

    suspend fun settings(): AppResult<SettingsResponse> =
        get("/api/${ProductConfig.DEFAULT_API_VERSION}/settings", SettingsResponse.serializer())

    suspend fun updateSettings(patch: UpdateSettingsRequest): AppResult<SettingsResponse> {
        val body = json.encodeToString(UpdateSettingsRequest.serializer(), patch)
        return decode(
            request("PATCH", "/api/${ProductConfig.DEFAULT_API_VERSION}/settings", body, authenticated = true),
            SettingsResponse.serializer(),
        )
    }

    // --------------------------------------------------------------- devices api

    suspend fun registerDevice(pushToken: String?, appVersion: String): AppResult<Unit> {
        val body = json.encodeToString(
            DeviceRequest.serializer(),
            DeviceRequest(deviceId = settings.deviceId, pushToken = pushToken, appVersion = appVersion),
        )
        return expectNoContent(
            request("POST", "/api/${ProductConfig.DEFAULT_API_VERSION}/devices", body, authenticated = true),
        )
    }

    suspend fun unregisterDevice(): AppResult<Unit> = expectNoContent(
        request("DELETE", "/api/${ProductConfig.DEFAULT_API_VERSION}/devices/${settings.deviceId}", null, authenticated = true),
    )

    /** Reachability probe used by the server-setup screen (no auth required). */
    suspend fun health(): AppResult<HealthResponse> =
        decode(request("GET", "/healthz", null, authenticated = false), HealthResponse.serializer())

    // ------------------------------------------------------------------ plumbing

    private suspend fun <T> get(path: String, serializer: DeserializationStrategy<T>): AppResult<T> =
        decode(request("GET", path, null, authenticated = true), serializer)

    private fun <T> decode(raw: String?, serializer: DeserializationStrategy<T>): AppResult<T> {
        if (raw.isNullOrBlank()) {
            return AppResult.Err(AppError.server("Empty response from server", httpStatus = 200))
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
                // Local session is gone: nothing to retry with.
                onSessionExpired()
                return@withContext AppResult.Err(AppError.unauthorized("Not signed in"))
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
                    if (refreshTokens()) {
                        request(method, path, body, authenticated = true, isRetryAfterRefresh = true)
                    } else {
                        onSessionExpired()
                        AppResult.Err(AppError.unauthorized("Session expired"))
                    }
                }

                else -> AppResult.Err(errorFrom(res, rawBody))
            }
        }
    }

    /**
     * Body-less POSTs (read/spoken receipts, logout without a refresh token) are
     * sent with no body at all: an empty body plus a JSON content type is
     * rejected by some servers, and there is nothing to encode.
     */
    private fun emptyBody(method: String): RequestBody? = null

    private fun errorFrom(response: Response, rawBody: String?): AppError {
        val detail = rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ApiErrorBody.serializer(), it).error }.getOrNull() }
        val message = detail?.message ?: "Request failed (${response.code})"
        val kind = when (response.code) {
            400, 422 -> AppErrorKind.BAD_REQUEST
            403 -> AppErrorKind.FORBIDDEN
            404 -> AppErrorKind.NOT_FOUND
            409 -> AppErrorKind.CONFLICT
            429 -> AppErrorKind.RATE_LIMITED
            in 500..599 -> AppErrorKind.SERVER
            else -> AppErrorKind.UNKNOWN
        }
        return AppError(kind, message, httpStatus = response.code)
    }

    /** Single-flight refresh so a burst of 401s results in one refresh call. */
    private suspend fun refreshTokens(): Boolean = refreshMutex.withLock {
        val refreshToken = secrets.refreshToken() ?: return@withLock false
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        withContext(dispatchers.io) {
            val refreshRequest = Request.Builder()
                .url(baseUrl + "/api/${ProductConfig.DEFAULT_API_VERSION}/auth/refresh")
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Accept", "application/json")
                .build()
            runCatching {
                http.newCall(refreshRequest).execute().use { res ->
                    val raw = res.body?.string()
                    if (!res.isSuccessful || raw.isNullOrBlank()) {
                        false
                    } else {
                        val tokens = json.decodeFromString(TokensDto.serializer(), raw)
                        secrets.putAccessToken(tokens.accessToken)
                        secrets.putRefreshToken(tokens.refreshToken)
                        true
                    }
                }
            }.getOrElse {
                AppLog.w("Api", "refresh_failed", it)
                false
            }
        }
    }

    private fun storeTokens(result: AppResult<AuthResponse>) {
        if (result is AppResult.Ok) {
            secrets.putAccessToken(result.value.tokens.accessToken)
            secrets.putRefreshToken(result.value.tokens.refreshToken)
            secrets.putUserId(result.value.user.id)
        }
    }

    /** Keeps message ids out of diagnostics while still allowing correlation. */
    private fun sanitizePath(path: String): String =
        path.replace(Regex("/[0-9a-fA-F-]{8,}"), "/{id}")

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
