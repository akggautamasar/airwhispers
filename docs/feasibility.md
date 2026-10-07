# Feasibility report — AirWhispers / Call Assist

This is the analysis required before writing code: what Android actually allows, where the
platform says no, and which fallback the product ships instead. Everything here was
verified against the documented behaviour of Android 8 (API 26) through Android 15 (API 35),
and the parts that can only be confirmed on hardware are marked **[device test]**.

---

## A. Feasibility summary

| # | Question | Verdict | What we ship |
| --- | --- | --- | --- |
| 1 | Receive a message in the background | **Feasible** | Cloud push (FCM flavor) and/or an app-owned WebSocket held while Call Assist is armed |
| 2 | Speak aloud while another app is in the foreground | **Feasible** | Foreground service + device `TextToSpeech`, media audio attributes |
| 3 | Play speech during a third-party call | **Feasible, with caveats** | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, media route; the call is only momentarily ducked **[device test]** |
| 4 | Bluetooth routing | **Feasible** | System media routing already prefers A2DP/LE/BLE; we never force a device |
| 5 | Detect a call in another app | **Partially feasible (heuristic)** | Telephony callback + `AudioManager.getMode()` + mic-in-use, plus a **manual switch** |
| 6 | Android version compatibility | **Feasible** | `minSdk 26`, `targetSdk 35`, permission and FGS-type gating per API level |
| 7 | Battery / background restrictions | **Feasible with discipline** | Only an explicitly armed foreground service + push; no polling loops in the background |
| 8 | Notification behaviour during calls | **Feasible** | Notifications stay quieter than speech: a spoken message is a *silent* notification |
| 9 | Samsung-specific behaviour | **Caveats** | Aggressive process killing → battery-optimisation exemption, documented **[device test]** |
| 10 | Pixel-specific behaviour | **Cleanest** | Stock FGS rules apply; automatic detection behaves best here |

**The one thing that is not feasible, and is never attempted:** injecting our audio into
another application's call so that the *remote* participant hears it. There is no public
Android API for that, it would require a rooted device or a system-privileged app, and it
is explicitly out of scope. AirWhispers makes the **local user** hear the message.

---

## B. Final architecture

```
┌──────────────────────── Android device ────────────────────────┐
│  MainActivity / Compose UI ─── AppViewModel ── AirWhispersRepository
│                                          │              │
│       SQLite (messages, conversations, spoken-ledger, outbox)
│                                          │              │
│  CallAssistService (foreground, armed by the user)         │
│    ├── CallDetector  (telephony • audio mode • mic-in-use) │
│    ├── SpeechPlayer  (TtsQueue → AndroidTtsSynthesizer)    │
│    ├── AudioRouter   (focus, route reporting, never reroute)│
│    └── Notifier      (ongoing status + silent message notes)│
│  RealtimeClient (WebSocket) ── optional FCM (fcm flavor)   │
└────────────────────────────────────────────────────────────┘
                │ HTTPS REST  +  WSS realtime
┌───────────────▼────────────────────────────────────────────┐
│ Node + TypeScript (Fastify)                                │
│  auth (scrypt + JWT + rotating refresh) · messaging        │
│  WebSocket hub (auth frames) · FCM HTTP v1 push (optional)  │
│  Store: PostgreSQL (production) | in-memory (dev/tests)     │
└────────────────────────────────────────────────────────────┘
```

Rationale and the full data flow: [architecture.md](architecture.md).

## C. Technology choices

| Layer | Choice | Why |
| --- | --- | --- |
| App language / UI | Kotlin 2.0, Jetpack Compose, Material 3 | Modern, testable, no XML sprawl; dark-first palette for night calls |
| Min / target SDK | 26 / 35 | Adaptive icons, `AudioManager.getActiveRecordingConfigurations` (API 24+), FGS types (API 34+) |
| Speech | Device `android.speech.tts` | Offline, private, lowest latency, free; pluggable behind `SpeechSynthesizer` for a cloud engine later |
| Local storage | Hand-written SQLite | Tiny schema; the message table doubles as the durable *spoken ledger* |
| Secrets | Android Keystore (AES/GCM) | Tokens never in plain SharedPreferences |
| Transport | REST + WebSocket (OkHttp) | REST for durability, socket for latency; both already production-grade |
| Backend | Node 22, TypeScript, Fastify, PostgreSQL | Small dependency surface (3 runtime deps), typed contracts, trivially containerised |
| Push | FCM HTTP v1 (optional) | Wakes a killed app; implemented without the Google SDK |
| CI/CD | GitHub Actions | Builds, tests and publishes the signed APK |

Deliberately **not** chosen: Firebase Realtime Database/Firestore for messaging (privacy and
lock-in), cloud TTS (privacy/latency/offline), Room (adds a code generator for a 4-table
schema), a DI framework (one module), Firebase for the default flavor (needs an account).

## D. Database schema

Seven tables, defined in [`backend/sql/schema.sql`](../backend/sql/schema.sql):
`users` (one row per device identity, keyed by its random code), `device_credentials`
(scrypt-hashed device secret — the reason there is no login), `conversations`,
`conversation_members`, `messages`, `trusts` (speech consent, owned by the listener),
`devices` (push targets).

There is deliberately no `user_settings` table: speech settings describe *this phone's*
voice and output, so they live only in the app, and the server stores exactly one social
fact per pair.

Key decisions:

* `messages.UNIQUE (sender_id, client_message_id)` — the server-side half of exactly-once
  delivery; a retried send returns the existing row instead of creating a duplicate.
* `timestamps are epoch milliseconds (bigint)` — one unambiguous representation shared by
  the API and the Android client.
* `conversations.pair_key` — a normalised `a:b` key with a unique index makes 1-to-1
  conversation creation race-free; the column is `NULL`-able so group chat can be added
  without migration pain.
* `messages.text` is isolated behind the store interface: replacing plaintext with
  ciphertext (E2EE) touches one column and the client crypto layer, not the API shape.
* `spoken_at` / `delivered_at` / `read_at` are separate: "spoken aloud" is a product-level
  event that is *not* the same as "read".

## E. API specification

Versioned under `/api/v1`, documented in [api.md](api.md). Identity: `POST /device/register`
→ a 6-character code + HS256 access token + device secret; `POST /device/token` exchanges the
secret for a fresh token (silent, forever), `POST /device/forget` revokes it. Messaging:
`GET/POST /conversations[/:id/messages]` with `PATCH /conversations/:id/trust` for per-peer
speech consent, `POST /messages/:id/{read,spoken}` receipts, device registration for push,
`/healthz`.
Realtime: `WSS /api/v1/realtime` with an explicit `auth` frame — the token never travels in
a URL query string where proxies and logs could capture it.

## F. Android project structure

`android/app/src/main/java/com/airwhispers/`:

```
config/   ProductConfig        branding + feature flags (rename the app in one file)
core/     AppLog AppResult Dispatchers
data/     model/  remote/ (ApiClient, RealtimeClient, Dto)  local/ (LocalStore)  prefs/ (SecretStore, SettingsStore)
domain/   tts/ (TextNormalizer, TtsQueue, SpeechSynthesizer)  assist/ (CallAssistEngine, SpokenLedger)
service/  CallAssistService, SpeechPlayer, AndroidTtsSynthesizer, AudioRouter, CallDetector, Notifier, TileService
ui/       MainActivity, AppViewModel, theme/, components/, screens/
push/     PushBridge (backend-agnostic boundary; FCM lives in the `fcm` flavor source set)
```

Rules that keep it healthy: the `domain/` layer is pure Kotlin (fully unit-testable),
Android types never leak into decision logic, and every platform affordance sits behind an
interface with a fake for tests.

## G. Security model

See [security.md](security.md). In short: TLS in production, 256-bit device secrets hashed
with scrypt (there is no password to guess), short-lived HS256 access tokens minted silently
from that secret, server-side authorization on every conversation and message (membership
checks, recipient-only receipts, consent owned by the listener), default-deny speech,
per-scope rate limiting, no secrets or message bodies in logs, secret material encrypted with
a non-exportable Android Keystore key, and an explicit migration path to E2EE.

## H. MVP implementation plan (all phases shipped in this repo)

| Phase | Deliverable | Status |
| --- | --- | --- |
| 1 | Loginless identity (device code + device secret, silent re-auth, secure storage) | ✅ |
| 2 | 1-to-1 messaging opened by code (REST + local cache + outbox + retry) | ✅ |
| 3 | Realtime delivery (WebSocket) + optional FCM push | ✅ |
| 4 | Local TTS (device engine, settings, test voice) | ✅ |
| 5 | Call Assist (foreground service, rules, notification controls) | ✅ |
| 6 | Sequential speech queue (pause/resume/skip/stop/clear, priorities) | ✅ |
| 7 | Per-peer speech consent (listener-owned, default deny) | ✅ |
| 8 | Audio behaviour (focus, Bluetooth awareness, never reroute a call) | ✅ |
| 9 | Polish + testing (unit tests, API tests, smoke test, docs) | ✅ |
| 10 | Security hardening (keystore, rate limits, authz tests, log hygiene) | ✅ |

---

# Detailed findings

## 1. Background message reception

| Mechanism | Works when app is… | Verdict |
| --- | --- | --- |
| WebSocket held by a foreground service | alive, armed, screen off | **Primary for the standalone flavor.** One socket, capped backoff, opened only while the user arms Call Assist |
| FCM data message (`high` priority) | killed / force-stopped-by-system | **Primary for the fcm flavor.** Delivered to `FirebaseMessagingService`; a high-priority push may start an FGS (the documented exception used by alarm/calling apps) |
| FCM + doze | dozing, screen off | High-priority messages are exempt from Doze; not from a *user* force-stop |
| `WorkManager` periodic | any | Minimum 15-minute latency — useless for a chat, so it is **not** used for delivery |

Consequence we state openly: in the standalone flavor, if the app was killed and Call Assist
was not armed, a message arrives as a normal notification rather than being spoken. The fcm
flavor closes that gap. Both are documented in the README.

## 2. TTS while another app is in the foreground

`TextToSpeech` runs in the *local* process and mixes into the normal media path, so another
app being in the foreground is irrelevant. What matters:

* **Process lifetime.** A background process can be killed at any moment, so speech is
  served from an explicit foreground service with an ongoing notification — this is both a
  technical requirement and a transparency feature ("Call Assist is on").
* **Foreground service type.** `mediaPlayback` (we play short spoken audio) + `specialUse`
  (the user-visible relay session), *not* `dataSync`: Android 15 caps `dataSync` at six
  hours per day and would kill the session mid-use.
* **Init latency.** The engine is created once and re-used; a freshly started engine can
  take seconds to answer — unacceptable mid-conversation. `AndroidTtsSynthesizer` waits for
  the init callback with a 5 s timeout and re-prepares after an error, retrying once.
* **Utterance completion.** `UtteranceProgressListener` gives exact `onDone`/`onError`/`onStop`,
  so the queue advances deterministically instead of guessing with delays.

## 3. Audio focus during a third-party call

Facts that shaped the implementation:

* Another app in a call holds `AUDIOFOCUS_GAIN_TRANSIENT` / `MODE_IN_COMMUNICATION`.
  Requesting `AUDIOFOCUS_GAIN` would steal the call's focus — never do that.
* We request **`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`** for the length of a single utterance
  and abandon it immediately. Other audio (including call audio) may duck while we speak,
  which is exactly the "least disruptive" behaviour we want.
* If focus is denied we still speak: a message the user is waiting for matters more than
  perfect etiquette, and the outcome is logged.
* We never call `AudioManager.setMode`, `setCommunicationDevice`, `setSpeakerphoneOn` or
  `startBluetoothSco`. Each of these can reroute **the call the user is on** — the single
  most harmful thing this app could do.

**[device test]** On some devices an active Bluetooth SCO call keeps the media route on the
headset at a low volume; verifying perceived loudness per device is in the device matrix.

## 4. Bluetooth audio routing

* Audio attributes `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH` route to A2DP / BLE headset /
  hearing aid when connected, otherwise the loudspeaker — no work needed from us.
* We *report* the route (`AudioRouter.currentRouteDescription()`) and prefer to state the
  truth ("Bluetooth (Pixel Buds)") instead of pretending to force it.
* "Prefer Bluetooth" is therefore a *preference we honour by not fighting the system*, and
  the settings screen says so in plain language.
* Forcing the loudspeaker while earbuds are connected is deliberately **not** implemented:
  on API 31+ the only APIs that would do it are communication-device APIs, which can change
  the routing of the ongoing third-party call.

## 5. Detecting active calls from other applications

| Signal | API | Reliability | Notes |
| --- | --- | --- | --- |
| Telephony call state | `TelephonyCallback.CallStateListener` (31+) / `PhoneStateListener` | Very high | Covers normal calls; needs `READ_PHONE_STATE` (optional, and only for this) |
| System audio mode | `AudioManager.getMode()` | High | `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` are set by the *other* app; we never set them |
| Microphone in use | `AudioManager.getActiveRecordingConfigurations()` | Medium | Public API; indicates an active VoIP/video session; can false-positive on recording apps |
| `TelecomManager` | `getSelfManagedCallingAccounts` etc. | Not usable | Only exposes *our* calls, not another app's |
| `CallScreeningService` | — | Not usable | Only for incoming telephony, and requires being the user's chosen screening app |
| Notification listener / accessibility | — | **Refused** | Reading other apps' notifications or UIs to infer calls is exactly the privacy-violating behaviour this project rules out |

Implementation: fuse the signals in `CallDetector`, apply two-reading hysteresis before
leaving a call state (VoIP apps release the audio mode momentarily), and always expose the
manual toggle. The UI names the signal it detected ("Voice chat likely — microphone in use")
rather than claiming certainty.

## 6. Android version compatibility

| Version | Behaviour that matters | Handling |
| --- | --- | --- |
| 8.0 (26, minSdk) | Notification channels, background service limits | Channels created up-front; FGS for the armed session |
| 10–11 (29–30) | FGS types introduced, background start restrictions | `mediaPlayback` type, foreground-only start |
| 12 (31) | FGS cannot start from the background; `POST_NOTIFICATIONS`-era permission checks; `TelephonyCallback` | Manual start (button, tile, notification action); runtime permission request |
| 13 (33) | `POST_NOTIFICATIONS` runtime permission | Requested once, non-blocking: speech works without it |
| 14 (34) | Mandatory FGS types + `specialUse` subtype declaration | Declared `mediaPlayback|specialUse` with a subtype property; `READ_PHONE_STATE` untouched by new restrictions |
| 15 (35) | 6-hour `dataSync` cap, BOOT_COMPLETED FGS restrictions | We do not use `dataSync`; the boot receiver only posts a notification that starts the service on tap |

## 7. Battery and background execution

* No polling of the server, ever: delivery is push- or socket-driven; the REST client is
  call-driven.
* Call detection polls `AudioManager` every 3 s **only while the service is armed**. That is
  a few microseconds of work per tick with no wake lock; the alternative (registering for
  every audio-mode change) has no public API.
* The socket uses capped exponential backoff (1 s → 60 s, jittered) and is closed the moment
  nothing needs it — the UI releasing it when the app goes to the background is explicit
  reference counting (`RelayRequirement`).
* An armed session that sees no call and speaks nothing for three hours stops itself and
  says so in a notification, instead of quietly draining the battery for days.
* Doze: an FGS holds the session, but there is no CPU-heavy loop, so Doze has little to
  interrupt.

## 8. Notification behaviour during calls

* A message that *is being spoken* gets a **silent** notification (big text, no sound, no
  vibration): the user is listening, and chirping twice is the classic failure of apps in
  this category.
* A message that is *not* spoken (Call Assist off, sender not allowed, nothing to say) gets a
  normal high-priority notification.
* The ongoing Call Assist notification shows live state ("Speaking: Partner", queue depth)
  and carries Pause / Skip / Stop actions, so the user can intervene without unlocking.
* The notification group is set so that a summary never re-alerts.

## 9. Samsung-specific behaviour

* One UI kills backgrounded processes more aggressively than AOSP, and its battery manager
  may silently drop the process even with an FGS.
* Mitigations shipped: an in-app prompt to exempt AirWhispers from battery optimisation
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), an ongoing notification the user can see
  was removed (a strong hint that the system killed the session), and the app re-arms from a
  notification tap rather than pretending it was never stopped.
* **[device test]** Samsung's "Sleeping apps" list can also suspend the app entirely; the
  testing doc includes a checklist for it.

## 10. Pixel-specific behaviour

* Stock Android applies the documented FGS rules with no OEM additions; automatic detection
  behaves most predictably here and is the reference for the device matrix.
* Pixel devices are the recommended test target for `TelephonyCallback` behaviour and for
  verifying that a high-priority FCM message can start the Call Assist service from the
  background.

---

## MVP definition of done — honest status

| Requirement | Status |
| --- | --- |
| Two devices, connected by code, no login anywhere | ✅ implemented and API-tested |
| A starts a WhatsApp/Telegram/dialer call | ✅ outside our app; detection heuristic + manual switch |
| A activates Call Assist if detection is unavailable | ✅ button, quick-settings tile, notification action |
| B sends a message; A receives it while our app is backgrounded | ✅ socket while armed; FCM push with the `fcm` flavor |
| The message enters the queue and is spoken through the right output | ✅ unit-tested queue + instrumented behaviour **[device test]** |
| A never has to open the app | ✅ service-owned pipeline |
| The external call continues normally | ✅ we never touch call audio; only transient ducking **[device test]** |
| Multiple messages queue correctly, no duplicates | ✅ queue + durable spoken ledger, unit-tested |
| Pause / stop / skip / clear | ✅ notification actions and in-app controls |
| Consent respected | ✅ default-deny, per-peer toggle, synced and enforced by the listener |
| Call Assist OFF stops all automatic speech | ✅ service teardown clears the queue and the queue's gate is the first check |

The remaining risk is *physical*: perceived loudness, routing and OEM aggressiveness on real
devices. That is why the device matrix in [testing.md](testing.md) exists and why nothing in
this repository claims otherwise.
