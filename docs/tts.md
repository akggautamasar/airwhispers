# The speech pipeline

"The message arrives and I hear it, naturally, without touching anything" is the whole
product. This document describes every stage between a message arriving and a sentence
coming out of the speaker, including the rules that keep it from being annoying.

```
message arrives (socket / push)
   │
   ├─ persisted to SQLite  (idempotent upsert by message id)
   │
   ▼
CallAssistEngine.evaluate()
   1  own message?            → DROP          (unless speakOwnMessages)
   2  Call Assist enabled?    → NOTIFY_ONLY   ("call_assist_off")
   3  onlyDuringCalls?        → NOTIFY_ONLY   ("not_in_call")
   4  sender trusted?         → NOTIFY_ONLY   ("sender_not_trusted")
   5  anything to say?        → NOTIFY_ONLY   ("nothing_to_speak")
   6  queue has room?         → NOTIFY_ONLY   ("queue_full")
   7  claim {GRANTED}                      ← durable, atomic, once ever
   8  enqueue  ────────────────────────────▶ TtsQueue
   │
   ▼
SpeechPlayer loop (foreground service)
   awaitNext() → request focus (GAIN_TRANSIENT_MAY_DUCK)
   → AndroidTtsSynthesizer.speak(utterance) → wait for onDone/onError
   → abandon focus → pause (default 700 ms) → POST /messages/:id/spoken
   → completeCurrent() → next item
```

Every gate that fails ends in a **notification**, never silence: the user is always told
that something arrived, even when it is not spoken.

## Text normalisation

`TextNormalizer.normalize(raw, emojiMode, maxChars)` is pure Kotlin, so its behaviour is
identical in the app, in unit tests, and anywhere else it is reused. It runs, in order:

1. **Unicode hygiene** — CRLF→LF, non-breaking spaces, zero-width characters, and
   bidi controls (used to spoof text) removed.
2. **Formatting stripping** — code blocks, inline code, `**bold**`/`*italic*`, blockquotes,
   headings and bullet markers lose their markup but keep their words.
3. **Links** — URLs and bare email addresses become "a link" (in `IGNORE` mode they are
   removed outright). Nobody wants to hear `h-t-t-p-s-colon-slash-slash`.
4. **Emoji policy** — see the table below. Emoji are consumed as whole clusters
   (variation selectors, ZWJ sequences, skin-tone modifiers, keycaps) so `❤️` and `👨‍👩‍👧` are
   handled as one unit.
5. **Chat shorthand** — `r u` → "are you", `pls`/`plz` → "please", `tmrw` → "tomorrow",
   `msg` → "message", `idk` → "I don't know", `asap` → "as soon as possible", `gr8` →
   "great", `im` → "I'm", `dont` → "don't", and friends. Word-boundary matched, so "sum"
   and "user" are untouched.
6. **Tidy** — repeated punctuation collapsed (`????` → `?`), repeated spaces collapsed,
   stray spaces before punctuation removed, ASCII emoticons (`:)`, `:(`) dropped,
   `<3` → "love", newlines converted to sentence breaks.
7. **Intonation** — the first letter is capitalised and a terminal `.` is added when the
   message ends without punctuation, because TTS engines read a bare word with a flat,
   robotic intonation.
8. **Truncation** — over `MAX_SPOKEN_CHARS` (320 by default), the message is cut at the last
   sentence boundary within the window (falling back to a word boundary) and ends with `…`,
   so the user hears a complete thought and knows it was shortened.

### Emoji modes

| Mode | `I love you ❤️😂` | `Please 📞 me` | `ok 👍` | Intent |
| --- | --- | --- | --- | --- |
| `IGNORE` | "I love you." | "Please me." | "Ok." | Never speak an emoji |
| `DESCRIBE_IMPORTANT` *(default)* | "I love you." — ❤️ redundant after "love" | "Please call me." | "Ok." — 👍 is decorative | Only emoji that change meaning |
| `READ_ALL` | "I love you love laughing." | "Please call me." | "Ok thumbs up." | Describe everything with a name |

`DESCRIBE_IMPORTANT` leans on a redundancy list: a description is dropped when the words
next to it already say the same thing (`❤️` after "love", `📞` after "call", `⚠️` after
"warning", `🚨` after "urgent"). That single rule is what keeps the reading from sounding
like a machine reading a transcript.

A message that reduces to nothing (`"😂😂😂"` in `IGNORE`, `"..."`, a bare link) is **not
speakable**: it is never queued, and it never claims the ledger entry, so if the user later
changes the emoji mode the message is still eligible.

### Hindi and Hinglish

The normaliser is language-agnostic by construction: no transliteration, no dictionary of
English words applied to Devanagari script, and Hindi text is passed through untouched
(`मैं आ रहा हूँ 👍` → "मैं आ रहा हूँ."). Hinglish gets only the Latin-script shorthand rules
(`bhai pls call karna` → "Bhai please call karna."). The engine is asked for the locale in
`SpeechSettings.languageTag` (`en-IN` by default) and falls back to the engine's default
voice when the exact locale is unavailable, logging the substitution instead of failing.

## The queue

`TtsQueue` guarantees **one utterance at a time**, always, with these rules:

| Rule | Behaviour |
| --- | --- |
| Sequential | `awaitNext()` refuses to hand out an item while another is `current`; the player cannot overlap utterances even if it tries |
| Dedupe (in memory) | The last 512 ids are remembered; re-enqueueing one returns `false` and increments `droppedCount` |
| Bounded | Default depth 20 (`ProductConfig.MAX_QUEUE_DEPTH`); beyond it, a `NORMAL` message is refused (and reported) rather than growing without limit |
| Priority | `SPEAK_NOW` is inserted before the first `NORMAL` item; when the queue is full it may **evict the oldest `NORMAL`** item to make room, and it never starves normal messages because it only ever takes one slot |
| Sender announcement | A message announces "Message from <name>" only when the sender differs from the previous utterance (or the message is `SPEAK_NOW`), so a burst of five messages from one person does not repeat the name five times |
| `SPEAK_NOW` phrasing | "Important. <text>" (plus "Message from <name>." when the sender changes) |
| Pause / resume | `pause()` stops the player from taking the next item; the current utterance is unaffected |
| Skip | Aborts the current utterance (`interruptions` emits its id so the player calls `Speech.stop()`), keeps the pending queue |
| Stop | Aborts the current utterance **and** empties the queue |
| Clear | Empties the pending queue without interrupting what is playing |
| Reset | Sign-out or "Call Assist off": state and recent-id memory cleared |

The queue is a pure Kotlin class with a `StateFlow<TtsQueueSnapshot>` (`current`, `pending`,
`paused`, `spokenCount`, `droppedCount`, `lastError`) — that is what the ongoing notification
and the Call Assist screen render, and what the unit tests drive with virtual time.

## Deduplication: exactly once, from three directions

The same message can reach the device multiple times:

1. the WebSocket delivers it, and FCM also delivers it (both flavors active);
2. the socket reconnects and the client re-fetches history;
3. the process is killed mid-queue and the message is evaluated again after restart.

Three layers stop that from becoming three utterances:

| Layer | Mechanism | Scope |
| --- | --- | --- |
| Storage | `LocalStore.upsertMessage()` never overwrites `spoken_at`, and `claimForSpeech()` is `UPDATE … SET spoken_at = ? WHERE client_message_id = ? AND spoken_at IS NULL` | Durable, survives restarts |
| Decision | `CallAssistEngine` only enqueues on `Claim.GRANTED`; `ALREADY_SPOKEN` and `UNKNOWN_MESSAGE` end the pipeline | Once per message, ever |
| Queue | In-memory recent-id set + "don't take an item twice" | Same process |

If synthesis *fails* the player calls `releaseSpeechClaim()`, which clears `spoken_at` so a
later attempt (or the next reconnect) can speak the message — a failure is not allowed to
silently consume a message. The claim is also released when the queue rejects an item after
the claim was taken, closing the one race between steps 6–8.

## Audio behaviour

* Attributes: `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`. Focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
  for the length of one utterance, abandoned immediately after.
* Whisper mode: the TTS parameter `KEY_PARAM_VOLUME` is set to 0.45 instead of the engine's
  default. This is a *quiet-mode* feature, not a claim that others cannot hear it.
* Rate/pitch come from `SpeechSettings` (defaults 1.0/1.0, clamped to 0.5–2.0 server-side).
* The synthesizer creates the engine on the main looper, waits for initialisation (5 s
  timeout), falls back to the engine's default voice if the requested locale/voice is
  missing, retries once after an error, and re-initialises an engine that dies mid-session.
* Reported route: `AudioRouter.currentRouteDescription()` names the output ("Bluetooth
  (Pixel Buds)", "Wired headset", "Speaker") for the UI. Read-only — the app never switches
  devices.
* Between utterances the player waits `pauseBetweenMessagesMs` (default 700 ms) so two
  messages do not run together into one confusing sentence.

## Failure handling

| Failure | What the user experiences |
| --- | --- |
| TTS engine missing or still initialising | The queue moves on; the Call Assist screen shows "Voice unavailable" with the engine name; the message stays un-spoken and can be retried |
| Speech interrupted by another app taking audio focus | The utterance completes or fails cleanly (`onStop`/`onError` are both terminal for the queue item) |
| Service killed mid-utterance | The claim for the in-flight message stays, the rest of the queue is re-evaluated on the next message; the ledger prevents repeats |
| Backend unreachable when reporting spoken | The receipt is best-effort; the local ledger already recorded the truth |
| Bluetooth route lost mid-utterance | The engine keeps speaking on the new default route; the UI updates the reported route |

## Testing the pipeline

`TextNormalizerTest` (rules above), `TtsQueueTest` (ordering, priority, dedupe, pause/skip/
stop), and `CallAssistEngineTest` (every gate, duplicate suppression, claim handling) run on
the JVM in `./gradlew testStandaloneDebugUnitTest` and in CI. Speech itself needs a device —
the manual device script in [testing.md](testing.md) covers it.
