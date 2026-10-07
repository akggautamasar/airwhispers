# Security and privacy

AirWhispers hears your messages out loud, so it must be unusually careful about *which*
messages, *where* the text goes, and *what* it leaves behind. This document is the honest
description of the current posture and the reasoning behind each decision.

## Threat model

| Adversary | We defend with | Residual risk |
| --- | --- | --- |
| Someone on the same Wi-Fi (passive eavesdropper) | TLS in production; cleartext allowed only for a private LAN development server | A development deployment on plain HTTP is readable — the app warns in the UI |
| Someone who steals the phone, unlocked app data | Tokens encrypted with a non-exportable Android Keystore key; `allowBackup=false`; message cache in app-private SQLite | Rooted device or a full-disk image can recover the local cache |
| Someone who steals the *server* database | Passwords hashed with scrypt (N=16384, per-user salt); refresh tokens stored as SHA-256 hashes; message bodies are the only plaintext, by design | Message text is readable by whoever runs the server — see the E2EE path below |
| A malicious API client | Per-route authorization (membership, recipient, ownership), scrypt cost, rate limits, uniform error envelopes | No device attestation; a stolen refresh token is usable until revoked |
| Another app on the device | No exported components except the quick-settings tile and the notification actions, both of which only start/stop the local service; no content providers, no broadcast receivers that accept foreign input | A malicious app with `READ_LOGS` (root) could read logs — we never log message text |
| The speech engine vendor (if a cloud TTS were used) | We use the **device** TTS engine; text never leaves the phone for synthesis | A device vendor's TTS engine could upload in theory, which is why the engine is swappable behind `SpeechSynthesizer` |
| Google (when the `fcm` flavor is used) | Push payloads carry *no message text* unless the server operator explicitly enables `PUSH_INCLUDES_CONTENT` | Metadata (that a message arrived, sender, timestamp) passes through FCM |

Explicit non-goals: resisting a compromised *device* (rootkits, accessibility-based
attackers), resisting a compromised *server operator*, and hiding metadata from the platform.

## Authentication and sessions

* **Passwords**: scrypt with a per-user 16-byte salt, N=16384, r=8, p=1, 32-byte key,
  stored as `scrypt$N$salt$key`. Verification is constant-time (`timingSafeEqual`).
* **Access token**: JWT HS256, 15 minutes, `typ: "access"`, subject = user id. Verified on
  every request; the `typ` claim prevents a refresh token from being used as an access token.
* **Refresh token**: 32 random bytes, base64url, returned exactly once, stored as a SHA-256
  hash with `expires_at`, `rotated_at`, `revoked_at`. **Every use rotates it**; reusing a
  rotated token revokes the family and returns `401`.
* **Logout**: revokes the presented token (and its family). Empty bodies are accepted, so the
  client can always clean up.
* **Rate limits** (per IP): register 10/min, login 20/min, refresh 60/min; sends are limited
  to 120/min per user. Exceeding returns `429` with `retryAfterMs`.

## Token storage on the device

`SecretStore` keeps the access/refresh tokens and the account id in an `EncryptedSharedPrefs`
style blob: AES-256-GCM with a key generated inside the **Android Keystore** and marked
non-exportable. A fresh IV is generated per write and stored alongside the ciphertext; the
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
| `GET/POST /conversations/:id/messages` | Caller is a member of the conversation → else `403 forbidden` (unknown id → `404`) |
| `POST /messages/:id/read`, `/spoken` | Caller is the message **recipient** → else `403 forbidden` |
| `PATCH/DELETE /contacts/:id` | The contact row belongs to the caller |
| `GET /conversations` | Only conversations the caller is a member of |
| Everything else | Authenticated, and scoped to `sub` |

The tests assert these negatives explicitly (a stranger cannot read a conversation, cannot
mark someone else's message read, cannot edit someone else's contact), including that
unknown ids cannot be told apart from forbidden ones by a stranger.

Ids are validated as UUIDs at the boundary before any query: a malformed id is a
`400 bad_request` rather than a parameter that reaches PostgreSQL (`invalid input syntax
for type uuid`, 22P02) and surfaces as a 500. The store repeats the check and treats a
malformed id as "no such row", so a future caller cannot turn a bad id into a database
error either — verified against real PostgreSQL by the store contract.

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
3. Key material belongs in the Android Keystore, alongside the existing token encryption, and
   public keys would be published per device via the existing `/devices` route extended with a
   `publicKey` field.
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
| *(none for Bluetooth)* | Route reporting uses `AudioManager.getDevices`, which needs no permission | No connect/disconnect/reroute capability is requested at all |
| `RECEIVE_BOOT_COMPLETED` | Show a "Call Assist is off" notification after a reboot rather than silently starting anything | — |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` *(declared for the user-initiated prompt)* | Let the user stop Samsung/Huawei-style battery managers from killing the session mid-call | — |

Deliberately **absent**: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `READ_CALL_LOG`, `READ_CONTACTS`,
`READ_SMS`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `BLUETOOTH_CONNECT`, and any
accessibility service. The full permission list is ten entries long — the manifest is the
source of truth and the list above is kept in sync with it.

`MODIFY_AUDIO_SETTINGS` deserves a note: it is the permission that would allow changing the
*communication* device or the audio mode. Requesting it would look like capability the app is
proud *not* to need, because changing the communication route is exactly what can break the
call the user is on.

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
