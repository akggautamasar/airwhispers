package com.airwhispers.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.airwhispers.core.AppLog
import com.airwhispers.core.DefaultDispatchers
import com.airwhispers.core.DispatcherProvider
import com.airwhispers.core.TimeSource
import com.airwhispers.data.model.CallDetectionCapability
import com.airwhispers.data.model.CallDetectionSource
import com.airwhispers.data.model.CallState
import com.airwhispers.data.model.CallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Detects whether the user is in *any* call — ours is not the point; the call is
 * happening in WhatsApp, Telegram, Meet, Discord, Signal, or the dialer.
 *
 * Signals used (all public, all permission-light, none invasive):
 *  1. Telephony call state via `TelephonyCallback`/`PhoneStateListener`
 *     (needs READ_PHONE_STATE; optional, user can decline).
 *  2. `AudioManager.getMode()` — `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` are set
 *     by the *other* app when it is in a call. We never set the mode ourselves.
 *  3. `AudioManager.getActiveRecordingConfigurations()` — the microphone being
 *     open by another app indicates an active VoIP/video session.
 *
 * No accessibility service, no notification scraping, no microphone access, no
 * private APIs. When none of these signals is available the product falls back to
 * the manual toggle (see [manualOverride]).
 */
class CallDetector(
    private val context: Context,
    private val audioRouter: AudioRouter,
    private val dispatchers: DispatcherProvider = DefaultDispatchers,
    private val time: TimeSource = TimeSource.SYSTEM,
) {

    private val _status = MutableStateFlow(CallStatus())
    val status: StateFlow<CallStatus> = _status.asStateFlow()

    private val _capability = MutableStateFlow(computeCapability())
    val capability: StateFlow<CallDetectionCapability> = _capability.asStateFlow()

    private var pollJob: Job? = null
    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    @Volatile
    private var manualOverride: Boolean = false

    @Volatile
    private var telephonyState: CallState = CallState.IDLE

    private var consecutiveIdleReadings = 0

    /** How often the cheap audio-mode probe runs while Call Assist is armed. */
    var pollIntervalMs: Long = 3_000L

    fun refreshCapability() {
        _capability.value = computeCapability()
    }

    fun start(scope: CoroutineScope) {
        registerTelephony()
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(dispatchers.default) {
            while (isActive) {
                evaluate()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop(scope: CoroutineScope? = null) {
        pollJob?.cancel()
        pollJob = null
        unregisterTelephony()
        // (scope parameter kept for symmetry with start())
        scope?.let { }
    }

    /** Manual "I am in a call" switch — the always-available fallback. */
    fun setManualOverride(inCall: Boolean) {
        manualOverride = inCall
        apply(
            if (inCall) CallState.IN_CALL else CallState.IDLE,
            if (inCall) CallDetectionSource.MANUAL else CallDetectionSource.NONE,
        )
        AppLog.i("CallDetector", "manual_override", "active" to inCall)
    }

    fun isManualOverride(): Boolean = manualOverride

    /** One detection pass; also used by tests and by the service on demand. */
    fun evaluate() {
        if (manualOverride) return

        val mode = audioRouter.audioMode()
        val audioSaysCall = mode == android.media.AudioManager.MODE_IN_CALL
        val audioSaysCommunication = mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val micInUse = audioRouter.microphoneInUseByOtherApp()

        val (state, source) = when {
            telephonyState == CallState.IN_CALL || telephonyState == CallState.RINGING ->
                telephonyState to CallDetectionSource.TELEPHONY
            audioSaysCall -> CallState.IN_CALL to CallDetectionSource.AUDIO_MODE
            audioSaysCommunication -> CallState.IN_COMMUNICATION to CallDetectionSource.AUDIO_MODE
            micInUse -> CallState.IN_COMMUNICATION to CallDetectionSource.MICROPHONE_IN_USE
            else -> CallState.IDLE to CallDetectionSource.NONE
        }

        // Hysteresis: leaving a call requires two consecutive idle readings, so a
        // VoIP app momentarily releasing the mic does not flap the state.
        if (state == CallState.IDLE) {
            consecutiveIdleReadings++
            if (_status.value.inProgress && consecutiveIdleReadings < IDLE_CONFIRMATIONS) return
        } else {
            consecutiveIdleReadings = 0
        }
        apply(state, source)
    }

    private fun apply(state: CallState, source: CallDetectionSource) {
        val current = _status.value
        if (current.state == state && current.source == source) return
        val next = CallStatus(state = state, source = source, changedAt = time.nowMillis())
        _status.value = next
        AppLog.i(
            "CallDetector",
            "call_state_changed",
            "state" to state,
            "source" to source,
            "route" to audioRouter.currentRouteDescription(),
        )
    }

    // ------------------------------------------------------------------ telephony

    private fun registerTelephony() {
        if (!hasPhonePermission()) return
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        telephonyState = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
                            else -> CallState.IDLE
                        }
                        evaluate()
                    }
                }
                manager.registerTelephonyCallback(Executor { it.run() }, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        telephonyState = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
                            else -> CallState.IDLE
                        }
                        evaluate()
                    }
                }
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        }.onFailure { AppLog.w("CallDetector", "telephony_register_failed", it) }
    }

    private fun unregisterTelephony() {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let { manager.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { manager.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        phoneStateListener = null
        telephonyState = CallState.IDLE
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun computeCapability(): CallDetectionCapability {
        val telephony = hasPhonePermission()
        val notes = buildList {
            if (!telephony) {
                add("Phone-call detection is off (READ_PHONE_STATE not granted). Call Assist can still run manually.")
            }
            add("VoIP apps (WhatsApp, Meet, Discord, Signal…) are recognised through the system audio mode and microphone usage, which is a documented heuristic — some apps and some device manufacturers behave differently.")
            when {
                audioRouter.hasHeadset() -> add("Audio will route to ${audioRouter.currentRouteDescription()}.")
                else -> add("Audio will play through the phone speaker.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add("Android ${Build.VERSION.RELEASE}: foreground service is user-visible and must be started by you.")
            }
        }
        return CallDetectionCapability(
            telephonyReadable = telephony,
            audioModeReadable = true,
            microphoneUsageReadable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            bluetoothRoutingInspectable = true,
            notes = notes,
        )
    }

    private companion object {
        const val IDLE_CONFIRMATIONS = 2
    }
}
