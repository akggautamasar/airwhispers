package com.airwhispers.core

/**
 * Domain-level result type. Deliberately small: the UI only ever needs to know
 * whether something worked, whether it is retryable, and whether the session
 * has to be re-established.
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
}

enum class AppErrorKind {
    NETWORK,
    SERVER,
    UNAUTHORIZED,
    FORBIDDEN,
    CONFLICT,
    RATE_LIMITED,
    NOT_FOUND,
    BAD_REQUEST,
    UNSUPPORTED,
    STORAGE,
    SPEECH_UNAVAILABLE,
    UNKNOWN,
}

data class AppError(
    val kind: AppErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = kind in setOf(
        AppErrorKind.NETWORK,
        AppErrorKind.SERVER,
        AppErrorKind.RATE_LIMITED,
        AppErrorKind.UNKNOWN,
    ),
    val cause: Throwable? = null,
) {
    companion object {
        fun network(message: String = "Network unavailable", cause: Throwable? = null) =
            AppError(AppErrorKind.NETWORK, message, cause = cause)

        fun server(message: String = "Server error", status: Int? = null, cause: Throwable? = null) =
            AppError(AppErrorKind.SERVER, message, httpStatus = status, cause = cause)

        fun unauthorized(message: String = "Session expired") =
            AppError(AppErrorKind.UNAUTHORIZED, message, httpStatus = 401)

        fun unknown(message: String = "Unexpected error", cause: Throwable? = null) =
            AppError(AppErrorKind.UNKNOWN, message, cause = cause)
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Ok) action(value)
}

inline fun <T> AppResult<T>.onError(action: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Err) action(error)
}
