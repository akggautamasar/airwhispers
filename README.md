# AirWhispers

**Messages you can hear.** While you are on a call in WhatsApp, Telegram, Instagram,
Google Meet, Discord, Signal or the plain dialer, incoming AirWhispers messages can be
spoken quietly to you — through the phone speaker, wired earphones or Bluetooth earbuds.
You never have to look at the phone.

> The external call is completely independent. AirWhispers does not join it, record it,
> reroute it, or inject audio into it. It speaks to **you**, the person holding the phone.

```
   Friend sends "Are you alone?"            (their phone knows your code)
              │
        your server (TLS)                    your phone
              │                                 │
              ▼                                 ▼
   realtime socket / cloud push  ──▶  Call Assist decides it may speak
                                                │
                                        text → natural speech (whisper mode)
                                                │
                                        sequential TTS queue
                                                │
                                     speaker / wired / Bluetooth
                                                │
                                   you hear it, the call continues
```

**No accounts. No login. No email. No phone number.** The first time the app reaches a
server it is handed a random six-character **code** (`k7m2pq`) that identifies that
device forever. That code is your entire address book entry, and the only thing a friend
needs in order to whisper to you. Nobody can whisper to a phone whose owner did not
allow them: consent is one switch per person, owned by the listener.

---

## What is "the server"?

AirWhispers is peer-to-peer in spirit but needs somewhere to meet. That somewhere is a
small Node program — the **server** — that:

* hands every device a code the first time it says hello,
* relays messages between phones over a WebSocket (and stores the history),
* tells you when a friend is online, when they are typing, and when they read you.

It does **not** need an account system, and it stores nothing about you beyond your
display name, your code and your messages. You can run it yourself in about a minute, or
use an address somebody gave you. Two phones that have the same server address can talk;
phones on different servers cannot (yet).

```bash
cd backend && npm ci && npm run dev     # → http://<your-ip>:8080
```

That is the whole installation. `docker compose up` does the same thing with PostgreSQL
for a permanent deployment. Full walkthrough: **[docs/quickstart.md](docs/quickstart.md)**.

---

## How do I use the app?

1. **Install the app.** Download `app-standalone-release.apk` from the
   [Releases page](../../releases) (check the `.sha256`), or build it yourself:
   `cd android && bash scripts/make-keystore.sh && ./gradlew assembleStandaloneRelease`.
2. **Open it.** Two fields: the address of your server, and your name. Tap
   **Get my code**. The home screen now shows **your code** in big letters — that is the
   whole sign-up.
3. **Start a chat.** Tap ✏️, type the code your friend reads out to you.
4. **Let them whisper to you.** In the chat, tap **🔇 muted** in the header so it turns
   into **🔊 whispers on**. Only people you switch on can be spoken aloud; everyone else
   is an ordinary notification.
5. **Arm Call Assist.** Call Assist tab → **Start listening** (or the quick-settings
   tile / the notification). Leave it armed while you are on a call in another app.
6. **Get a message.** When your friend sends "Are you alone?" — tap **🔊 Whisper** to ask
   for it to be spoken immediately — you hear it through whatever is in your ear. Whisper
   mode is on by default, so the message stays between you and your earbuds.

Anything that goes wrong is visible in the app: no server, no permission, no text-to-speech
engine, no network — each has its own message, never a silent failure.

### Want to try it without a phone?

Run the backend and open `http://localhost:8080/` in two browser tabs. Each tab is a
device: it gets its own code, you can pair the two tabs by code, chat in realtime, toggle
permission, and hear "whisper now" messages spoken locally. It is the same public API the
Android app uses — a test bench, not a second product.

---

## Try it, in one minute

```bash
cd backend
npm ci
npm run dev            # in-memory store, demo console at /
npm test               # 20 API tests: identity, consent, messaging, rate limits
node scripts/smoke.mjs # live end-to-end check (two devices, socket, whisper consent)
```

---

## Repository layout

```
airwhispers/
├── android/                 Kotlin + Jetpack Compose app (Gradle, Kotlin DSL)
│   ├── app/                 single module: core/, data/, domain/, service/, ui/, push/
│   ├── keystore/            demo signing identity (replace before a store release)
│   └── scripts/             keystore generation, cross-file reference check
├── backend/                 Node 22 + TypeScript + Fastify + PostgreSQL (or in-memory)
│   ├── src/                 config, crypto, validators, store (memory|pg), realtime, app
│   ├── public/index.html    browser test console served at / and /demo
│   ├── sql/schema.sql       database schema
│   ├── tests/               API tests (node:test)
│   └── scripts/smoke.mjs    live end-to-end smoke test
├── docs/                    quickstart, feasibility, architecture, API, security, TTS, …
└── .github/workflows/       Android build + release, backend type-check + tests
```

## Build the Android app yourself

```bash
cd android
bash scripts/make-keystore.sh          # demo signing identity -> keystore/*.p12
./gradlew testStandaloneDebugUnitTest  # unit tests
./gradlew assembleStandaloneRelease    # app-standalone-release.apk
```

Requires JDK 17 and the Android SDK (compileSdk 35). CI does the same thing and attaches
the APK to a GitHub Release — see [.github/workflows/android.yml](.github/workflows/android.yml).

Two product flavors:

| Flavor | What it does | Needs |
| --- | --- | --- |
| `standalone` (default) | Messages arrive over the app's own realtime channel while Call Assist is armed. No Google account, no Firebase, nothing to configure. | nothing |
| `fcm` | Adds Firebase Cloud Messaging so a message can wake the app even when it was killed — the path to "never pre-arm anything". | `android/app/google-services.json` |

## Run the backend

```bash
cd backend
npm ci
npm run dev        # in-memory store, no database needed
npm test           # type-check + API tests + build
node scripts/smoke.mjs   # against a running server
```

Production: set `DATABASE_URL`, `JWT_SECRET` and run `npm run schema && npm start`
(or `docker build`). Details: [backend/README.md](backend/README.md).

### API in one screen

```
POST /api/v1/device/register   { deviceId, displayName? }        → { user.code, tokens, deviceSecret }
POST /api/v1/device/token      { deviceId, deviceSecret }        → fresh token, no UI involved
POST /api/v1/device/forget     { deviceId, deviceSecret }        → new code next time
GET  /api/v1/me                                                  → your code and name
GET  /api/v1/conversations                                       → chats, presence, consent flags
POST /api/v1/conversations     { code }                          → open a chat with a code
GET  /api/v1/conversations/:id/messages
POST /api/v1/conversations/:id/messages { clientMessageId, text, priority }
PATCH /api/v1/conversations/:id/trust { trusted }                → "may they whisper to me"
POST /api/v1/messages/:id/read | /spoken                          → receipts
WS   /api/v1/realtime          auth · typing · message.* · presence.updated
```

Full reference: [docs/api.md](docs/api.md).

---

## What AirWhispers does — and does not do

**Does**: private 1-to-1 messaging, realtime + optional push delivery, per-person speech
consent, presence (online / typing / read), English and Hindi UI (per-app language on
Android 13+) and English/Hindi/Hinglish speech, **whisper mode** (quiet speech meant for
earbuds during a call), a sequential speech queue with pause/skip/stop, natural text
normalisation (`"I love you ❤️😂"` → *"I love you."*), Bluetooth-aware audio, work with the
screen off.

**Does not** (by design, and this is the honest part):

* record, intercept or inspect any third-party call — no call audio, ever;
* inject speech into WhatsApp/Telegram/Meet/Discord's call stream (no public Android API
  allows it, and we do not use private ones);
* ask for the microphone — the app has no `RECORD_AUDIO` permission at all;
* read other apps' messages or use an accessibility service to spy on them;
* pretend automatic call detection is universal: VoIP apps are recognised through the
  system audio mode and microphone usage, which is a heuristic, backed by a manual switch;
* keep an account you can lose: there is nothing to reset, because there is no password.
  Lose the device or its secret and you register again — with a new code.

Full analysis: [docs/feasibility.md](docs/feasibility.md) ·
[docs/android-limitations.md](docs/android-limitations.md)

## Documentation

| Document | Contents |
| --- | --- |
| [docs/quickstart.md](docs/quickstart.md) | Run a server, install the app, pair two phones, hear a whisper — step by step |
| [docs/feasibility.md](docs/feasibility.md) | The technical feasibility study: background delivery, TTS during a third-party call, audio focus, Bluetooth, call detection, OEM behaviour, battery |
| [docs/architecture.md](docs/architecture.md) | System architecture, module map, data flow, database schema, deployment |
| [docs/api.md](docs/api.md) | Versioned REST + WebSocket API reference |
| [docs/security.md](docs/security.md) | Threat model, device identity, token storage, privacy posture, consent, E2EE path |
| [docs/android-limitations.md](docs/android-limitations.md) | What Android permits, what it forbids, and what the app does instead |
| [docs/tts.md](docs/tts.md) | Speech pipeline: normalisation rules, whisper mode, queue semantics, deduplication |
| [docs/testing.md](docs/testing.md) | Test strategy, device matrix, manual test script |
| [docs/ios.md](docs/ios.md) | iOS status and the path to an iOS build |

## Contributing / house rules

* Prefer official Android APIs. No private APIs, no accessibility abuse, no OEM hacks.
* Local TTS (device engine) rather than cloud TTS: privacy, latency, offline.
* Consent is owned by the listener. A sender can ask, never force.
* Never log message bodies, tokens or call content — see `core/AppLog.kt`.
* Every behaviour that depends on the platform gets a documented fallback.

MIT licensed. Built to make "message arrives → you hear it → you keep talking" feel normal.
