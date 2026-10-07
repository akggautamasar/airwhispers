# Android limitations (and what AirWhispers does instead)

Every claim in this document was checked against the Android platform documentation and the
behaviour of Android 8–15. Where something can only be confirmed on hardware, it is marked
**[device test]** — the repository never pretends a device behaviour is verified when it is
not. Anything marked *not attempted* means the app deliberately refuses to do it.

## 1. "Read my WhatsApp messages aloud during a WhatsApp call"

**Partially possible, and the boundary is worth stating precisely.**

* AirWhispers can speak **its own** messages to you while you are on a call in another app.
  This works because the device has a single media output path and a TTS engine that mixes
  into it.
* AirWhispers **cannot** read WhatsApp/Telegram/Signal messages. Those apps' notification
  text is not available to us without a notification-listener permission, and reading other
  apps' content is a privacy violation the project rules out.
* Practical consequence: your partner needs to use AirWhispers (or forward the message to
  it). This is the honest limit of the premise, not an implementation gap.

## 2. Automatic detection of third-party calls

| Situation | Detected automatically? | Signal |
| --- | --- | --- |
| Cellular call (dialer) | Yes | `TelephonyCallback.CallStateListener` (API 31+) / legacy listener below |
| WhatsApp / Telegram / Signal / Discord / Meet voice or video call | Usually | `AudioManager.getMode()` → `MODE_IN_COMMUNICATION`, microphone in use |
| An app holding `MODE_IN_CALL` | Usually | `AudioManager.getMode()` → `MODE_IN_CALL` |
| Push-to-talk, muted call, or an app that never sets the audio mode | **No** | No public API exposes another app's call state |
| A call confined to another audio device (e.g. some OEM "app audio" paths) | Sometimes | Microphone-in-use signal may still fire |

Consequences designed into the product:

* The gate is *capability-based*: `CallDetectionCapability` tells the UI which signals exist,
  and `automaticDetectionAvailable` is false when none do.
* The **manual fallback** is first-class: a button, a quick-settings tile, and a notification
  action all arm or disarm Call Assist regardless of detection.
* The UI names its evidence ("Voice chat likely — microphone in use") instead of claiming
  certainty.
* Reading other apps' notifications or screens to infer calls is **not attempted**.

## 3. Audio injection into the other party's audio stream

**Not attempted, and impossible without system privileges.** Routing our TTS into a VoIP
call's uplink requires controlling that app's audio session: on a normal Android device this
is not available (rooted devices or a system-signed app with privileged capture could, but
that is out of scope and would be dishonest to promise). The remote participant can never
hear the spoken message — only the local user does.

## 4. Ducking and focused audio

* `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is requested per utterance. During speech the call
  audio usually ducks. **[device test]** How much it ducks — and whether a Bluetooth call
  ducks the media path or leaves it at a low fixed volume — varies by device and by headset
  firmware. Nothing here is a guarantee about perceived loudness.
* Apps that specify `AudioAttributes` with `setWillPauseWhenDucked(true)` will pause their
  audio instead of ducking it; that is their choice and we cannot override it.
* AirWhispers never calls `setSpeakerphoneOn`, `setMode`, `setCommunicationDevice`,
  `startBluetoothSco` or `setBluetoothScoOn`. Those are precisely the calls that could reroute
  the user's live call.

## 5. Bluetooth routing

* Speech uses `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, which the system routes to A2DP/LE
  headset/hearing aid, wired headset, then speaker — in the system's own priority order.
* **Not attempted**: forcing a specific output device. The only APIs that can force routing
  are communication-device APIs, which can move the ongoing call's audio; forcing the phone
  speaker while the user is wearing earbuds is both rude and technically risky.
* The "prefer Bluetooth" setting therefore means: do not fight the system's media routing,
  and report which route is in use. The UI says this in words rather than implying control.
* **[device test]** Some headsets mix media and call streams; some mute media during a call.
  Per-device behaviour is on the testing matrix.

## 6. Foreground service rules

| Android | Rule | Our behaviour |
| --- | --- | --- |
| 8.0+ (26) | Background services are stopped; a notification is required to stay foreground | The armed session is a foreground service with an ongoing notification |
| 9–10 (28–29) | `FOREGROUND_SERVICE` permission required | Declared |
| 11 (30) / 12 (31) | Cannot start a foreground service from the background (`ForegroundServiceStartNotAllowedException`) | The service starts from a user action only: tapping Start, the tile, or a notification action |
| 13 (33) | `POST_NOTIFICATIONS` is a runtime permission | Requested once; speech still works if denied, the user just loses the status/controls |
| 14 (34) | FGS **types** are mandatory and audited; `specialUse` needs a subtype property | Declared `mediaPlayback|specialUse` with `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |
| 15 (35) | `dataSync` FGS capped at 6 h/24 h, `onTimeout` fires, and `dataSync`/`mediaPlayback`/`phoneCall` cannot start from `BOOT_COMPLETED` | We never use `dataSync`; the boot receiver only posts a notification |
| All | A user force-stop disables the app until the user opens it again | Detected and surfaced as a "Call Assist is off" state, not silently swallowed |

The armed session also self-terminates after three idle hours so an accidentally armed
device does not hold a foreground service (and a battery bar) forever.

## 7. Doze, app standby, and OEM battery managers

* Doze does not stop a foreground service, but it does throttle background work; since our
  delivery path is push/socket driven and the session is user-visible, Doze is not a
  correctness problem.
* **Samsung (One UI)**: aggressive process killing and a "Sleeping apps" list can end the
  session anyway, even with a foreground service. Mitigations: the in-app battery-optimisation
  prompt, the ongoing notification the user can notice disappearing, and a boot/relaunch
  notification that re-arms with one tap. **[device test]**
* **Xiaomi/Huawei/Oppo/vivo**: "Autostart"/"Protected apps" settings are outside the scope of
  what an app can request programmatically. The UI links to the battery settings screen and
  explains the symptom ("Call Assist stopped while the screen was off").
* Never attempted: `bindService` tricks, `JobScheduler` abuse, alarms every minute, or
  `WakeLock` holding to defeat the platform's power management.

## 8. Detecting the microphone-in-use heuristic

`AudioManager.getActiveRecordingConfigurations()` is a public API and reports *that* some
client is recording, not which app or why. Consequences:

* A voice memo, a recorder app, or a video recording by any app looks like a call.
* A VoIP app that mutes its microphone may stop looking like a call.
* The state is therefore treated as **supporting evidence** with hysteresis (two consecutive
  readings) rather than ground truth, and the user can always correct it manually.

## 9. Notification behaviour

* On Android 13+, notifications are optional. If the permission is denied, Call Assist still
  works but the user loses the pause/skip controls and the ongoing status. The app says so
  rather than pretending the feature is broken.
* Some OEM ROMs silently suppress ongoing notifications of sideloaded apps before the app is
  "trusted"; the status screen is the fallback place to see the truth.
* Notification actions call `startForegroundService`/`startService` on our own component
  only — no cross-app intents, nothing exported for third parties to abuse (the tile service
  is the single exported component, and it only toggles the local service).

## 10. Things the app will never do — by design

1. Record, store, transcribe, or upload call audio (it has no `RECORD_AUDIO` permission).
2. Join or observe another app's call session, or read other apps' notifications/screens.
3. Use accessibility services, `QUERY_ALL_PACKAGES`, or hidden/private APIs.
4. Root-based or ADB-based tricks, or asking the user to "grant permissions via ADB".
5. Inject audio into the remote side of a call.
6. Claim universal call detection, or hide the fact that detection is a heuristic.

## 11. Known issues / not-yet-implemented in v1

| Item | Status |
| --- | --- |
| `fcm` flavor delivery when the app was force-stopped by the user | Not possible (platform rule); the app explains it |
| Group conversations | Backend schema is additive-ready, client UI is 1-to-1 only |
| Media/image messages | Not implemented; the TTS pipeline ignores non-text by design |
| Whisper mode | Implemented as reduced TTS volume (0.45); true inaudible-to-others whispering is not attempted |
| Voice replies / speech-to-text | Designed for, not implemented in v1 (would need the microphone permission) |
| Smart importance classification | Deterministic rules only (priority flag + emoji/word heuristics), no ML in v1 |
| iOS | Same protocol, no app build yet — see [ios.md](ios.md) |
| Bluetooth SCO call ducking behaviour | **[device test]** pending hardware |
| Samsung/Huawei session survival | **[device test]** pending hardware |
