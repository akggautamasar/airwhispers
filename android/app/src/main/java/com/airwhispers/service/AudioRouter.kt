package com.airwhispers.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.airwhispers.core.AppLog
import com.airwhispers.data.model.SpeechOutput

/**
 * Audio behaviour for spoken messages.
 *
 * Design rules (see docs/android-limitations.md):
 *  - We never touch `AudioManager.mode`, never call `setCommunicationDevice`, and
 *    never flip the speakerphone: all three would reroute *the third-party call*
 *    the user is actually on. Spoken messages follow the normal media route.
 *  - We request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` for a single utterance and
 *    release it right after, so an external call is only momentarily ducked, not
 *    interrupted.
 *  - If a Bluetooth headset is connected, the media route already prefers it;
 *    "prefer Bluetooth" therefore means "report what the system will do", not
 *    "hijack the route".
 */
class AudioRouter(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null

    /**
     * @return true when focus was granted (playback is still attempted when it is
     *         not — a spoken message must not be silently lost).
     */
    fun requestFocus(): Boolean {
        val attributes = attributesFor(SpeechOutput.SYSTEM_DEFAULT)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                AppLog.d("Audio", "focus_change", "change" to change)
            }
            .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) AppLog.w("Audio", "focus_denied")
        return granted
    }

    fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Attributes for the device engine. `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH` is
     * the combination Android routes predictably (Bluetooth A2DP/LE when
     * connected, loudspeaker otherwise) without disturbing communication audio.
     */
    fun attributesFor(output: SpeechOutput): AudioAttributes = when (output) {
        SpeechOutput.PHONE_SPEAKER, SpeechOutput.PREFER_BLUETOOTH, SpeechOutput.SYSTEM_DEFAULT ->
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
    }

    /** Output devices currently capable of playing our speech, best first. */
    fun outputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun bluetoothOutput(): AudioDeviceInfo? =
        outputDevices().firstOrNull { it.type in BLUETOOTH_TYPES && it.isSink }

    fun wiredOutput(): AudioDeviceInfo? =
        outputDevices().firstOrNull { it.type in WIRED_TYPES && it.isSink }

    fun hasHeadset(): Boolean = bluetoothOutput() != null || wiredOutput() != null

    /** Human readable description used in the Call Assist panel and in logs. */
    fun currentRouteDescription(): String {
        bluetoothOutput()?.let { return "Bluetooth (${it.productName})" }
        wiredOutput()?.let { return "Wired headset (${it.productName})" }
        return "Phone speaker"
    }

    /**
     * Whether the phone is currently in *any* call-like audio mode.
     * Public API, no permissions, and — importantly — we never set it ourselves.
     */
    fun audioMode(): Int = audioManager.mode

    /**
     * Microphone currently open by another app. A strong hint that an app like
     * WhatsApp or Meet has an active voice/video session. Returns false for our
     * own process (we never record).
     */
    fun microphoneInUseByOtherApp(): Boolean = runCatching {
        val configurations = audioManager.activeRecordingConfigurations
        if (configurations.isEmpty()) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A silenced client is not sending audio anywhere, so it is not a call.
            configurations.any { !it.isClientSilenced }
        } else {
            // AudioRecordingConfiguration.isClientSilenced only exists from API 29.
            // Below that, presence in this list already means "someone is recording".
            true
        }
    }.getOrDefault(false)

    private companion object {
        val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
    }
}
