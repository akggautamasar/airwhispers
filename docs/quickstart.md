# Quickstart — the server, the app, and your first whisper

This is the shortest honest path from nothing to "a friend's message was spoken quietly
into my ear while I was on a call".

There are three things involved:

| Thing | What it is | Where it runs |
| --- | --- | --- |
| **The server** | A small Node program that hands out codes and relays messages between phones | Your laptop, a cheap VPS, or `docker compose` |
| **The app** | The Android app that speaks messages to you | Your phone |
| **The code** | Six random characters (`k7m2pq`) that identify one device | Shown on the app's home screen |

There is nothing else: no account, no email, no password, no phone number, no verification
SMS. A device registers once and is recognised forever after.

---

## 1. Run the server

### Locally (simplest)

```bash
cd backend
npm ci
npm run dev
```

`npm run dev` compiles first and then serves, so it behaves exactly like production. The console
prints the address, for example:

```
{"level":"info","message":"server.listening","port":8080,"host":"0.0.0.0","version":"1.0.0","push":false}
```

Open `http://localhost:8080/` on the machine running it and you get the **browser test
console** — two tabs are two devices, and you can see the whole flow without installing
anything.

> The local run keeps everything in memory. Restart it and every device is issued a new
> code. That is fine for trying things out; use the container below for anything you want
> to keep.

### For keeps (PostgreSQL)

```bash
export JWT_SECRET=$(openssl rand -base64 48)   # at least 32 characters
docker compose up --build
```

or, without Docker:

```bash
cd backend
npm ci
export DATABASE_URL=postgres://user:pass@host:5432/airwhispers
export JWT_SECRET=$(openssl rand -base64 48)
npm run schema      # creates the tables
npm start
```

Notes that matter in the real world:

* The phone must be able to reach the address you type into the app. On a home network
  that is the laptop's LAN IP (`http://192.168.1.10:8080`). Over the internet, put it
  behind HTTPS (a reverse proxy with a certificate) — then the app gets an `https://`
  address and the little "this is not encrypted" warning disappears.
* `ALLOW_NEW_DEVICES=false` closes the server to newcomers. Existing devices keep working
  because they resume with the secret they were given at registration.
* `PUSH_INCLUDES_CONTENT=false` (default) keeps message bodies out of any cloud push
  payload; only ids travel.

---

## 2. Install the app

**From a release** — download `app-standalone-release.apk` from the repository's Releases
page, verify it against the matching `.sha256` file, and install it (Android will ask you
to allow installing from this source).

**From source:**

```bash
cd android
bash scripts/make-keystore.sh
./gradlew assembleStandaloneRelease
# app/build/outputs/apk/standalone/release/app-standalone-release.apk
```

Needs JDK 17 and the Android SDK (compileSdk 35).

---

## 3. Register this phone (two fields, once)

Open the app:

1. **Server address** — type the address from step 1 and tap *Check server*. The app asks
   the server what it expects and tells you if the address is wrong. (You can skip the
   check, but there is no reason to.)
2. **Your name** — only people you give your code to will see it. Tap **Get my code**.

The home screen now shows your code in big letters:

```
YOUR CODE
K7M 2PQ
This code is this phone. Anyone you give it to can start a chat …
```

Buttons next to it: **Copy** and **Share**. That message is all a friend needs.

> Behind the scenes the app also received a long random *device secret*, which it keeps in
> the Android Keystore. That secret is what lets the app fetch fresh tokens forever with no
> login screen — see [security.md](security.md).

---

## 4. Pair two phones (or two browser tabs)

On phone A: **✏️ → type phone B's code → Start**. The chat opens. Do the same on B (or let
B just answer the chat A created).

In the browser, this is the same thing: two tabs on the test console each get their own
code, and you type one into the other.

What you get immediately:

* realtime delivery over one WebSocket (no polling),
* *online* / *offline* / *typing…* / *read*,
* history that survives a restart (with PostgreSQL).

---

## 5. Allow someone to whisper to you

Speech is **off for everyone by default**. In a chat, the header shows `🔇 muted`; tap it
and it becomes `🔊 whispers on`. From that moment, messages from that person may be spoken
aloud on this device. Tap again to mute them.

The same list is on the **Call Assist → Who may whisper to you** card, so you can see all
of them in one place.

Consent is one-directional and owned by the listener: a sender can *ask* for their message
to be spoken ("🔊 Whisper"), but only your switch decides whether this phone speaks.

---

## 6. Hear it during a call

1. Call Assist tab → **Start listening** (this also switches on *Speak incoming messages*).
   You can start it from the quick-settings tile or the notification action too.
2. Take a call in WhatsApp / Meet / Discord / the dialer as usual. AirWhispers does not
   touch that call.
3. Your friend sends *"Are you alone?"* — or taps **🔊 Whisper** if it is urgent.
4. The phone speaks it to you through the current output (phone speaker, wired headset or
   Bluetooth — whichever the system is using), **quietly**, because *Whisper mode* is on by
   default. The queue keeps messages from overlapping; the notification has pause / skip /
   stop.

What you will *not* hear: the other side of your call never hears it, because AirWhispers
never joins or modifies the call audio path. See
[android-limitations.md](android-limitations.md) for exactly what Android allows.

---

## Common questions

**Do both phones need the same server?** Yes. The server is the meeting point; a code only
means something on the server that issued it.

**What happens if I reinstall the app?** The new installation has no secret, so it gets a
new code and a new identity. Your old chats stay with the old code. (Nothing is
recoverable by design — that is the price of having no account.)

**Can I change my code?** Settings → *Get a new code*. The old identity is forgotten on the
server; people who only knew the old code can no longer reach you.

**Someone I don't know keeps messaging me.** Mute speech for them (or never allow it), and
ignore the chat — a code is only known if you shared it. `ALLOW_NEW_DEVICES=false` stops
strangers from registering on your server at all. Rate limits throttle message and chat
creation per device.

**Does it work with the screen off / phone locked?** Yes, while Call Assist is armed
(foreground service). Speech uses the device's own TTS engine, so nothing leaves the phone.

**Is my message text sent to Google?** Only if you build the `fcm` flavor *and* set
`PUSH_INCLUDES_CONTENT=true`. The standalone flavor and the default configuration send
ids and metadata only; the app fetches the body over TLS from your server.
