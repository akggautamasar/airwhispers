# AirWhispers backend

A small Node 22 + TypeScript + Fastify service that hands devices their codes and relays
messages between them in realtime (REST + WebSocket).

* **No accounts.** A device registers once, gets a 6-character code and a device secret
  (stored as an scrypt hash). The secret buys fresh access tokens forever, with no login UI.
* **Two stores.** In-memory for `npm run dev` (zero infrastructure, resets on restart) and
  PostgreSQL for anything permanent.
* **Optional push.** FCM (HTTP v1) is used only for the `fcm` Android flavor and only when
  the recipient has no open socket.

## Run it

```bash
npm ci
npm run dev            # in-memory store + demo console on http://localhost:8080/
npm test               # type-check + 20 API tests + build
node scripts/smoke.mjs # live end-to-end check against a running server
```

Production:

```bash
export DATABASE_URL=postgres://user:pass@host:5432/airwhispers
export JWT_SECRET=$(openssl rand -base64 48)
npm ci && npm run schema && npm start
# or, from the repository root:
docker compose up --build
```

## The browser test console

`GET /` (and `GET /demo`) serves `public/index.html` — a single static page that speaks the
same public API as the Android app. Two browser tabs are two devices: each gets a code, you
pair them by code, chat in realtime, toggle "let them whisper to me", and *Whisper now*
messages are spoken locally so you can see the whole flow without a phone. It is a test
bench, not a second product.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` / `HOST` | `8080` / `0.0.0.0` | Listen address |
| `DATABASE_URL` | *(unset)* | Postgres; without it the in-memory store is used |
| `JWT_SECRET` | dev fallback | **Required in production** (≥ 32 chars) |
| `ACCESS_TOKEN_TTL_SECONDS` | `3600` | Access token lifetime; refresh is silent |
| `ALLOW_NEW_DEVICES` | `true` | `false` closes registration; existing devices keep working |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | *(unset)* | Enables FCM push (one-line JSON) |
| `FCM_PROJECT_ID` | *(unset)* | Overrides the project id in the service account |
| `PUSH_INCLUDES_CONTENT` | `false` | Send message text in the push payload (off by default) |
| `TRUST_PROXY` | `false` | Trust `X-Forwarded-For` (only behind a known proxy) |
| `LOG_LEVEL` | `debug`/`info` | Fastify log level; message bodies are never logged |

## Layout

```
src/
  app.ts            every HTTP route + the realtime-server wiring, in one auditable file
  config.ts         environment → typed config with safe defaults
  crypto.ts         scrypt secret hashing, HS256 tokens, code generation, FCM assertion
  validators.ts     validation + the error classes the error handler maps to statuses
  types.ts          domain types and the Store contract (memory | pg)
  store.memory.ts   in-memory store (dev, tests, single-node demos)
  store.pg.ts       PostgreSQL store (production)
  realtime.ts       WebSocket hub: auth, presence fan-out, typing relay, heartbeat
  push.ts           FCM HTTP v1 sender (data-only messages, no Google SDK)
  server.ts         wiring, logging, graceful shutdown
public/index.html   the browser test console
sql/schema.sql      PostgreSQL schema (no email, no password, no settings tables)
tests/api.test.ts   20 end-to-end tests over the in-memory store
scripts/smoke.mjs   live end-to-end script (two devices, socket, consent, receipts)
```

## Data model, briefly

```
users(id, code UNIQUE, display_name, created_at)
device_credentials(device_id, user_id, secret_hash, platform, app_version,
                   created_at, last_seen_at)            -- PK (device_id, user_id)
conversations(id, pair_key UNIQUE, created_at)
conversation_members(conversation_id, user_id)
messages(id, client_message_id, conversation_id, sender_id, recipient_id, text,
         priority, created_at, delivered_at, read_at, spoken_at)
         UNIQUE (sender_id, client_message_id)          -- idempotent sends
trusts(owner_user_id, peer_user_id, trusted, created_at, updated_at)
devices(device_id, user_id, platform, push_token, app_version, updated_at)
```

Speech settings live only on the phone (voices differ per device); the server stores
exactly one social fact per pair — "this owner lets that peer whisper to them" — and it is
owned by the listener.

See [../docs/api.md](../docs/api.md) for the full API and
[../docs/quickstart.md](../docs/quickstart.md) for the end-to-end walkthrough.
