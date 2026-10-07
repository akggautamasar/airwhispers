# iOS: status, and the honest path to a build

## Short version

* The **backend, protocol and feature set are platform-neutral** — an iOS client uses the same
  `/api/v1` REST API, the same WebSocket events and the same message/settings models.
* There is **no iOS app in this repository yet**, and this document does not pretend
  otherwise. Producing one requires a Mac (or a macOS CI runner) for Xcode, signing and
  App Store distribution — Apple does not permit building iOS apps on Linux, and no amount of
  Gradle will change that.
* The Android pipeline was chosen as the first deliverable because it can be built, signed and
  released **entirely on GitHub Actions** — which is exactly what this repository does.

The user's requirement "an APK that also works on iOS" is satisfied to the extent the
platforms allow: **one APK for Android** (installable, signed, attached to a GitHub Release)
plus **one shared backend and one shared product definition** that an iOS client consumes
unchanged. A single binary that runs on both Android and iOS does not exist for any
technology, and any tool that claims otherwise is packaging two apps behind one build step.

## What is already iOS-ready today

| Piece | Why it carries over |
| --- | --- |
| REST + WebSocket API | No Android-specific design: JSON, bearer tokens, first-frame WebSocket auth |
| Message model | `id, conversationId, senderId, recipientId, text, createdAt, priority, deliveredAt, readAt, spokenAt` — no platform fields |
| Speech settings | `languageTag`, `rate`, `pitch`, `voiceName`, `output`, `emojiMode` map onto `AVSpeechSynthesizer` with a small adapter |
| Text normalisation rules | The rules in [tts.md](tts.md) are a specification; a Swift port is mechanical (and testable with the same table of cases) |
| Deduplication contract | `clientMessageId` + `spokenAt` semantics are server-enforced, so a second client cannot double-speak a message |
| Push model | FCM works on iOS through APNs; the backend's `devices` table already has a `platform` column |
| Docs | Feasibility findings, limitations, security model and permissions rationale all apply, with the iOS caveats below |

## iOS platform reality check

| Android capability | iOS equivalent | Notes |
| --- | --- | --- |
| Foreground service holding a socket | Limited background execution; `BGAppRefreshTask` is opportunistic | iOS kills sockets and background audio without an active audio session — the iOS client must be push-first |
| Remote push waking the app for custom work | Notification Service Extension (with `mutable-content`) | Runs on delivery and *can* trigger local work; this is the practical hook for "speak on arrival" |
| Background TTS mixed into a call | `AVSpeechSynthesizer` with the `.playback`/`.voicePrompt` audio session category, mixed with other audio | Mixing behaviour with a VoIP call is app-dependent; `.duckOthers` is the closest analogue of our `MAY_DUCK` focus request |
| Detecting another app's call | **Not possible** — no public API exposes another app's call state; `CallKit` only reports *this* app's calls | The manual "arm the assist" model is mandatory on iOS, not optional |
| Microphone-in-use heuristic | No public API | Cannot be replicated |
| Bluetooth routing | `AVAudioSession` route change notifications | Read-only reporting, same posture as Android |
| Sideloading an app | Not possible (TestFlight or the App Store, or a 7-day dev-signed build) | Distribution friction is higher by design |

Consequence: on iOS the product is **"messages spoken while you have armed the assistant"**,
not "spoken during any call" — because iOS gives no way to observe another app's call. That is
a platform fact, and the app must say so in its own UI rather than implying parity.

## Build path (when an iOS client is added)

1. **Hosting**: a GitHub Actions `macos-14` runner (or a self-hosted Mac). `xcodebuild` +
   `xcodebuild -exportArchive` can produce an `.ipa` in CI; a simulator build needs no
   signing at all and can run on the same runner for smoke tests.
2. **Project**: a SwiftUI app, structured like the Android one
   (`Config/`, `Core/`, `Data/Remote`, `Data/Local`, `Domain/TTS`, `Domain/Assist`, `Service/`, `UI/`),
   with the same three pure-logic pieces ported first — `TextNormalizer`, `TtsQueue`,
   `CallAssistEngine` — because those are exactly what the Android unit tests already pin down.
3. **Storage**: SwiftData or SQLite for the same cache + `spoken_at` ledger (the dedupe
   contract is server-shared, so the local ledger only needs to be local-equivalent).
4. **Secrets**: Keychain (the direct analogue of the Android Keystore path).
5. **Push**: APNs via the existing backend `devices` table; extend `push.ts` with an APNs
   sender (HTTP/2 + JWT key) — the fan-out logic is already platform-agnostic.
6. **Background speech**: a Notification Service Extension that decides (with the same gate
   order) whether to speak, then hands off to the app's `AVSpeechSynthesizer`.
7. **Distribution**: TestFlight for beta, App Store for release. Apple's review guidelines
   matter here: an app that reads messages aloud is fine, but any claim of *call*
   integration must be backed by real API usage — the honest "armed assistant" framing is
   also the compliant one.

## What we deliberately did not do

* Ship a WebView/Cordova/React-Native wrapper that pretends to be a native iOS app: it could
  not speak reliably in the background, and it would misrepresent the platform capability.
* Promise an iOS build from this Linux-only CI: impossible without macOS runners, and a fake
  `ios/` folder with broken Xcode project files would be worse than no folder.
* Copy Android-specific concepts (foreground services, audio focus) into iOS documentation as
  though they translate — the table above states what actually differs.

## Summary for the release notes

> AirWhispers ships for Android today: a signed APK built and released by GitHub Actions.
> The server, protocol, message model and speech rules are platform-neutral, and
> `docs/ios.md` documents the concrete path to an iOS client (SwiftUI + AVSpeechSynthesizer,
> built on a macOS runner). iOS cannot observe another app's calls, so there the assistant is
> explicitly armed by the user — the same manual fallback Android already ships.
