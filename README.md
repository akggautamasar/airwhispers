# AirWhispers

**Messages you can hear.** While you are on a call in WhatsApp, Telegram, Instagram,
Google Meet, Discord, Signal or the plain dialer, incoming AirWhispers messages can be
spoken aloud to you — through the phone speaker, wired earphones or Bluetooth earbuds.
You never have to look at the phone.

> The external call is completely independent. AirWhispers does not join it, record it,
> reroute it, or inject audio into it. It speaks to **you**, the person holding the phone.

```
   Partner sends "Are you alone?"
              │
        our server (TLS)                    your phone
              │                                 │
              ▼                                 ▼
   realtime socket / cloud push  ──▶  Call Assist decides it may speak
                                                │
                                        text → natural speech
                                                │
                                        sequential TTS queue
                                                │
                                     speaker / wired / Bluetooth
                                                │
                                   you hear it, call continues
```

* Status: **v1.0.0 MVP** — Android app (this repo builds a signed APK), Node backend, docs.
* License: MIT (see [LICENSE](LICENSE)).

---

## Get the app (fastest path)

1. Open the [Releases page](../../releases) and download `app-standalone-release.apk`
   (verify it against the matching `.sha256` file).
2. Run an AirWhispers server ([backend/README.md](backend/README.md)):
   `docker compose up` or `npm ci && npm run dev`.
3. In the app: enter `http://<your-server>:8080`, create an account.
4. Second phone: same server, second account, add each other by email.
5. On the phone that should **listen**: *Call Assist → Start Call Assist*, and mark the
   sender as a **trusted** contact (Contacts → toggle 🔊).
6. Take a call in any other app, then have the other phone send a message
   (tap **🔊 Speak Now** to jump the queue). The message is spoken to you.

Manually starting Call Assist always works on every device. Automatic call detection is a
documented heuristic — read [docs/android-limitations.md](docs/android-limitations.md)
before trusting it with something important.

---

## Repository layout

```
airwhispers/
├── android/                 Kotlin + Jetpack Compose app (Gradle, Kotlin DSL)
│   ├── app/                 single module: core/, data/, domain/, service/, ui/, push/
│   ├── keystore/            demo signing identity (replace before a store release)
│   └── scripts/             keystore generation
├── backend/                 Node 22 + TypeScript + Fastify + PostgreSQL (or in-memory)
│   ├── src/                 config, crypto, validators, store (memory|pg), realtime, push, app
│   ├── sql/schema.sql       database schema
│   ├── tests/               API tests (node:test)
│   └── scripts/smoke.mjs    live end-to-end smoke test
├── docs/                    feasibility, architecture, API, security, limitations, iOS, testing
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

---

## What AirWhispers does — and does not do

**Does**: private 1-to-1 messaging, realtime + push delivery, trusted-contact rules,
a sequential speech queue with pause/skip/stop, natural text normalisation
(`"I love you ❤️😂"` → *"I love you."*), Bluetooth-aware audio, work with the screen off.

**Does not** (by design, and this is the honest part):

* record, intercept or inspect any third-party call — no call audio, ever;
* inject speech into WhatsApp/Telegram/Meet/Discord's call stream (no public Android API
  allows it, and we do not use private ones);
* ask for the microphone — the app has no `RECORD_AUDIO` permission at all;
* read other apps' messages or use an accessibility service to spy on them;
* pretend automatic call detection is universal: VoIP apps are recognised through the
  system audio mode and microphone usage, which is a heuristic, backed by a manual switch.

Full analysis: [docs/feasibility.md](docs/feasibility.md) ·
[docs/android-limitations.md](docs/android-limitations.md)

## Documentation

| Document | Contents |
| --- | --- |
| [docs/feasibility.md](docs/feasibility.md) | The technical feasibility study: background delivery, TTS during a third-party call, audio focus, Bluetooth, call detection, OEM behaviour, battery |
| [docs/architecture.md](docs/architecture.md) | System architecture, module map, data flow, database schema, deployment |
| [docs/api.md](docs/api.md) | Versioned REST + WebSocket API reference |
| [docs/security.md](docs/security.md) | Threat model, auth, token storage, privacy posture, E2EE path |
| [docs/android-limitations.md](docs/android-limitations.md) | What Android permits, what it forbids, and what the app does instead |
| [docs/tts.md](docs/tts.md) | Speech pipeline: normalisation rules, queue semantics, deduplication |
| [docs/testing.md](docs/testing.md) | Test strategy, device matrix, manual test script |
| [docs/ios.md](docs/ios.md) | iOS status and the path to an iOS build |

## Contributing / house rules

* Prefer official Android APIs. No private APIs, no accessibility abuse, no OEM hacks.
* Local TTS (device engine) rather than cloud TTS: privacy, latency, offline.
* Never log message bodies, tokens or call content — see `core/AppLog.kt`.
* Every behaviour that depends on the platform gets a documented fallback.

MIT licensed. Built to make "message arrives → you hear it → you keep talking" feel normal.
