# AirWhispers backend

Node 22 (>= 22.6, for `--experimental-strip-types`) + TypeScript + Fastify. Stores messages
in PostgreSQL for production, or in memory for development and tests. Sends realtime events over WebSocket and optional push through
Firebase Cloud Messaging (HTTP v1, no Google SDK required).

## Quick start

```bash
npm ci
npm run dev            # in-memory store, no database needed, listens on :8080
```

```bash
npm test               # type-check, API tests (node:test), build
npm run build          # -> dist/
npm start              # runs dist/src/server.js
```

Against a running server:

```bash
node scripts/smoke.mjs http://127.0.0.1:8080
```

The smoke test registers two users, opens a WebSocket, sends a message, marks it read and
spoken, and prints a line per check — it is the fastest way to confirm a deployment is sane.

## Configuration

Copy `.env.example` → `.env` (loaded with `--env-file` in `npm run dev`) or set real
environment variables.

| Variable | Default | Notes |
| --- | --- | --- |
| `PORT` | `8080` | |
| `HOST` | `0.0.0.0` | |
| `DATABASE_URL` | *(unset)* | Unset ⇒ in-memory store (dev only, data is lost on restart) |
| `JWT_SECRET` | *(dev fallback)* | **Required in production**, ≥ 32 characters; the server refuses to start with a short secret when `NODE_ENV=production` |
| `ACCESS_TOKEN_TTL_SECONDS` | `900` | |
| `REFRESH_TOKEN_TTL_DAYS` | `60` | |
| `TRUST_PROXY` | `false` | Set `true` behind a reverse proxy so rate limits use the real client IP |
| `LOG_LEVEL` | `info` | `debug`…`fatal` |
| `PUSH_INCLUDES_CONTENT` | `false` | Keep `false` unless you want message text inside push payloads (it then travels through Google's infrastructure) |
| `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY` | *(unset)* | From a Firebase service account. All three unset ⇒ push disabled, sockets only |

## Run with PostgreSQL

```bash
createdb airwhispers

DATABASE_URL=postgres://user:pass@localhost:5432/airwhispers \
JWT_SECRET="$(openssl rand -base64 48)" \
npm run schema      # applies sql/schema.sql idempotently
npm start
```

Or with Docker:

```bash
docker compose up -d --build      # postgres + backend
docker compose logs -f backend
```

## Deploy checklist

1. `JWT_SECRET` set to a fresh random value (`openssl rand -base64 48`).
2. `DATABASE_URL` pointing at a managed Postgres; `npm run schema` run once per deploy.
3. TLS terminated in front of the process; `TRUST_PROXY=true`.
4. Health probe on `GET /healthz` (the Dockerfile already declares it).
5. Rate limits reviewed (`register` 10/min, `login` 20/min, `refresh` 60/min, `send` 120/min
   per IP/user) — raise them only with an explanation.
6. Logs checked for the absence of message bodies and tokens (they never appear by design).

## Layout

```
src/
  config.ts        env parsing, validation and defaults
  crypto.ts        scrypt hashing, JWT sign/verify, refresh-token hashing
  types.ts         domain types + the Store interface both stores implement
  store.memory.ts  in-memory store (dev + tests)
  store.pg.ts      PostgreSQL store
  validators.ts    request validation helpers
  realtime.ts      WebSocket hub (auth frames, per-user sockets, heartbeats)
  push.ts          optional FCM HTTP v1 sender
  app.ts           routes, auth guards, rate limits, error envelope
  server.ts        bootstrap + graceful shutdown
sql/schema.sql     full schema, safe to re-run
tests/api.test.ts  API, authorization and idempotency tests
scripts/           apply-schema.ts, smoke.mjs
```

## Behaviour worth remembering

* `POST /conversations/:id/messages` is **idempotent per `(sender, clientMessageId)`**: a
  retry returns `200` with the original message instead of creating a second one, and
  fan-out happens only on the `201`.
* `POST /messages/:id/spoken` always answers `204`, even for an empty body — it is
  best-effort telemetry and must never cause a client retry storm.
* Receipts are recipient-only; anything else is `403`.
* New contacts default to `isTrusted: false`.
* The in-memory store and the Postgres store are interchangeable and are held to the same
  contract, which is why `npm test` needs no database.

Full REST and WebSocket reference: [`../docs/api.md`](../docs/api.md). Security model:
[`../docs/security.md`](../docs/security.md). Architecture:
[`../docs/architecture.md`](../docs/architecture.md).
