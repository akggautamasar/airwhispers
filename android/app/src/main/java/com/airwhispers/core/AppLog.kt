package com.airwhispers.core

import android.util.Log
import java.security.MessageDigest

/**
 * Structured logging with a strict no-secrets policy.
 *
 * Message bodies and tokens must never reach logcat: only ids, counts and
 * lengths are logged. [redact] exists to make that easy to get right.
 */
object AppLog {
    private const val DEFAULT_TAG = "AirWhispers"

    fun d(area: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!Log.isLoggable(DEFAULT_TAG, Log.DEBUG)) return
        Log.d(DEFAULT_TAG, format(area, event, fields))
    }

    fun i(area: String, event: String, vararg fields: Pair<String, Any?>) =
        Log.i(DEFAULT_TAG, format(area, event, fields))

    fun w(area: String, event: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) =
        Log.w(DEFAULT_TAG, format(area, event, fields), t)

    fun e(area: String, event: String, t: Throwable? = null, vararg fields: Pair<String, Any?>) =
        Log.e(DEFAULT_TAG, format(area, event, fields), t)

    /** Fingerprints an id so support tickets can correlate without leaking it. */
    fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Never log raw text. Length + shape is enough for diagnostics. */
    fun describeText(text: String?): String =
        if (text == null) "null" else "chars=${text.length}"

    private fun format(area: String, event: String, fields: Array<out Pair<String, Any?>>): String {
        if (fields.isEmpty()) return "[$area] $event"
        val rendered = fields.joinToString(" ") { (k, v) -> "$k=${v ?: "null"}" }
        return "[$area] $event $rendered"
    }
}
