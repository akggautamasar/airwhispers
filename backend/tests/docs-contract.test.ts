/**
 * The API documentation is a promise, so it is tested like one.
 *
 * `docs/api.md` is the only place a client author looks to learn the error
 * envelope and the route list; drift there is invisible until somebody
 * integrates against it. The authorization-boundary tests found exactly that
 * kind of drift — the error codes were documented uppercase (`NOT_A_MEMBER`)
 * while the app answers lowercase `forbidden`, and validation failures were
 * documented as `422` while the app answers `400` — so this file keeps the
 * document honest:
 *
 *   1. every route in the document exists, and every registered route is in
 *      the document — no orphans in either direction;
 *   2. every `(status, code)` row of the documented error table is provoked
 *      against the real app and must come back exactly as documented.
 *
 * The route list comes from Fastify's own route tree, so renaming a route in
 * `src/app.ts` without touching `docs/api.md` fails here instead of silently.
 */

import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { MemoryStore } from "../src/store.memory.js";
import { RealtimeHub } from "../src/realtime.js";
import { FcmPusher } from "../src/push.js";
import { loadConfig } from "../src/config.js";
import { verifyAccessToken } from "../src/crypto.js";

/** Walks up from this file until it finds `docs/api.md` (dist/ does not copy docs/). */
function findApiDocs(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let depth = 0; depth < 6; depth += 1) {
    const candidate = join(dir, "docs", "api.md");
    try {
      readFileSync(candidate, "utf8");
      return candidate;
    } catch {
      dir = resolve(dir, "..");
    }
  }
  throw new Error("docs/api.md not found — the docs contract test cannot run");
}

const docs = readFileSync(findApiDocs(), "utf8");

/**
 * Routes the document names. Headings come in several shapes
 * (`### \`GET /api/v1/settings\` · \`PATCH /api/v1/settings\``, a table cell for
 * `realtime/info`), so any fully-qualified `METHOD /api/v1/…` mention counts.
 * Short-form mentions of the same path (as used in docs/security.md) do not.
 */
function documentedRoutes(): Set<string> {
  const routes = new Set<string>();
  const pattern = /`(GET|POST|PATCH|DELETE|PUT|HEAD) (\/api\/v1[A-Za-z0-9_:\-/]*|\/healthz)/g;
  for (const match of docs.matchAll(pattern)) {
    routes.add(`${match[1]} ${match[2]!.replace(/\/$/, "")}`);
  }
  return routes;
}

/**
 * Parses Fastify's `printRoutes({ commonPrefix: false })` tree into
 * `METHOD path` pairs. The tree nests segments by depth, so the absolute path
 * is the concatenation of the segments above it.
 */
function registeredRoutes(app: FastifyInstance): Set<string> {
  const tree = app.printRoutes({ commonPrefix: false });
  const out = new Set<string>();
  const stack: string[] = [];
  for (const line of tree.split("\n")) {
    const match = /^([\s│├└─]*)(\/\S*)\s*\(([^)]+)\)\s*$/.exec(line);
    if (!match) continue;
    const depth = Math.floor(match[1]!.length / 4) + 1;
    stack.length = depth - 1;
    stack[depth - 1] = match[2]!;
    const path = stack.join("");
    for (const method of match[3]!.split(",").map((m) => m.trim())) {
      if (method === "HEAD") continue; // Fastify registers HEAD alongside GET.
      out.add(`${method} ${path}`);
    }
  }
  assert.ok(out.size >= 20, `route tree parsed only ${out.size} routes — parser drift?`);
  return out;
}

interface Harness {
  app: FastifyInstance;
  close: () => Promise<void>;
}

async function harness(): Promise<Harness> {
  const config = loadConfig({
    NODE_ENV: "test",
    JWT_SECRET: "test-secret-that-is-long-enough-1234",
    LOG_LEVEL: "silent",
  });
  const store = new MemoryStore();
  const hub = new RealtimeHub({
    path: "/api/v1/realtime",
    verifyToken: (token) => verifyAccessToken(token, config.jwtSecret),
    log: () => {},
  });
  const app = buildApp({
    config,
    store,
    hub,
    pusher: new FcmPusher(config, () => {}),
    version: "docs-contract",
  });
  await app.ready();
  return { app, close: () => app.close() };
}

describe("docs/api.md is kept honest", () => {
  test("every documented route exists and every route is documented", async () => {
    const h = await harness();
    try {
      const registered = registeredRoutes(h.app);
      const documented = documentedRoutes();

      for (const route of documented) {
        const [method, path] = route.split(" ") as [string, string];
        assert.ok(
          h.app.hasRoute({ method: method as "GET", url: path }),
          `docs/api.md documents \`${route}\` but no such route is registered`,
        );
      }

      // `/api/v1/health` is a documented alias of `/healthz`, and creating a
      // conversation is an upsert described in prose, so the sets are compared
      // after removing that alias.
      const undocumented = [...registered]
        .filter((route) => !route.endsWith("/api/v1/health"))
        .filter((route) => !documented.has(route));
      assert.deepEqual(undocumented, [], "routes exist that docs/api.md never mentions");
    } finally {
      await h.close();
    }
  });

  test("every documented error code is what the API actually answers", async () => {
    // Parse the error table: | `400` | `bad_request` | ... |
    const table = [...docs.matchAll(/^\| `(\d{3})` \| `([a-z_]+)` \|/gm)].map((m) => ({
      status: Number(m[1]),
      code: m[2]!,
    }));
    assert.ok(table.length >= 6, `error table parsed only ${table.length} rows`);

    const h = await harness();
    try {
      const app = h.app;
      const register = (email: string, displayName = "Docs") =>
        app.inject({
          method: "POST",
          url: "/api/v1/auth/register",
          payload: { email, password: "correct horse battery", displayName },
        });
      const auth = (token: string) => ({ authorization: `Bearer ${token}` });

      // Registrations happen before the 429 probe, because the register limiter
      // is 10/minute per IP and would otherwise block the setup.
      const alice = await register("docs-alice@example.com");
      assert.equal(alice.statusCode, 201, alice.body);
      const bob = await register("docs-bob@example.com", "Bob");
      const carol = await register("docs-carol@example.com", "Carol");

      // 400 bad_request — a field that fails validation.
      const badRequest = await app.inject({
        method: "POST",
        url: "/api/v1/auth/register",
        payload: { email: "not-an-email", password: "correct horse battery", displayName: "Docs" },
      });
      // 401 unauthorized — no token.
      const unauthorized = await app.inject({ method: "GET", url: "/api/v1/users/me" });
      // 404 not_found — unknown endpoint.
      const notFound = await app.inject({ method: "GET", url: "/api/v1/does-not-exist" });
      // 409 conflict — the same email twice.
      const conflict = await register("docs-alice@example.com");
      // 403 forbidden — a third user reaching into somebody else's conversation.
      const contact = await app.inject({
        method: "POST",
        url: "/api/v1/contacts",
        headers: auth(alice.json().tokens.accessToken),
        payload: { email: "docs-bob@example.com" },
      });
      assert.equal(contact.statusCode, 201, contact.body);
      const conversationId = contact.json().contact.conversationId as string;

      const send = await app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: auth(alice.json().tokens.accessToken),
        payload: { clientMessageId: "docs-1", text: "Reached home?", priority: "NORMAL" },
      });
      assert.equal(send.statusCode, 201, send.body);

      const stranger = await app.inject({
        method: "GET",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: auth(carol.json().tokens.accessToken),
      });
      // 429 rate_limited — the login limiter is 20/minute per IP; probing it last
      // keeps the register and send limits untouched.
      let limited = await app.inject({
        method: "POST",
        url: "/api/v1/auth/login",
        payload: { email: "docs-alice@example.com", password: "wrong horse battery" },
      });
      for (let i = 0; limited.statusCode !== 429 && i < 30; i += 1) {
        limited = await app.inject({
          method: "POST",
          url: "/api/v1/auth/login",
          payload: { email: "docs-alice@example.com", password: "wrong horse battery" },
        });
      }

      const observed: Record<number, { status: number; code: string }> = {
        400: { status: badRequest.statusCode, code: badRequest.json().error.code },
        401: { status: unauthorized.statusCode, code: unauthorized.json().error.code },
        403: { status: stranger.statusCode, code: stranger.json().error.code },
        404: { status: notFound.statusCode, code: notFound.json().error.code },
        409: { status: conflict.statusCode, code: conflict.json().error.code },
        429: { status: limited.statusCode, code: limited.json().error.code },
      };

      // Documented but deliberately unprovoked: `500 internal_error` cannot be
      // triggered from the outside. Anything else must be provoked, so a new
      // documented code cannot slip through unverified.
      const unprovoked = new Set([500]);

      for (const row of table) {
        assert.equal(
          row.code,
          row.code.toLowerCase(),
          `docs/api.md documents an uppercase code (${row.code}); the API answers lowercase`,
        );
        if (unprovoked.has(row.status)) continue;
        const saw = observed[row.status];
        assert.ok(saw, `docs/api.md documents ${row.status} ${row.code} but nothing provokes it`);
        assert.equal(saw.status, row.status, `status for documented ${row.status} ${row.code}`);
        assert.equal(saw.code, row.code, `docs/api.md promises ${row.status} ${row.code} …`);
      }

      // The receipt rules documented alongside the table: recipient-only.
      const spokenBySender = await app.inject({
        method: "POST",
        url: `/api/v1/messages/${send.json().message.id}/spoken`,
        headers: auth(alice.json().tokens.accessToken),
      });
      assert.equal(spokenBySender.statusCode, 403, "only the recipient reports speech");
      const spokenByRecipient = await app.inject({
        method: "POST",
        url: `/api/v1/messages/${send.json().message.id}/spoken`,
        headers: auth(bob.json().tokens.accessToken),
      });
      assert.equal(spokenByRecipient.statusCode, 204);
      const readByRecipient = await app.inject({
        method: "POST",
        url: `/api/v1/messages/${send.json().message.id}/read`,
        headers: auth(bob.json().tokens.accessToken),
      });
      assert.equal(readByRecipient.statusCode, 204, "the read receipt is fire-and-forget");
    } finally {
      await h.close();
    }
  });
});
