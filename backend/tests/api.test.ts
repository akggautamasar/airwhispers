import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { MemoryStore } from "../src/store.memory.js";
import { RealtimeHub } from "../src/realtime.js";
import { FcmPusher } from "../src/push.js";
import { loadConfig } from "../src/config.js";
import { hashSecret, normaliseCode, verifyAccessToken, verifySecret } from "../src/crypto.js";
import { CODE_PATTERN } from "../src/types.js";
import type { Store } from "../src/types.js";

/**
 * End-to-end API tests over the in-memory store — the same code paths production
 * uses, minus Postgres.
 *
 * The identity model under test: no accounts, a device registers once, keeps a
 * secret, and is recognised by its code.
 */

interface Harness {
  app: FastifyInstance;
  store: Store;
  close: () => Promise<void>;
}

async function harness(): Promise<Harness> {
  const config = loadConfig({
    NODE_ENV: "test",
    JWT_SECRET: "test-secret-that-is-long-enough-1234",
    // Keep the test output readable; the assertions do not depend on logging.
    LOG_LEVEL: "silent",
  });
  const store = new MemoryStore();
  const hub = new RealtimeHub({
    path: "/api/v1/realtime",
    verifyToken: (token) => verifyAccessToken(token, config.jwtSecret),
    presenceAudience: (userId) => store.peerIdsOf(userId),
    conversationMembers: async (id) => (await store.findConversation(id))?.memberIds ?? [],
    log: () => {},
  });
  const pusher = new FcmPusher(config, () => {});
  const app = buildApp({ config, store, hub, pusher, version: "test" });
  return {
    app,
    store,
    close: async () => {
      hub.close();
      await app.close();
      await store.close();
    },
  };
}

interface RegisteredDevice {
  id: string;
  code: string;
  deviceId: string;
  secret: string;
  accessToken: string;
  auth: (extra?: Record<string, string>) => Record<string, string>;
}

/** Registers a brand-new device through the public API, exactly as the app does. */
async function registerDevice(app: FastifyInstance, displayName = "Phone"): Promise<RegisteredDevice> {
  const deviceId = `device-${randomUUID()}`;
  const response = await app.inject({
    method: "POST",
    url: "/api/v1/device/register",
    payload: { deviceId, displayName, platform: "test" },
  });
  assert.equal(response.statusCode, 201, response.body);
  const body = response.json() as {
    user: { id: string; code: string };
    tokens: { accessToken: string };
    deviceSecret: string;
  };
  return {
    id: body.user.id,
    code: body.user.code,
    deviceId,
    secret: body.deviceSecret,
    accessToken: body.tokens.accessToken,
    auth: (extra = {}) => ({ authorization: `Bearer ${body.tokens.accessToken}`, ...extra }),
  };
}

describe("device identity", () => {
  test("a new device gets a code, a secret and a token without any sign-up", async () => {
    const h = await harness();
    try {
      const device = await registerDevice(h.app, "Anu's phone");
      assert.match(device.code, CODE_PATTERN);
      assert.equal(device.code.length, 6);
      assert.ok(device.secret.length >= 16);

      const me = await h.app.inject({ method: "GET", url: "/api/v1/me", headers: device.auth() });
      assert.equal(me.statusCode, 200);
      const user = me.json().user;
      assert.equal(user.code, device.code);
      assert.equal(user.displayName, "Anu's phone");
      assert.equal(user.email, undefined, "there is no email in this product");
    } finally {
      await h.close();
    }
  });

  test("codes are unique across devices", async () => {
    const h = await harness();
    try {
      const codes = new Set<string>();
      for (let i = 0; i < 25; i++) {
        const device = await registerDevice(h.app, `Phone ${i}`);
        assert.ok(!codes.has(device.code), `duplicate code ${device.code}`);
        codes.add(device.code);
      }
    } finally {
      await h.close();
    }
  });

  test("a known device resumes with its secret — no re-registration", async () => {
    const h = await harness();
    try {
      const first = await registerDevice(h.app, "Anu");
      const resume = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/register",
        payload: { deviceId: first.deviceId, deviceSecret: first.secret },
      });
      assert.equal(resume.statusCode, 200, resume.body);
      assert.equal(resume.json().user.code, first.code, "the code must survive");
      assert.equal(resume.json().deviceSecret, undefined, "the secret is only ever issued once");
    } finally {
      await h.close();
    }
  });

  test("a wrong secret is rejected, and a fresh secret means a fresh identity", async () => {
    const h = await harness();
    try {
      const device = await registerDevice(h.app, "Anu");

      const forged = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/register",
        payload: { deviceId: device.deviceId, deviceSecret: "x".repeat(43) },
      });
      assert.equal(forged.statusCode, 401);

      // Same physical device, no valid secret: it simply becomes a new identity.
      const reinstall = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/register",
        payload: { deviceId: device.deviceId, displayName: "Anu again" },
      });
      assert.equal(reinstall.statusCode, 201);
      assert.notEqual(reinstall.json().user.code, device.code);
    } finally {
      await h.close();
    }
  });

  test("access tokens can be refreshed silently, forever", async () => {
    const h = await harness();
    try {
      const device = await registerDevice(h.app, "Anu");
      const refreshed = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/token",
        payload: { deviceId: device.deviceId, deviceSecret: device.secret },
      });
      assert.equal(refreshed.statusCode, 200);
      const body = refreshed.json() as { tokens: { accessToken: string } };
      const claims = verifyAccessToken(body.tokens.accessToken, "test-secret-that-is-long-enough-1234");
      assert.equal(claims?.sub, device.id);

      const bad = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/token",
        payload: { deviceId: device.deviceId, deviceSecret: "not-the-secret-but-long-enough" },
      });
      assert.equal(bad.statusCode, 401);
    } finally {
      await h.close();
    }
  });

  test("forgetting a device invalidates its secret and frees it for a new code", async () => {
    const h = await harness();
    try {
      const device = await registerDevice(h.app, "Anu");
      const forget = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/forget",
        payload: { deviceId: device.deviceId, deviceSecret: device.secret },
      });
      assert.equal(forget.statusCode, 204);

      const oldSecret = await h.app.inject({
        method: "POST",
        url: "/api/v1/device/token",
        payload: { deviceId: device.deviceId, deviceSecret: device.secret },
      });
      assert.equal(oldSecret.statusCode, 401, "a forgotten secret must be dead");

      const fresh = await registerDevice(h.app, "Anu");
      assert.match(fresh.code, CODE_PATTERN);
    } finally {
      await h.close();
    }
  });

  test("secrets are stored hashed, never recoverable", async () => {
    const secret = "a-very-secret-device-secret";
    const hash = await hashSecret(secret);
    assert.ok(!hash.includes(secret));
    assert.equal(await verifySecret(secret, hash), true);
    assert.equal(await verifySecret("another-secret", hash), false);
  });

  test("protected endpoints require a valid token", async () => {
    const h = await harness();
    try {
      const anonymous = await h.app.inject({ method: "GET", url: "/api/v1/conversations" });
      assert.equal(anonymous.statusCode, 401);

      const forged = await h.app.inject({
        method: "GET",
        url: "/api/v1/conversations",
        headers: { authorization: "Bearer not.a.token" },
      });
      assert.equal(forged.statusCode, 401);
    } finally {
      await h.close();
    }
  });

  test("registration is rate limited per client", async () => {
    const h = await harness();
    try {
      let limited = false;
      for (let attempt = 0; attempt < 70; attempt++) {
        const response = await h.app.inject({
          method: "POST",
          url: "/api/v1/device/register",
          payload: { deviceId: `device-${randomUUID()}`, displayName: `Burst ${attempt}` },
        });
        if (response.statusCode === 429) {
          limited = true;
          assert.ok(response.json().error.retryAfterMs > 0);
          break;
        }
      }
      assert.ok(limited, "expected a 429 after repeated registrations");
    } finally {
      await h.close();
    }
  });

  test("the server advertises that it needs no account", async () => {
    const h = await harness();
    try {
      const info = await h.app.inject({ method: "GET", url: "/api/v1/server" });
      assert.equal(info.statusCode, 200);
      const body = info.json() as { registration: string; realtimePath: string; codeLength: number };
      assert.equal(body.registration, "device");
      assert.equal(body.realtimePath, "/api/v1/realtime");
      assert.equal(body.codeLength, 6);
    } finally {
      await h.close();
    }
  });
});

describe("chat by code", () => {
  test("one device opens a chat with another using only its code", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");

      // Humans type codes carelessly: uppercase, spaces, dashes all work.
      const messy = `${bob.code.slice(0, 3).toUpperCase()}-${bob.code.slice(3).toUpperCase()}`;
      const open = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: messy },
      });
      assert.equal(open.statusCode, 201, open.body);
      const conversation = open.json().conversation as {
        id: string;
        peer: { id: string; code: string; displayName: string };
        youAllowSpeak: boolean;
        peerAllowsSpeak: boolean;
      };
      assert.equal(conversation.peer.code, bob.code);
      assert.equal(conversation.peer.displayName, "Bob");
      assert.equal(conversation.youAllowSpeak, false, "speech is opt-in");
      assert.equal(conversation.peerAllowsSpeak, false);

      // Opening it twice must not create a second chat.
      const again = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      assert.equal(again.json().conversation.id, conversation.id);
    } finally {
      await h.close();
    }
  });

  test("unknown codes and your own code are refused clearly", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const unknown = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: "zzzz99" },
      });
      assert.equal(unknown.statusCode, 404);

      const self = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: anu.code },
      });
      assert.equal(self.statusCode, 400);

      const malformed = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: "nope" },
      });
      assert.equal(malformed.statusCode, 400);
    } finally {
      await h.close();
    }
  });

  test("messages flow, replay is idempotent, read state is tracked", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      const chat = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      const conversationId = chat.json().conversation.id as string;

      let send = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "client-1", text: "Are you alone?", priority: "SPEAK_NOW" },
      });
      assert.equal(send.statusCode, 201, send.body);
      const message = send.json().message as { id: string; priority: string; speakEligible: boolean };
      assert.equal(message.priority, "SPEAK_NOW");
      assert.equal(message.speakEligible, false, "Bob has not allowed speech yet");

      const replay = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "client-1", text: "Are you alone?", priority: "SPEAK_NOW" },
      });
      assert.equal(replay.statusCode, 200);
      assert.equal(replay.json().message.id, message.id);

      const history = await h.app.inject({
        method: "GET",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: bob.auth(),
      });
      assert.equal(history.statusCode, 200);
      assert.equal(history.json().messages.length, 1, "a replay must not duplicate");

      const read = await h.app.inject({
        method: "POST",
        url: `/api/v1/messages/${message.id}/read`,
        headers: bob.auth(),
      });
      assert.equal(read.statusCode, 204);

      const spoken = await h.app.inject({
        method: "POST",
        url: `/api/v1/messages/${message.id}/spoken`,
        headers: bob.auth(),
      });
      assert.equal(spoken.statusCode, 204);

      const anuChats = await h.app.inject({ method: "GET", url: "/api/v1/conversations", headers: anu.auth() });
      const list = anuChats.json().conversations as Array<{ unreadCount: number; lastMessage: { text: string } }>;
      assert.equal(list.length, 1);
      assert.equal(list[0]!.lastMessage.text, "Are you alone?");

      const bobChats = await h.app.inject({ method: "GET", url: "/api/v1/conversations", headers: bob.auth() });
      assert.equal((bobChats.json().conversations as Array<{ unreadCount: number }>)[0]!.unreadCount, 0);
    } finally {
      await h.close();
    }
  });

  test("speech consent is one-directional and owned by the listener", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      const chat = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      const conversationId = chat.json().conversation.id as string;

      // Bob allows Anu to be spoken on his phone.
      const allow = await h.app.inject({
        method: "PATCH",
        url: `/api/v1/conversations/${conversationId}/trust`,
        headers: bob.auth(),
        payload: { trusted: true },
      });
      assert.equal(allow.statusCode, 200, allow.body);
      assert.equal(allow.json().conversation.youAllowSpeak, true);
      assert.equal(allow.json().conversation.peerAllowsSpeak, false, "Anu did not allow Bob");

      // Anu now sees that his messages may be whispered on Bob's phone.
      const anuView = await h.app.inject({
        method: "GET",
        url: "/api/v1/conversations",
        headers: anu.auth(),
      });
      const conversation = (anuView.json().conversations as Array<{ id: string; peerAllowsSpeak: boolean }>)[0]!;
      assert.equal(conversation.id, conversationId);
      assert.equal(conversation.peerAllowsSpeak, true);

      const send = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "speak-1", text: "Pick up milk", priority: "SPEAK_NOW" },
      });
      assert.equal(send.json().message.speakEligible, true);

      // Bob can take the permission back.
      await h.app.inject({
        method: "PATCH",
        url: `/api/v1/conversations/${conversationId}/trust`,
        headers: bob.auth(),
        payload: { trusted: false },
      });
      const after = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "speak-2", text: "Anyone there?", priority: "SPEAK_NOW" },
      });
      assert.equal(after.json().message.speakEligible, false);
    } finally {
      await h.close();
    }
  });

  test("display names can be changed and are visible to the other side", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });

      const rename = await h.app.inject({
        method: "PATCH",
        url: "/api/v1/me",
        headers: bob.auth(),
        payload: { displayName: "Bob (work)" },
      });
      assert.equal(rename.statusCode, 200);
      assert.equal(rename.json().user.displayName, "Bob (work)");

      const anuChats = await h.app.inject({ method: "GET", url: "/api/v1/conversations", headers: anu.auth() });
      const peer = (anuChats.json().conversations as Array<{ peer: { displayName: string } }>)[0]!.peer;
      assert.equal(peer.displayName, "Bob (work)");
    } finally {
      await h.close();
    }
  });

  test("a stranger cannot read or write someone else's conversation", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      const eve = await registerDevice(h.app, "Eve");

      const chat = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      const conversationId = chat.json().conversation.id as string;

      const intruderRead = await h.app.inject({
        method: "GET",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: eve.auth(),
      });
      assert.equal(intruderRead.statusCode, 403);

      const intruderSend = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: eve.auth(),
        payload: { clientMessageId: "eve-1", text: "hello?" },
      });
      assert.equal(intruderSend.statusCode, 403);

      const intruderTrust = await h.app.inject({
        method: "PATCH",
        url: `/api/v1/conversations/${conversationId}/trust`,
        headers: eve.auth(),
        payload: { trusted: true },
      });
      assert.equal(intruderTrust.statusCode, 403);

      const empty = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "empty-1", text: "   " },
      });
      assert.equal(empty.statusCode, 400);
    } finally {
      await h.close();
    }
  });

  test("only the recipient can mark a message read or spoken", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      const chat = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      const conversationId = chat.json().conversation.id as string;
      const send = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "msg-1", text: "hi" },
      });
      const messageId = send.json().message.id as string;

      for (const action of ["read", "spoken"]) {
        const senderTries = await h.app.inject({
          method: "POST",
          url: `/api/v1/messages/${messageId}/${action}`,
          headers: anu.auth(),
        });
        assert.equal(senderTries.statusCode, 403, `${action} must be recipient-only`);
      }
    } finally {
      await h.close();
    }
  });
});

describe("devices, health and codes", () => {
  test("push tokens register and can be removed", async () => {
    const h = await harness();
    try {
      const device = await registerDevice(h.app, "Anu");
      const register = await h.app.inject({
        method: "POST",
        url: "/api/v1/devices",
        headers: device.auth(),
        payload: { deviceId: device.deviceId, pushToken: "fcm-token", appVersion: "1.0.0" },
      });
      assert.equal(register.statusCode, 204);

      const devices = await h.store.devicesForUsers([device.id]);
      assert.equal(devices.length, 1);
      assert.equal(devices[0]!.pushToken, "fcm-token");

      const remove = await h.app.inject({
        method: "DELETE",
        url: `/api/v1/devices/${device.deviceId}`,
        headers: device.auth(),
      });
      assert.equal(remove.statusCode, 204);
      assert.equal((await h.store.devicesForUsers([device.id])).length, 0);
    } finally {
      await h.close();
    }
  });

  test("health endpoint answers without authentication", async () => {
    const h = await harness();
    try {
      const response = await h.app.inject({ method: "GET", url: "/healthz" });
      assert.equal(response.statusCode, 200);
      assert.equal(response.json().status, "ok");
    } finally {
      await h.close();
    }
  });

  test("codes are normalised the way a human would type them", () => {
    assert.equal(normaliseCode("K7M-2PQ"), "k7m2pq");
    assert.equal(normaliseCode(" k7 m2 pq "), "k7m2pq");
    assert.equal(normaliseCode("k7m2pq"), "k7m2pq");
  });
  test("a malformed id is a bad request, never a server error", async () => {
    const h = await harness();
    try {
      const anu = await registerDevice(h.app, "Anu");
      const bob = await registerDevice(h.app, "Bob");
      const open = await h.app.inject({
        method: "POST",
        url: "/api/v1/conversations",
        headers: anu.auth(),
        payload: { code: bob.code },
      });
      const conversationId = open.json().conversation.id as string;
      const sent = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: anu.auth(),
        payload: { clientMessageId: "malformed-1", text: "Are you there?", priority: "NORMAL" },
      });
      assert.equal(sent.statusCode, 201, sent.body);

      // Every `:id` in this API is a uuid column, so a malformed one used to
      // reach PostgreSQL as `invalid input syntax for type uuid` (22P02) and
      // come back as a 500 for what is a client mistake. The near-miss matters:
      // it satisfied a looser pattern and failed only at the database.
      const malformed = ["not-a-uuid", "deadbeef-cafe", "1"];
      const routes: Array<[string, string, Record<string, unknown> | undefined]> = [
        ["GET", "/api/v1/conversations/:id/messages", undefined],
        ["POST", "/api/v1/conversations/:id/messages", { clientMessageId: "malformed-2", text: "hi" }],
        ["PATCH", "/api/v1/conversations/:id/trust", { trusted: true }],
        ["POST", "/api/v1/messages/:id/read", undefined],
        ["POST", "/api/v1/messages/:id/spoken", undefined],
      ];
      for (const id of malformed) {
        for (const [method, template, payload] of routes) {
          const url = template.replace(":id", id);
          const response = await h.app.inject({
            method: method as "GET",
            url,
            headers: anu.auth(),
            ...(payload ? { payload } : {}),
          });
          assert.equal(
            response.statusCode,
            400,
            `${method} ${url} must be rejected as a bad request, not ${response.statusCode}`,
          );
          assert.equal(response.json().error.code, "bad_request");
        }
      }

      // A percent-encoded id is still just a bad id.
      const encoded = await h.app.inject({
        method: "GET",
        url: "/api/v1/conversations/..%2F..%2Fetc%2Fpasswd/messages",
        headers: anu.auth(),
      });
      assert.equal(encoded.statusCode, 400, "a percent-encoded id is a bad id too");

      // A well-formed but unknown id stays a 404: the shape check must not
      // swallow the normal not-found path.
      const unknown = "00000000-0000-4000-8000-000000000000";
      const notFound = await h.app.inject({
        method: "GET",
        url: `/api/v1/conversations/${unknown}/messages`,
        headers: anu.auth(),
      });
      assert.equal(notFound.statusCode, 404);
      assert.equal(notFound.json().error.code, "not_found");

      // And the device id is *not* validated as a uuid: that column is text and
      // the id is chosen by the client.
      const forget = await h.app.inject({
        method: "DELETE",
        url: `/api/v1/devices/${anu.deviceId}`,
        headers: anu.auth(),
      });
      assert.equal(forget.statusCode, 204, "a client-chosen device id is not a uuid");
    } finally {
      await h.close();
    }
  });
});
