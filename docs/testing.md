# Testing

Three layers, in the order they fail fastest: JVM unit tests, backend API tests, and a
scripted manual pass on real hardware (because nothing in a unit test can hear a speaker).

## What runs automatically

| Suite | Where | Command | Covers |
| --- | --- | --- | --- |
| Android unit tests | `android/app/src/test/java/com/airwhispers/` | `cd android && ./gradlew testStandaloneDebugUnitTest` | Text normalisation, queue semantics, assist decision gates |
| Backend API tests | `backend/tests/api.test.ts` | `cd backend && npm test` | Device identity and codes, consent, messaging, idempotency, receipts, rate limiting |
| Schema reference check | `backend/scripts/check-schema-refs.ts` | part of `npm test` | Every table and column the PostgreSQL store names must exist in `sql/schema.sql`, and every `ON CONFLICT` target must have a unique or primary key — the SQL is never executed by these tests, so this is what catches a schema that drifted away from the code |
| **Permission manifest** | `.github/scripts/check-permissions.py` (CI, before the build) | The manifest and the table in `docs/security.md` must list the same permissions, no "deliberately absent" permission (microphone, call log, contacts, SMS, overlay, audio-mode, accessibility) may appear in the shipped APK, and permissions merged in from libraries are reported as an annotation |
| **Translations** | `.github/scripts/check-translations.py` (CI, before the build) | Every locale has the same keys as `values/`, nothing is empty or duplicated, and each translated string keeps its English format arguments |
| Backend smoke test | `backend/scripts/smoke.mjs` | `node scripts/smoke.mjs http://127.0.0.1:8080` | A real server process end to end: register ×2, WebSocket auth, send, receive, consent, receipt |
| CI | `.github/workflows/` | push / PR | All of the above, plus the release APK build and the release APK's own verification (integrity, package id, not debuggable, signature, shipped permissions, Hindi resources present) |

### Android unit tests

* **`TextNormalizerTest`** — decorative vs meaning-bearing emoji, the redundancy rule
  ("I love you ❤️" must not become "I love you love"), `IGNORE`/`READ_ALL` modes, link
  replacement, chat shorthand expansion, markdown stripping, repeated punctuation, terminal
  punctuation, emoji-only messages being unspeakable, truncation on a sentence boundary,
  and Hindi/Hinglish pass-through.
* **`TtsQueueTest`** — strict one-at-a-time ordering, duplicate rejection, `SPEAK_NOW`
  overtaking, bounded depth with oldest-normal eviction, pause blocking `awaitNext` and
  resume releasing it, skip emitting an interruption and keeping the queue, stop emptying
  everything, and the exact spoken phrasing including sender announcement.
* **`CallAssistEngineTest`** — every gate in the pipeline (off, not in a call, sender not
  allowed, own message, nothing to speak, full queue), the claim granting speech exactly once
  and returning `ALREADY_SPOKEN` afterwards, `UNKNOWN_MESSAGE` for an unpersisted message,
  the claim *not* being consumed when the queue is full, and `SPEAK_NOW` phrasing.

These are pure JVM tests: `domain/` has no Android imports by design, so they run in
milliseconds on any machine (and on the CI runner, which has no emulator).

### Backend API tests

They build the real Fastify app over the in-memory store, so the code under test is the same
code production runs — only the storage differs.

Asserted invariants include: a code is 6 characters from the unambiguous alphabet and unique
per device (100 registrations, zero collisions); resuming with the device secret returns the
**same** code while a wrong secret returns `401`; the secret itself is never stored in
plaintext; a stranger cannot read a conversation (`403`) nor mark another user's message read
(`403`); only the *listener* can change consent for a pair; duplicate `clientMessageId`
returns the original message with `200` and never creates a second row; history reads stamp
`delivered_at`; `spoken` receipts are recipient-only and always `204`; a code that is not
normalised (`" K7M-2PQ "` → `k7m2pq`) still resolves while an unknown or self code is
rejected; per-scope rate limits return `429` with `retryAfterMs`; and validation failures use
the standard error envelope.

### CI

`.github/workflows/android.yml` warms Gradle, generates the demo signing identity, runs the
unit tests, assembles the release and debug APKs of the `standalone` flavor (and attempts the
`fcm` flavor, which is allowed to fail without an account), records SHA-256 checksums, and
uploads both APKs. When dispatched with `create_release: true` (or on a `v*`/`android-*`
tag) a second job publishes a GitHub Release with the APK and its checksum attached.

`.github/workflows/backend.yml` type-checks, runs the API tests, then boots a live server and
runs the smoke script against it — i.e. it exercises the exact artifacts a deployment would.

## Manual verification on a device

Automatic tests cannot confirm that a human hears a sentence. This is the script to run on
each device in the matrix; it takes about fifteen minutes.

### Preparation

1. Server reachable from both phones (`docker compose up` is fine).
2. Install `app-standalone-release.apk` on both.
3. Phone A: note the code on the home screen, **Call Assist → Start listening**.
4. Phone B: ✏️ → type A's code (tap **Copy** on A to send it over any channel).
5. Confirm in Settings → Device capability that the app reports what it can and cannot detect.

### Core loop

| # | Do this | Expect |
| --- | --- | --- |
| 1 | Phone B sends "Are you alone?" | A's phone speaks it; A's screen may be off and the app backgrounded |
| 2 | Start a WhatsApp call on A to a third person, then have B send two messages | Both are spoken **in order**, the call continues, the other participant hears nothing from us |
| 3 | Have B send five messages quickly | Spoken one after another; the sender's name is announced once, not five times |
| 4 | While a message is being spoken, tap **Pause** in the notification | The current utterance finishes, nothing new starts; **Resume** continues |
| 5 | Tap **Skip** during an utterance | The utterance stops mid-sentence, the next message starts |
| 6 | Tap **Stop** | Speech stops and the pending queue is emptied |
| 7 | Have B send a message, then lock the screen and press power to sleep the phone | The message is still spoken |
| 8 | Have B press **🔊 Whisper** while A's queue holds a normal message | The urgent message is spoken next, phrased "Important. …" |
| 9 | Send a message that was already spoken, from the same conversation history (or toggle airplane mode and back so the socket reconnects) | Nothing is spoken twice |
| 10 | Turn **Call Assist off** on A, then have B send a message | No speech at all, a normal notification appears |
| 11 | Turn the 🔇/🔊 switch in the chat header off, then have B send a message | Not spoken; notification only |
| 12 | Switch A to airplane mode, have B send a message, then restore connectivity | The message arrives and is spoken once |
| 13 | Kill the app from recents while Call Assist is armed (fcm flavor) | Depending on the platform, the session ends; the app must not claim otherwise — reopen and it says Call Assist is off |

### Audio routing matrix

| Route | Expected |
| --- | --- |
| Phone speaker | Speech is audible over the call's ducked audio |
| Wired headset | Speech in the headset, call audio ducked |
| Bluetooth earbuds (A2DP) | Speech in the earbuds; **[device test]** check whether the headset mixes the call and media streams |
| Bluetooth during a call (SCO/HFP) | **[device test]** loudness and ducking vary by headset firmware — record what happens rather than assuming |
| Speaker + "prefer Bluetooth" enabled with no headset connected | Speech stays on the speaker; the setting never breaks playback |

### Device matrix (real hardware required)

| Tier | Devices | Why | Status |
| --- | --- | --- | --- |
| A — reference | Pixel 7 / 8 (Android 14/15) | Stock FGS rules, cleanest detection | ☐ not yet run |
| B — mainstream | Samsung Galaxy S21/S23 (One UI 5/6) | Aggressive battery management; the "Sleeping apps" list can kill the session | ☐ not yet run |
| C — mainstream | OnePlus 11 / Nord (OxygenOS 13+) | Differs from both of the above in background policy and audio routing | ☐ not yet run |
| D — budget | a Redmi/Realme device with an OEM battery manager | Worst case for background execution | ☐ not yet run |
| E — older | a Nokia/Moto device on Android 8/9 (the minSdk floor) | Notification channels, legacy permission paths | ☐ not yet run |

The checkboxes are honest: these runs have **not** happened in this repository, so no
device-specific claim is made anywhere in the docs. What was verified automatically is listed
above; what a device must confirm is exactly the table in this and the previous sections.

### Things that need a device, stated plainly

* perceived loudness and ducking quality
* whether a given VoIP app trips the microphone-in-use / audio-mode signals
* Bluetooth mixing behaviour per headset
* whether an OEM battery manager kills the session, and how long that takes
* whether the quick-settings tile appears before the app has been opened once

## Regression checklist for contributors

1. `cd backend && npm test` — type-check + 12 API tests.
2. `cd backend && node scripts/smoke.mjs <url>` against a running server.
3. `cd android && ./gradlew testStandaloneDebugUnitTest` — 32 unit tests (14 normaliser, 10 assist, 8 queue).
4. `cd android && ./gradlew assembleStandaloneRelease` — the APK still builds.
5. If you touched the speech pipeline: re-run at least the core loop (steps 1–3, 8–10) on one
   device and record the result in the PR description.
