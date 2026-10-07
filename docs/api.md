# API reference

Base URL: `https://<host>` · All REST routes live under `/api/v1` unless noted.
Content type is `application/json; charset=utf-8`. Timestamps are epoch **milliseconds**.
IDs are UUID strings.

## Authentication

Two token kinds:

* **Access token** — JWT (HS256), `Authorization: Bearer <token>`, 15 minutes, carries
  `{ sub, email, typ: "access", iat, exp }`.
* **Refresh token** — opaque 32-byte random string, returned once, stored server-side as a
  SHA-256 hash, valid for 60 days, **rotated on every use** (reuse of a rotated token is
  rejected).

### `POST /api/v1/auth/register`

```json
{ "email": "a@example.com", "password": "correct horse battery", "displayName": "Asha" }
```

| Response | Body |
| --- | --- |
| `201` | `{ "user": {...}, "tokens": { "accessToken": "...", "refreshToken": "...", "expiresIn": 900, "tokenType": "Bearer" } }` |
| `409` | `{"error":{"code":"EMAIL_TAKEN","message":"..."}}` |
| `422` | validation error (bad email, password shorter than 8 characters) |

### `POST /api/v1/auth/login`

Same request shape (email + password). `200` with the same `{ user, tokens }` payload.
`401 INVALID_CREDENTIALS` on a bad pair — identical response whether the email exists or not.
Rate limited to 20 requests/minute per IP (register: 10).

### `POST /api/v1/auth/refresh`

```json
{ "refreshToken": "..." }
```

`200` with a **new** access token *and* a new refresh token (the token object itself, not a
`user`); the old refresh token is invalidated.
`401 INVALID_REFRESH_TOKEN` when it is unknown, expired, or already rotated.

### `POST /api/v1/auth/logout`

`{ "refreshToken": "..." }` or an **empty body** (both accepted). Revokes the token family
for that device. `204`, no body. Idempotent.

### `GET /api/v1/users/me`

`200 { "user": { id, email, displayName, createdAt } }`. Use it on app start to validate a
restored session.

### `PATCH /api/v1/users/me`

`{ "displayName": "Asha" }` → `200 { "user": {...} }`. Only the display name is editable.

## Conversations

### `GET /api/v1/conversations`

`200 { "conversations": [ { id, peer: {…}, lastMessage, unreadCount, updatedAt } ] }` —
ordered by recent activity, scoped to the caller.

### `POST /api/v1/conversations`

```json
{ "participantEmail": "b@example.com" }
```

`201` with the conversation, or `200` if a conversation with that peer already exists
(idempotent by `pair_key`). `404 USER_NOT_FOUND` if the email is unknown — the app then
offers to add the person as a contact instead of leaking more information.

### `GET /api/v1/conversations/:id/messages?limit=50&before=<millis>`

`200 { "messages": [ … ] }`, newest-last, paginated backwards by `before`.
Reading history from the recipient stamps `delivered_at` on messages that had none and emits
`message.updated` to the sender. `403 NOT_A_MEMBER` for a conversation the caller is not in.

### `POST /api/v1/conversations/:id/messages`

```json
{ "clientMessageId": "5f8c…", "text": "Reached home?", "priority": "NORMAL" }
```

* `clientMessageId` is the **idempotency key**; the app generates it once per message and
  reuses it on every retry.
* `priority` is `NORMAL` (default) or `SPEAK_NOW`.
* `201` on create; `200` with the **original** message if this `(sender, clientMessageId)`
  pair already exists — so retrying a send never duplicates.
* Fan-out (`message.created` over WebSocket, push to the recipient's devices) happens **only
  on create**.
* Rate limited to 120 sends/minute per user. `422` when `text` is empty or longer than 4000
  characters.

## Message receipts

### `POST /api/v1/messages/:id/read`

Recipient only (`403 NOT_RECIPIENT` otherwise). Sets `read_at` once; emits `message.read`
to the sender; `200` with the updated message. Idempotent.

### `POST /api/v1/messages/:id/spoken`

Marks that the text-to-speech pipeline actually spoke this message — a product-level event
distinct from "read". Recipient only. Idempotent, `204` *always* (including when already
marked, or when called with an **empty body**), because it is telemetry and must never
retry-storm.

## Contacts and trust

### `GET /api/v1/contacts`

`200 { "contacts": [ { id, user: {…}, isTrusted, createdAt } ] }`

### `POST /api/v1/contacts`

```json
{ "email": "b@example.com" }
```

`201` with the contact. New contacts are created with **`isTrusted: false`** — Call Assist's
privacy-first default refuses to speak strangers. `404 USER_NOT_FOUND` for unknown emails.

### `PATCH /api/v1/contacts/:id`

```json
{ "isTrusted": true }
```

`200` with the updated contact. Contact creation is also possible implicitly: if the target
user exists but no contact row does yet, the call upserts it (so "trust this sender" from a
notification never fails). `404 USER_NOT_FOUND` only when the target user itself is unknown.

### `DELETE /api/v1/contacts/:id`

`204`. Removes the relationship from the caller's list.

## Settings

### `GET /api/v1/settings` · `PATCH /api/v1/settings`

```json
{
  "speakMessages": false,
  "onlyDuringCalls": true,
  "trustedContactsOnly": true,
  "preferBluetooth": true,
  "languageTag": "en-IN",
  "speechRate": 1.0,
  "speechPitch": 1.0,
  "emojiMode": "DESCRIBE_IMPORTANT"
}
```

Defaults are **opt-in and private**: nothing is spoken until `speakMessages` is true,
only during calls, and only from trusted contacts. `emojiMode` is one of `IGNORE`
(default posture for most brand emojis), `DESCRIBE_IMPORTANT` (❤️ → "heart") or `READ_ALL`.
`PATCH` accepts any subset; unknown fields are ignored; `speechRate`/`speechPitch` are
clamped to `0.5–2.0` / `0.5–2.0`. The response always contains the full settings object.

## Devices (push)

### `POST /api/v1/devices`

```json
{ "deviceId": "…", "platform": "android", "pushToken": "…", "appVersion": "1.0.0" }
```

Registers or updates this device. Send `"pushToken": null` to unregister. The backend prunes
tokens that FCM rejects, so a stale token cannot keep a device "reachable" forever.

### `DELETE /api/v1/devices/:deviceId`

`204`, no body. Used on sign-out so a shared phone stops receiving pushes for the previous
account. Idempotent.

## Health

| Route | Notes |
| --- | --- |
| `GET /healthz` | Outside `/api/v1` for load balancers: `{ "status": "ok", "version": "1.0.0" }` |
| `GET /api/v1/health` | Versioned equivalent |
| `GET /api/v1/realtime/info` | `{ "authenticated": bool, "online": n }` — useful for diagnostics |

## WebSocket

Connect to `wss://<host>/api/v1/realtime`. **Tokens never go in the query string** (they end
up in proxy logs): the first frame authenticates the socket.

```json
// client → server
{ "type": "auth", "token": "<access token>", "deviceId": "…" }
{ "type": "ping" }

// server → client
{ "type": "auth.ok", "userId": "…" }
{ "type": "auth.required", "reason": "expired" }   // refresh, then send auth again
{ "type": "pong" }
{ "type": "message.created", "message": { … }, "conversationId": "…" }
{ "type": "message.updated", "message": { … } }
{ "type": "message.read",    "messageId": "…", "readAt": 1712345678901 }
{ "type": "conversation.updated", "conversation": { … } }
{ "type": "contact.updated", "contact": { … } }
{ "type": "error", "error": { "code": "…", "message": "…" } }
```

Behaviour: unauthenticated sockets receive `auth.required` and are closed after a grace
period; the server replies `pong` to `ping` (the client uses this as a heartbeat); buffered
events are **not** replayed on reconnect — the client re-fetches history, and idempotent
message upserts plus the spoken ledger make that safe.

## Errors

Every failure uses one envelope:

```json
{ "error": { "code": "NOT_A_MEMBER", "message": "You are not a member of this conversation." } }
```

| Status | When |
| --- | --- |
| `400` | Malformed JSON (including a body that is not an object) |
| `401` | Missing/expired/invalid token, bad credentials, bad refresh token |
| `403` | Authenticated but not allowed (not a member, not the recipient) |
| `404` | Unknown conversation, message, or user |
| `409` | Conflict (email taken, pair already exists) |
| `422` | Validation failure |
| `429` | Rate limited — includes `retryAfterMs` |
| `500` | Unexpected; logged with a request id, never with message content |

The Android client maps these to typed `AppError`s, distinguishing retryable
(`NETWORK`, `TIMEOUT`, `SERVER`, `RATE_LIMITED`) from terminal (`UNAUTHORIZED`,
`VALIDATION`, `NOT_FOUND`) so the outbox does not retry hopeless sends forever.

## Client behaviour contract

* On `auth.required`, refresh the access token and re-authenticate the socket.
* On a `401` anywhere, refresh once; if refresh fails, sign out locally and keep the queued
  messages in the outbox.
* Never send `clientMessageId` twice with different content — reuse it on retry, regenerate
  it for a genuinely new message.
* Treat push and socket delivery as the same ingestion path: dedupe by message id.

## Compatibility

`/api/v1` is frozen for the 1.x line. Additive fields are always allowed; removing or
renaming a field requires `/api/v2`. A client that ignores unknown fields (as the Android
DTOs do) will keep working across 1.x releases.
