# Architecture

AirWhispers is a small monorepo with three cooperating parts: an Android app that does the
speaking, a Node/TypeScript server that moves messages, and documentation that keeps the
platform constraints honest.

```
┌────────────────────────── Android (Kotlin, Compose) ──────────────────────────┐
│                                                                              │
│  ui/            MainActivity · AppViewModel · screens · theme · components    │
│                        │                                                      │
│  data/repo/     AirWhispersRepository  ── orchestration + offline behaviour   │
│            ┌───────────┼───────────────┬──────────────────┬───────────────┐   │
│            ▼           ▼               ▼                  ▼               ▼   │
│  data/remote/      data/local/    domain/tts/       domain/assist/   data/prefs/ │
│  ApiClient         LocalStore     TtsQueue          CallAssistEngine  SecretStore│
│  RealtimeClient    (SQLite)       TextNormalizer    SpokenLedger      SettingsStore│
│  Dto               SpokenLedger   SpeechSynthesizer  │                       │
│            │                                          │                       │
│            │                    service/              │                       │
│            │      CallAssistService (foreground)      │                       │
│            │        ├── CallDetector                  │                       │
│            │        ├── SpeechPlayer ──▶ TtsQueue ────┘                       │
│            │        ├── AndroidTtsSynthesizer (device TTS engine)             │
│            │        ├── AudioRouter                                           │
│            │        └── Notifier                                              │
│            │                                                                  │
│  push/     PushBridge  ◀── FcmPushProvider (fcm flavor only)                  │
└────────────┼─────────────────────────────────────────────────────────────────┘
             │  HTTPS /api/v1 (REST)      WSS /api/v1/realtime
┌────────────▼─────────────────────────────────────────────────────────────────┐
│                        Node 22 + TypeScript + Fastify                        │
│  server.ts ── app.ts (REST + WS routes) ── realtime.ts (socket hub)           │
│                │             │                  │                            │
│           validators.ts   crypto.ts          push.ts (FCM HTTP v1, optional) │
│                │             │                  │                            │
│                └──── store.pg.ts  |  store.memory.ts (tests & npm run dev) ───┘
│                              PostgreSQL                                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Module responsibilities

### Android

| Path | Responsibility | Depends on |
| --- | --- | --- |
| `config/ProductConfig.kt` | Single source of branding, feature flags and limits (`MAX_SPOKEN_CHARS`, `MAX_QUEUE_DEPTH`) | — |
| `core/` | `AppLog` (redacting logger), `AppResult`/`AppError`, `Dispatchers` | — |
| `data/model/` | Domain models: `Account`, `Contact`, `Message`, `Conversation`, `CallState`, `SpeechSettings` | core |
| `data/remote/` | `ApiClient` (OkHttp, bearer auth, single-flight refresh, error mapping), `RealtimeClient` (WS relay with backoff + auth frames), `Dto` (wire contract) | model, core |
| `data/local/` | `LocalStore`: SQLite cache, durable outbox, and the `SpokenLedger` implementation (`UPDATE … WHERE spoken_at IS NULL`) | model |
| `data/prefs/` | `SecretStore` (AndroidKeyStore AES/GCM for tokens), `SettingsStore` (observable settings, `normaliseBackendUrl`) | core |
| `domain/tts/` | `TextNormalizer`, `TtsQueue` (pure Kotlin, sequential, deduplicating), `SpeechSynthesizer` interface | model |
| `domain/assist/` | `SpokenLedger` interface, `CallAssistEngine.evaluate()` — the speech decision pipeline | tts, model |
| `service/` | `CallAssistService` (owns the FGS and the armed session), `CallAssistController` (UI/notification facade), `CallDetector`, `SpeechPlayer`, `AndroidTtsSynthesizer`, `AudioRouter`, `Notifier`, `BootCompletedReceiver`, `CallAssistTileService` | domain, data |
| `ui/` | Compose UI: onboarding, chats, conversation, Call Assist dashboard, contacts, settings | data |
| `push/` | `PushBridge` boundary + FCM implementation in the `fcm` flavor source set | — |

**Layering rules.** `domain/` is pure Kotlin with no Android imports, which is what makes the
queue and the decision pipeline unit-testable without a device. Android specifics are
injected into it through small interfaces (`SpeechSynthesizer`, `SpokenLedger`, `Dispatchers`).
`ui/` never talks to `ApiClient` directly — everything goes through the repository.

### Backend

| File | Responsibility |
| --- | --- |
| `config.ts` | Environment parsing with validation and safe defaults |
| `crypto.ts` | scrypt password hashing/verification, JWT sign/verify, refresh-token hashing |
| `types.ts` | Domain types **and** the `Store` interface — the contract both stores implement |
| `store.memory.ts` | In-memory store used by `npm run dev` and the test suite |
| `store.pg.ts` | PostgreSQL store (same contract), used in production |
| `validators.ts` | Hand-written request validation (no schema dependency), consistent error messages |
| `realtime.ts` | WebSocket hub: auth frames, per-user sockets, conversation-scoped fan-out, heartbeats |
| `push.ts` | Optional FCM HTTP v1 sender, service-account JWT, per-device failure pruning |
| `app.ts` | All routes, auth guards, rate limits, error handler |
| `server.ts` | Process bootstrap, graceful shutdown |

The store interface is the seam that makes the whole API testable in-process: `app.ts`
never knows whether it is holding Postgres or a `Map`.

## Core flows

### Sending a message

```
Compose  → repository.sendMessage()
  ├─ optimistic row in SQLite (status SENDING, clientMessageId)
  ├─ POST /api/v1/conversations/:id/messages { clientMessageId, text, priority }
  │     ├─ duplicate (sender, clientMessageId)? → 200 + original row
  │     └─ created → 201 + row  → fan-out: WS frame + push
  └─ response upserted (status SENT), outbox row cleared
```

If the network is down the message stays in the outbox and is retried on reconnect or on the
next app start; the `clientMessageId` is generated once, before the first attempt, so retries
can never duplicate. "Speak Now" sets `priority: SPEAK_NOW` — the fast-path button gets an
instant local echo *and* a high-priority send.

### Receiving and deciding to speak

```
WS frame (or FCM → PushBridge → repository.ingest(...))
  ├─ LocalStore.upsertMessage()      ← idempotent by message id
  ├─ notification (silent if it will be spoken)
  └─ CallAssistEngine.evaluate(message):
       1. is it mine?                 → no
       2. Call Assist enabled?        → no  (hard gate)
       3. onlyDuringCalls && no call? → no
       4. sender trusted?             → no  (Everyone / Contacts only / Selected)
       5. speakable text?             → no  (empty after normalisation)
       6. queue has room?             → no  (MAX_QUEUE_DEPTH)
       7. SpokenLedger.claim(id)      → ALREADY_SPOKEN / UNKNOWN → no
       8. enqueue(normalise(text), priority)
```

Steps 6–7 are the duplicate protection: the claim is a single atomic SQL `UPDATE` that only
succeeds if `spoken_at IS NULL`, so a re-delivered push, a socket replay after reconnect or a
restart mid-queue all converge on "spoken exactly once". A failed synthesis calls
`releaseSpeechClaim()` so a genuine retry later is allowed.

### Speaking

`SpeechPlayer` mirrors the queue state into the service: for each item it requests transient
ducking audio focus, calls `AndroidTtsSynthesizer.speak()` (attributes `USAGE_MEDIA` +
`CONTENT_TYPE_SPEECH`, volume 0.45 when whisper mode is on), waits for the completion
callback, abandons focus, waits the configured inter-message pause, then takes the next item.
The queue is strictly sequential — the engine refuses a second concurrent utterance.

## Branding layer and localisation

The product name is never hard-coded into logic:

| Where | What lives there |
| --- | --- |
| `config/ProductConfig.kt` | Product name, tagline, "Call Assist" label, feature flags and limits — the only Kotlin file that names the product |
| `res/values/strings.xml` | Every user-visible string, including the `brand_*` group read by the UI |
| `app/build.gradle.kts` | `applicationId`, version name/code (overridable with `-Pairwhispers.versionName=…`) |
| Release notes / docs | Product name in prose only |

Renaming the product is therefore a one-file change plus a `strings.xml` edit, and nothing in
the speech pipeline, backend or tests refers to the brand.

Localisation:

* `values/strings.xml` (English) and `values-hi/strings.xml` (Hindi) carry identical key sets —
  139 strings each, checked by comparing the two files. Screens read them with
  `stringResource(...)`; the only hard-coded text left in the UI is decoration (arrows,
  emoji glyphs) and URLs.
* `res/xml/locales_config.xml` declares `en` and `hi` and is referenced from the manifest
  (`android:localeConfig`), so Android 13+ offers *Settings → Apps → AirWhispers → Language*.
* The **interface** language and the **speech** language are deliberately separate settings: a
  user may read the UI in English and still want Hindi speech (`SpeechSettings.languageTag`,
  default `en-IN`), or vice versa.
* Hinglish is handled where it matters — in the text normaliser's abbreviation rules — rather
  than by pretending it is a locale.

## Data model

`backend/sql/schema.sql` (PostgreSQL) and `LocalStore` (SQLite) intentionally mirror each
other so the client can cache everything it needs:

| Table | Purpose | Notable columns |
| --- | --- | --- |
| `users` | Accounts | `email` (unique, lowercased), `password_hash` (scrypt), `display_name` |
| `refresh_tokens` | Rotating sessions | `token_hash`, `expires_at`, `rotated_at`, `revoked_at`, `device_id` |
| `conversations` / `conversation_members` | 1-to-1 conversations (groups are additive later) | `pair_key` unique, `last_message_at` |
| `messages` | Messages | `client_message_id` + `UNIQUE(sender_id, client_message_id)`, `priority`, `delivered_at`, `read_at`, `spoken_at` |
| `contacts` | Trust graph | `owner_id`, `contact_id`, `is_trusted` (default **false**) |
| `user_settings` | Per-user speech defaults | `speak_messages`, `only_during_calls`, `trusted_contacts_only`, `prefer_bluetooth`, `language_tag`, `speech_rate`, `speech_pitch`, `emoji_mode` |
| `devices` | Push targets | `platform`, `push_token`, `last_seen_at` |

Timestamps are epoch milliseconds everywhere (API, database, client) — one representation,
no timezone ambiguity. IDs are UUID strings.

## Security boundaries

* The app trusts only its own server, over TLS (a documented LAN exception exists for
  development; see `network_security_config.xml`).
* The server authorizes every request per resource: conversation membership for reads and
  sends, recipient identity for read/spoken receipts, ownership for contact edits.
* The client stores tokens encrypted with a non-exportable Android Keystore key; the
  backend stores refresh tokens only as SHA-256 hashes.
* Message bodies are never logged by either side (see `AppLog` and the backend's log level
  discipline).

More: [security.md](security.md).

## Deployment

```
GitHub Actions ──▶ release APK ──▶ GitHub Release (with SHA-256 checksums)
                └─▶ backend tests + smoke

docker compose up   →  postgres:16 + airwhispers-backend (built from backend/Dockerfile)
```

CI gates on: TypeScript type-check, backend API tests, a live smoke test, Android unit tests,
**Android lint with `abortOnError`** (permission and API-level correctness) and both APK
builds. Nothing reaches a release without all of them passing.

Production checklist: `JWT_SECRET` (≥ 32 random bytes), `DATABASE_URL`, TLS-terminating
reverse proxy, `TRUST_PROXY=true` so rate limiting sees real client IPs, `PUSH_INCLUDES_CONTENT=false`
if you do not want message text in push payloads, and `npm run schema` on first deploy.
Horizontal scaling needs a WebSocket-aware router (sticky sessions) or a Redis fan-out —
documented, not implemented, because one process serves the MVP comfortably.

## Scalability notes (deliberately deferred)

| Concern | Today | Next step |
| --- | --- | --- |
| Many socket connections | Single Node process | Redis pub/sub fan-out behind the existing `Profile` interface in `realtime.ts` |
| Message history volume | Indexed reads per conversation | Partition `messages` by month, or archive to object storage |
| Push fan-out | One token per device, inline send | Queue (e.g. `pg-boss`) with retry accounting |
| Media messages | Text only | Add `messages.kind` + object storage; the TTS pipeline already ignores non-text |

None of these are on the MVP path, and none of them change the external API.
