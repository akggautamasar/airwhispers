import { test, describe } from "node:test";
import assert from "node:assert/strict";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { MemoryStore } from "../src/store.memory.js";
import { RealtimeHub } from "../src/realtime.js";
import { FcmPusher } from "../src/push.js";
import { loadConfig } from "../src/config.js";
import { hashPassword, verifyAccessToken, verifyPassword } from "../src/crypto.js";
import type { Store } from "../src/types.js";

/**
 * End-to-end API tests over the in-memory store — the same code paths production
 * uses, minus Postgres.
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

async function registerUser(app: FastifyInstance, email: string, displayName: string) {
  const response = await app.inject({
    method: "POST",
    url: "/api/v1/auth/register",
    payload: { email, password: "correct-horse-battery", displayName, deviceId: `device-${email}` },
  });
  assert.equal(response.statusCode, 201, response.body);
  const body = response.json() as { user: { id: string }; tokens: { accessToken: string; refreshToken: string } };
  return {
    id: body.user.id,
    accessToken: body.tokens.accessToken,
    refreshToken: body.tokens.refreshToken,
    auth: (extra: Record<string, string> = {}) => ({ authorization: `Bearer ${body.tokens.accessToken}`, ...extra }),
  };
}

describe("auth", () => {
  test("register, sign in and read the profile", async () => {
    const h = await harness();
    try {
      const user = await registerUser(h.app, "a@example.com", "Alpha");
      const me = await h.app.inject({ method: "GET", url: "/api/v1/users/me", headers: user.auth() });
      assert.equal(me.statusCode, 200);
      assert.equal(me.json().user.email, "a@example.com");

      const login = await h.app.inject({
        method: "POST",
        url: "/api/v1/auth/login",
        payload: { email: "a@example.com", password: "correct-horse-battery" },
      });
      assert.equal(login.statusCode, 200);

      const wrong = await h.app.inject({
        method: "POST",
        url: "/api/v1/auth/login",
        payload: { email: "a@example.com", password: "not-the-password" },
      });
      assert.equal(wrong.statusCode, 401);
    } finally {
      await h.close();
    }
  });

  test("duplicate email is rejected", async () => {
    const h = await harness();
    try {
      await registerUser(h.app, "dup@example.com", "First");
      const second = await h.app.inject({
        method: "POST",
        url: "/api/v1/auth/register",
        payload: { email: "dup@example.com", password: "correct-horse-battery", displayName: "Second" },
      });
      assert.equal(second.statusCode, 409);
    } finally {
      await h.close();
    }
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

  test("refresh tokens rotate and cannot be reused", async () => {
    const h = await harness();
    try {
      const user = await registerUser(h.app, "rotate@example.com", "Rotate");
      const first = await h.app.inject({
        method: "POST",
        url: "/api/v1/auth/refresh",
        payload: { refreshToken: user.refreshToken },
      });
      assert.equal(first.statusCode, 200);
      const rotated = first.json() as { refreshToken: string };

      const replay = await h.app.inject({
        method: "POST",
        url: "/api/v1/auth/refresh",
        payload: { refreshToken: user.refreshToken },
      });
      assert.equal(replay.statusCode, 401, "a used refresh token must be dead");
      assert.notEqual(rotated.refreshToken, user.refreshToken);
    } finally {
      await h.close();
    }
  });

  test("passwords are stored hashed, not recoverable", async () => {
    const hash = await hashPassword("correct-horse-battery");
    assert.ok(!hash.includes("correct-horse-battery"));
    assert.equal(await verifyPassword("correct-horse-battery", hash), true);
    assert.equal(await verifyPassword("wrong", hash), false);
  });

  test("login attempts are rate limited", async () => {
    const h = await harness();
    try {
      await registerUser(h.app, "limit@example.com", "Limit");
      let limited = false;
      for (let attempt = 0; attempt < 30; attempt++) {
        const response = await h.app.inject({
          method: "POST",
          url: "/api/v1/auth/login",
          payload: { email: "limit@example.com", password: "wrong-password" },
        });
        if (response.statusCode === 429) {
          limited = true;
          assert.ok(response.json().error.retryAfterMs > 0);
          break;
        }
      }
      assert.ok(limited, "expected a 429 after repeated attempts");
    } finally {
      await h.close();
    }
  });
});

describe("messaging", () => {
  test("two users exchange a message, with delivery and read state", async () => {
    const h = await harness();
    try {
      const alice = await registerUser(h.app, "alice@example.com", "Alice");
      const bob = await registerUser(h.app, "bob@example.com", "Bob");

      const addContact = await h.app.inject({
        method: "POST",
        url: "/api/v1/contacts",
        headers: alice.auth(),
        payload: { email: "bob@example.com" },
      });
      assert.equal(addContact.statusCode, 201, addContact.body);
      const contact = addContact.json().contact as { id: string; conversationId: string; isTrusted: boolean };
      assert.equal(contact.id, bob.id);
      assert.equal(contact.isTrusted, false, "new contacts must not be trusted by default");

      // Bob's client trusts Alice, so her messages may be spoken to him.
      const trust = await h.app.inject({
        method: "PATCH",
        url: `/api/v1/contacts/${alice.id}`,
        headers: bob.auth(),
        payload: { isTrusted: true },
      });
      assert.equal(trust.statusCode, 200);
      assert.equal(trust.json().contact.isTrusted, true);

      const conversationId = contact.conversationId;
      const clientMessageId = "client-1";
      const send = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: alice.auth(),
        payload: { clientMessageId, text: "Are you alone?", priority: "SPEAK_NOW" },
      });
      assert.equal(send.statusCode, 201, send.body);
      const message = send.json().message as { id: string; priority: string; speakEligible: boolean };
      assert.equal(message.priority, "SPEAK_NOW");
      assert.equal(message.speakEligible, true);

      // Idempotent replay: same clientMessageId must not create a second message.
      const replay = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: alice.auth(),
        payload: { clientMessageId, text: "Are you alone?", priority: "SPEAK_NOW" },
      });
      assert.equal(replay.statusCode, 200);
      assert.equal(replay.json().message.id, message.id);

      const history = await h.app.inject({
        method: "GET",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: bob.auth(),
      });
      assert.equal(history.statusCode, 200);
      assert.equal(history.json().messages.length, 1, "duplicate must not appear twice");

      const markRead = await h.app.inject({
        method: "POST",
        url: `/api/v1/messages/${message.id}/read`,
        headers: bob.auth(),
      });
      assert.equal(markRead.statusCode, 204);

      const markSpoken = await h.app.inject({
        method: "POST",
        url: `/api/v1/messages/${message.id}/spoken`,
        headers: bob.auth(),
      });
      assert.equal(markSpoken.statusCode, 204);

      const aliceConversations = await h.app.inject({
        method: "GET",
        url: "/api/v1/conversations",
        headers: alice.auth(),
      });
      const list = aliceConversations.json().conversations as Array<{ unreadCount: number; lastMessage: { text: string } }>;
      assert.equal(list.length, 1);
      assert.equal(list[0]!.lastMessage.text, "Are you alone?");

      const bobConversations = await h.app.inject({
        method: "GET",
        url: "/api/v1/conversations",
        headers: bob.auth(),
      });
      const bobList = bobConversations.json().conversations as Array<{ unreadCount: number }>;
      assert.equal(bobList[0]!.unreadCount, 0, "read messages are no longer unread");
    } finally {
      await h.close();
    }
  });

  test("a third user cannot read someone else's conversation", async () => {
    const h = await harness();
    try {
      const alice = await registerUser(h.app, "a2@example.com", "Alice");
      const bob = await registerUser(h.app, "b2@example.com", "Bob");
      const eve = await registerUser(h.app, "eve@example.com", "Eve");

      const contact = await h.app.inject({
        method: "POST",
        url: "/api/v1/contacts",
        headers: alice.auth(),
        payload: { email: "b2@example.com" },
      });
      const conversationId = contact.json().contact.conversationId as string;

      const intruder = await h.app.inject({
        method: "GET",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: eve.auth(),
      });
      assert.equal(intruder.statusCode, 403);

      const intruderSend = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: eve.auth(),
        payload: { clientMessageId: "eve-1", text: "hello?" },
      });
      assert.equal(intruderSend.statusCode, 403);

      // Messages are validated.
      const empty = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: alice.auth(),
        payload: { clientMessageId: "empty-1", text: "   " },
      });
      assert.equal(empty.statusCode, 400);

      assert.ok(bob.id);
    } finally {
      await h.close();
    }
  });

  test("only the recipient can mark a message read", async () => {
    const h = await harness();
    try {
      const alice = await registerUser(h.app, "a3@example.com", "Alice");
      await registerUser(h.app, "b3@example.com", "Bob");
      const contact = await h.app.inject({
        method: "POST",
        url: "/api/v1/contacts",
        headers: alice.auth(),
        payload: { email: "b3@example.com" },
      });
      const conversationId = contact.json().contact.conversationId as string;
      const send = await h.app.inject({
        method: "POST",
        url: `/api/v1/conversations/${conversationId}/messages`,
        headers: alice.auth(),
        payload: { clientMessageId: "msg-1", text: "hi" },
      });
      const messageId = send.json().message.id as string;

      const senderTries = await h.app.inject({
        method: "POST",
        url: `/api/v1/messages/${messageId}/read`,
        headers: alice.auth(),
      });
      assert.equal(senderTries.statusCode, 403);
    } finally {
      await h.close();
    }
  });
});

describe("settings and devices", () => {
  test("call assist settings round-trip with privacy-safe defaults", async () => {
    const h = await harness();
    try {
      const user = await registerUser(h.app, "settings@example.com", "Settings");
      const initial = await h.app.inject({ method: "GET", url: "/api/v1/settings", headers: user.auth() });
      assert.equal(initial.statusCode, 200);
      const defaults = initial.json().settings;
      assert.equal(defaults.speakMessages, false, "speaking must be opt-in");
      assert.equal(defaults.onlyDuringCalls, true);
      assert.equal(defaults.trustedContactsOnly, true);

      const updated = await h.app.inject({
        method: "PATCH",
        url: "/api/v1/settings",
        headers: user.auth(),
        payload: { speakMessages: true, languageTag: "hi-IN", speechRate: 1.4, emojiMode: "IGNORE" },
      });
      assert.equal(updated.statusCode, 200);
      assert.equal(updated.json().settings.speakMessages, true);
      assert.equal(updated.json().settings.languageTag, "hi-IN");
      assert.equal(updated.json().settings.emojiMode, "IGNORE");

      const bad = await h.app.inject({
        method: "PATCH",
        url: "/api/v1/settings",
        headers: user.auth(),
        payload: { emojiMode: "SHOUT_EVERYTHING" },
      });
      assert.equal(bad.statusCode, 400);
    } finally {
      await h.close();
    }
  });

  test("devices register push tokens and can be removed", async () => {
    const h = await harness();
    try {
      const user = await registerUser(h.app, "device@example.com", "Device");
      const register = await h.app.inject({
        method: "POST",
        url: "/api/v1/devices",
        headers: user.auth(),
        payload: { deviceId: "11111111-2222-3333-4444-555555555555", pushToken: "fcm-token", appVersion: "1.0.0" },
      });
      assert.equal(register.statusCode, 204);

      const devices = await h.store.devicesForUsers([user.id]);
      assert.equal(devices.length, 1);
      assert.equal(devices[0]!.pushToken, "fcm-token");

      const remove = await h.app.inject({
        method: "DELETE",
        url: "/api/v1/devices/11111111-2222-3333-4444-555555555555",
        headers: user.auth(),
      });
      assert.equal(remove.statusCode, 204);
      assert.equal((await h.store.devicesForUsers([user.id])).length, 0);
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
});
