# Security and privacy

AirWhispers hears your messages out loud, so it must be unusually careful about *which*
messages, *where* the text goes, and *what* it leaves behind. This document is the honest
description of the current posture and the reasoning behind each decision.

## Threat model

| Adversary | We defend with | Residual risk |
| --- | --- | --- |
| Someone on the same Wi-Fi (passive eavesdropper) | TLS in production; cleartext allowed only for a private LAN development server | A development deployment on plain HTTP is readable — the app warns in the UI |
| Someone who steals the phone, unlocked app data | Tokens encrypted with a non-exportable Android Keystore key; `allowBackup=false`; message cache in app-private SQLite | Rooted device or a full-disk image can recover the local cache |
| Someone who steals the *server* database | Device secrets hashed with scrypt (per-credential salt); codes are the only identifiers; message bodies are the only plaintext, by design | Message text is readable by whoever runs the server — see the E2EE path below |
| A malicious API client | Per-route authorization (membership, recipient, listener owns consent), scrypt cost, rate limits, uniform error envelopes | No device attestation; a stolen device secret is usable until revoked (`/device/forget`) |
| A stranger who guesses a code | 6 characters from a 32-symbol alphabet (≈ 1.07 × 10⁹ codes), registration and chat-creation rate limits, and **default-deny speech**: guessing a code only lets someone *send* a message; it can never make your phone speak |
| A sender who wants audio to play | Consent lives on the listener's device (`trusts`); `priority: SPEAK_NOW` is a request, and Call Assist re-checks the local switch before speaking | — |
| Another app on the device | No exported components except the quick-settings tile and the notification actions, both of which only start/stop the local service; no content providers, no broadcast receivers that accept foreign input | A malicious app with `READ_LOGS` (root) could read logs — we never log message text |
| The speech engine vendor (if a cloud TTS were used) | We use the **device** TTS engine; text never leaves the phone for synthesis | A device vendor's TTS engine could upload in theory, which is why the engine is swappable behind `SpeechSynthesizer` |
| Google (when the `fcm` flavor is used) | Push payloads carry *no message text* unless the server operator explicitly enables `PUSH_INCLUDES_CONTENT` | Metadata (that a message arrived, sender, timestamp) passes through FCM |

Explicit non-goals: resisting a compromised *device* (rootkits, accessibility-based
attackers), resisting a compromised *server operator*, and hiding metadata from the platform.

## Identity: devices, not accounts

There are no passwords to protect because there are no passwords.

* **Code**: 6 characters from `abcdefghjkmnpqrstuvwxyz23456789` (no `0/o/1/l/i` because it is
  read aloud). Stored lowercase, compared case-insensitively, normalised from sloppy input
  (`K7M-2PQ` → `k7m2pq`). Uniqueness is enforced by the database; the server retries a
  collision a few times before answering `409`.
* **Device secret**: 32 random bytes (base64url), returned exactly once at registration and
  kept in the Android Keystore on the phone. The server stores only `scrypt$N$salt$key`
  (per-credential salt, constant-time verification). Because the secret is 256 bits of
  entropy rather than a human password, a modest scrypt cost (N=4096) is enough to make an
  offline attack on a leaked dump pointless — and it keeps silent re-authentication fast.
* **Access token**: JWT HS256, 1 hour, `typ: "access"`, subject = device identity, plus the
  `deviceId` claim. Verified on every request.
* **Silent re-authentication**: `POST /device/token` (or `/device/register` with the secret)
  exchanges the secret for a fresh token. A burst of 401s triggers exactly one refresh
  (single-flight mutex in `ApiClient`); if the secret itself is rejected, the app registers
  again and the user simply sees a new code — there is no login screen that could fail.
* **Rotation / revocation**: `POST /device/forget` deletes the credential; the old secret is
  dead immediately and the next registration mints a new code. There is no session family to
  replay, because there is no refresh-token chain.
* **Rate limits** (per IP or per device): register 60/min, token 120/min, forget 10/min,
  chat creation 60/min, sends 120/min, consent 60/min. Exceeding returns `429` with
  `retryAfterMs`.
* **Closing the door**: `ALLOW_NEW_DEVICES=false` makes the server reject *new* identities
  while existing devices keep resuming. Useful for a friends-and-family server.

## Token storage on the device

`SecretStore` keeps the access token, the device secret and the identity id in an
`EncryptedSharedPrefs` style blob: AES-256-GCM with a key generated inside the **Android
Keystore** and marked non-exportable. A fresh IV is generated per write and stored alongside the ciphertext; the
GCM tag means a tampered file fails to decrypt and is discarded rather than trusted.

Nothing sensitive is written to plain `SharedPreferences`, to the SQLite cache (which holds
message text and ids, but no credentials), or to logs.

## Transport

* Production deployments must terminate TLS. The app's `network_security_config` forbids
  cleartext **except** for the explicitly documented case of a development server on a
  private LAN, where the practical alternative is "no app at all". Release builds point at
  whatever URL the user enters on the onboarding screen; the app displays a warning when the
  URL is not `https://`.
* The WebSocket uses `wss://` for the same reason, and authenticates with a **first frame**
  rather than a query parameter, so access tokens do not end up in proxy access logs,
  browser history, or crash reports.
* Certificate pinning is deliberately not enabled in v1: self-hosted servers with Let's
  Encrypt renewals plus pinning is an operational footgun for a self-hosted product. It is
  straightforward to add later (`CertificatePinner` in `ApiClient`).

## Authorization (server side)

Every route re-derives the caller from the token and then checks the *resource*:

| Route | Check |
| --- | --- |
| `GET/POST /conversations/:id/messages` | Caller is a member of the conversation → else `403 NOT_A_MEMBER` |
| `POST /messages/:id/read`, `/spoken` | Caller is the message **recipient** → else `403 NOT_RECIPIENT` |
| `PATCH /conversations/:id/trust` | The caller is the **listener** (the peer whose phone would speak) |
| `GET/PATCH /me` | Scoped to the token subject |
| `GET /conversations` | Only conversations the caller is a member of |
| Everything else | Authenticated, and scoped to `sub` |

The tests assert these negatives explicitly (a stranger cannot read a conversation, cannot
mark someone else's message read, cannot change consent for a pair they are not part of, and
cannot grant themselves the right to be spoken to).

## Message content

* Message text is stored as plaintext in the database and in the app's SQLite cache. This is
  the one place where the product takes a deliberate, documented risk so that v1 could ship:
  search, receipts and the speech pipeline all work on plaintext.
* **Logs never contain message bodies.** The backend logs method, path, status, duration,
  request id — never bodies. The Android `AppLog` redacts by construction: it takes key/value
  pairs and callers pass ids, counts and enums, and `AppLog.fingerprint()` hashes ids when a
  correlation value is genuinely needed.
* Push payloads carry only ids, priority and sender display name by default
  (`PUSH_INCLUDES_CONTENT=false`); enabling it is an explicit operator decision documented in
  `backend/.env.example`.
* The app never uploads audio, never records the microphone (no `RECORD_AUDIO` permission is
  requested at all), and never reads other apps' notifications or screens.

## E2EE-ready design (not implemented in v1)

The message layer is shaped so that end-to-end encryption can be added without changing the
product surface:

1. `messages.text` becomes an envelope (`{"v":1,"alg":"x25519","ct":"…","nonce":"…"}`) rather
   than a string — one column, one migration.
2. The server never needs to see plaintext because it does not generate or read it: the
   server's only content-derived behaviour is *fan-out*, and the pipeline on the device
   (`CallAssistEngine`) already works on locally-decrypted text.
3. Key material belongs in the Android Keystore, alongside the existing device-secret
   encryption, and public keys would be published per device via the existing `/devices`
   route extended with a `publicKey` field — no identity system to retrofit, because
   identities are already per-device.
4. Push payloads already omit content, so notification text does not leak ciphertext metadata.

What is honestly **missing** for E2EE: per-device key exchange, multi-device sender keys,
group key agreement, and a security-review of the crypto. Implementing that badly is worse
than not implementing it, so v1 documents the path instead of shipping a hand-rolled protocol.

## The app's permissions, and why each one exists

| Permission | Why | What it cannot do |
| --- | --- | --- |
| `INTERNET` | Talk to the server | — |
| `ACCESS_NETWORK_STATE` | Choose "offline" vs "retry" instead of failing blindly | — |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the armed session and speak while another app is on screen | Nothing about the other app's audio |
| `POST_NOTIFICATIONS` | Show the Call Assist status and message notifications (Android 13+) | — |
| `READ_PHONE_STATE` *(optional, requested only if you enable automatic telephony detection)* | Detect that a **cellular** call is active | No call content, no numbers, no call log access; it is not needed for VoIP detection, which uses the public audio APIs |
| `RECEIVE_BOOT_COMPLETED` | Show a "Call Assist is off" notification after a reboot rather than silently starting anything | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` *(declared for the user-initiated prompt)* | Let the user stop Samsung/Huawei-style battery managers from killing the session mid-call | — |

Deliberately **absent**: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `READ_CALL_LOG`, `READ_CONTACTS`,
`READ_SMS`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `BLUETOOTH_CONNECT`, `WAKE_LOCK`, and any
accessibility service.

The table above is not maintained by hand. `.github/scripts/check-permissions.py` compares it with
the manifest on every CI run, fails when either side drifts, and then inspects the *shipped* APK —
the merged manifest, where a dependency can add a permission this app never declared. Two
permissions were removed after that check was added:

* `MODIFY_AUDIO_SETTINGS` was declared but nothing called `AudioManager.setMode`,
  `setCommunicationDevice` or `setSpeakerphoneOn` — the audio router only requests transient focus,
  which needs no permission. It was also listed as *deliberately absent* while being present, which
  is exactly the kind of contradiction a user cannot be expected to audit. Requesting it would look
  like capability the app is proud *not* to need: changing the communication route is precisely what
  can break the call the user is on.
* `WAKE_LOCK` was declared and no code path ever acquired one (the media player holds its own), so it
  was an unused promise.

`BIND_QUICK_SETTINGS_TILE` is not on the list because it is not requested: it appears in the
manifest as the `android:permission` attribute of the Call Assist quick-settings tile service, which
is the permission Android checks before letting *another* app bind that service. It grants this app
nothing.

Libraries additionally declare: `com.airwhispers.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

That one line is the whole of it. It comes from AndroidX Core, which declares a signature-level
permission against the app itself to gate its own non-exported dynamic receivers; it is scoped to
`com.airwhispers`, is not a system capability, and grants nothing to another app. It is named here
because it does appear in the shipped APK's manifest — and the CI check fails if the APK ever carries
a merged permission this file does not name, or names one the APK no longer carries.

## Data retention

* Server: messages persist until deleted; refresh tokens expire after 60 days and are pruned
  on use. No analytics, no third-party SDKs, no advertising identifiers.
* Device: the SQLite cache holds conversations and messages for the signed-in account and is
  wiped by "Sign out" (`secrets.clearSession()` + `LocalStore.clearAccount()`), together with
  the obfuscation of the local identifier.
* Uninstalling the app removes everything on the device.

## Reporting a vulnerability

Open a private security advisory on the GitHub repository (`Security → Report a
vulnerability`) rather than a public issue. Please include the version, a reproduction, and
whether the impact is confidentiality, integrity, or availability. Expect an acknowledgement
within a few days; this is a small project, so there is no bug-bounty programme to promise.
