import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { Pool as PgPool } from "pg";
import type { Pool } from "pg";
import { PgStore } from "../src/store.pg.js";
import type { Store } from "../src/types.js";

/**
 * The store contract: what every `Store` implementation promises.
 *
 * The in-memory store is covered by the API tests. This file exists because the
 * PostgreSQL store — the one production actually runs — was written against
 * `sql/schema.sql` and, until this test existed, the two had never been executed
 * together.
 *
 * It needs a **real** PostgreSQL (`TEST_DATABASE_URL=postgres://…`); CI runs it
 * against a service container.
 *
 * An in-process emulator (pg-mem) was tried first and rejected. It parses PostgreSQL
 * happily and then returns wrong results: `ON CONFLICT … DO NOTHING RETURNING *` hands
 * back the existing row (which PostgreSQL does not), and `= ANY($1::uuid[])` returned
 * one row in isolation and zero rows later in the same session. A test whose passes
 * cannot be trusted is worse than no test. The bug class it was meant to catch —
 * column and table names drifting away from the schema — is covered statically by
 * `scripts/check-schema-refs.ts`, which runs on every `npm test`.
 */

export interface StoreUnderTest {
  store: Store;
  label: string;
  close: () => Promise<void>;
  /**
   * Runs one statement straight against the pool, bypassing the store guards.
   * The contract uses it to prove that PostgreSQL really does reject a
   * malformed id (22P02) — otherwise the guards would be superstition.
   */
  rawQuery: (sql: string, params?: unknown[]) => Promise<unknown>;
}

export function hasTestDatabase(): boolean {
  return Boolean(process.env.TEST_DATABASE_URL);
}

/**
 * Applies the production schema; pgcrypto is unnecessary for these tables.
 *
 * The file is located by walking up from this module, because `tsc` does not copy
 * `sql/` into `dist/` — the test may run from the source tree or from the build output.
 */
async function schemaSql(): Promise<string> {
  let dir = dirname(fileURLToPath(new URL(".", import.meta.url)));
  for (let depth = 0; depth < 5; depth += 1) {
    const candidate = join(dir, "sql", "schema.sql");
    try {
      const raw = await readFile(candidate, "utf8");
      return raw.replace(/CREATE EXTENSION[^;]*;/gi, "");
    } catch {
      dir = dirname(dir);
    }
  }
  throw new Error("sql/schema.sql not found near the test directory");
}

export async function makeStore(): Promise<StoreUnderTest> {
  const url = process.env.TEST_DATABASE_URL;
  if (!url) throw new Error("TEST_DATABASE_URL is required for the store contract");

  const pool: Pool = new PgPool({ connectionString: url, max: 4 });
  // Create the schema only if it is missing, then start clean so repeated runs agree.
  const client = await pool.connect();
  try {
    await client.query(await schemaSql());
  } finally {
    client.release();
  }
  await pool.query(
    `TRUNCATE messages, conversation_members, conversations, contacts, devices,
              user_settings, refresh_tokens, users CASCADE`,
  );
  return {
    store: new PgStore(pool),
    label: "postgres",
    close: () => pool.end(),
    rawQuery: async (sql, params = []) => pool.query(sql, params),
  };
}

/** Every assertion the store interface promises. */
export async function runStoreContract(t: StoreUnderTest): Promise<void> {
  const { store } = t;
  const now = Date.now();

  // ---------------------------------------------------------------- accounts
  const asha = await store.createUser({
    email: "Asha@Example.com",
    displayName: "Asha",
    passwordHash: "scrypt$1$salt$key",
  });
  assert.ok(asha.id, "user gets an id");
  assert.equal(asha.email, "asha@example.com", "emails are stored lowercased");
  assert.equal(asha.displayName, "Asha");

  const found = await store.findUserByEmail("asha@example.com");
  assert.equal(found?.id, asha.id, "lookup by email");
  assert.equal(found?.passwordHash, "scrypt$1$salt$key", "password hash round-trips");
  assert.equal(await store.findUserByEmail("nobody@example.com"), null);
  assert.equal((await store.findUserById(asha.id))?.email, "asha@example.com");
  assert.equal(await store.findUserById("00000000-0000-4000-8000-000000000000"), null);

  await assert.rejects(
    () => store.createUser({ email: "asha@example.com", displayName: "Clone", passwordHash: "x" }),
    "duplicate email is rejected by the unique index",
  );

  const renamed = await store.updateUser(asha.id, { displayName: "Asha R" });
  assert.equal(renamed.displayName, "Asha R");

  // ---------------------------------------------------------------- sessions
  await store.saveRefreshToken({
    tokenHash: "hash-1",
    userId: asha.id,
    deviceId: "device-1",
    expiresAt: now + 60_000,
    revokedAt: null,
  });
  const token = await store.findRefreshToken("hash-1");
  assert.equal(token?.userId, asha.id);
  assert.equal(token?.deviceId, "device-1");
  assert.equal(token?.revokedAt, null);

  await store.revokeRefreshToken("hash-1", now);
  assert.equal((await store.findRefreshToken("hash-1"))?.revokedAt, now, "revocation persists");

  await store.saveRefreshToken({
    tokenHash: "hash-2",
    userId: asha.id,
    deviceId: "device-1",
    expiresAt: now + 60_000,
    revokedAt: null,
  });
  await store.revokeAllRefreshTokens(asha.id);
  assert.notEqual((await store.findRefreshToken("hash-2"))?.revokedAt, null);

  // ---------------------------------------------------------------- messaging
  const bharat = await store.createUser({
    email: "bharat@example.com",
    displayName: "Bharat",
    passwordHash: "scrypt$1$salt$key2",
  });

  const conversation = await store.getOrCreateConversation(asha.id, bharat.id);
  assert.equal(conversation.memberIds.length, 2, "both members are stored");
  const again = await store.getOrCreateConversation(bharat.id, asha.id);
  assert.equal(again.id, conversation.id, "the pair key makes conversation creation idempotent");
  assert.equal((await store.listConversations(asha.id)).length, 1, "the creator sees it");
  assert.equal((await store.listConversations(bharat.id)).length, 1, "so does the other member");
  assert.equal((await store.findConversation(conversation.id))?.id, conversation.id);
  assert.equal(await store.findConversation("00000000-0000-4000-8000-000000000000"), null);

  const message = {
    id: "11111111-1111-4111-8111-111111111111",
    clientMessageId: "cm-1",
    conversationId: conversation.id,
    senderId: bharat.id,
    recipientId: asha.id,
    text: "Are you alone?",
    priority: "NORMAL" as const,
    createdAt: now,
    deliveredAt: null,
    readAt: null,
    spokenAt: null,
  };
  const inserted = await store.insertMessage(message);
  assert.equal(inserted.created, true);
  assert.equal(inserted.message.text, "Are you alone?");

  // The idempotency guarantee the whole delivery path depends on.
  const duplicate = await store.insertMessage({
    ...message,
    id: "22222222-2222-4222-8222-222222222222",
  });
  assert.equal(duplicate.created, false, "duplicate (sender, clientMessageId) does not create a row");
  assert.equal(duplicate.message.id, message.id, "the original message is returned");
  assert.equal((await store.listMessages(conversation.id, 50)).length, 1);

  assert.equal(await store.unreadCount(conversation.id, asha.id), 1);
  assert.equal(await store.unreadCount(conversation.id, bharat.id), 0, "one's own message is never unread");

  const last = await store.lastMessages([conversation.id]);
  assert.equal(last.get(conversation.id)?.id, message.id, "DISTINCT ON returns the newest message");
  assert.equal(await store.lastMessages([]).then((m) => m.size), 0, "no conversations means no query");

  await store.markMessageDelivered(message.id, now + 10);
  assert.equal((await store.findMessage(message.id))?.deliveredAt, now + 10);

  await store.markMessageSpoken(message.id, now + 20);
  const spoken = await store.findMessage(message.id);
  assert.equal(spoken?.spokenAt, now + 20);
  assert.equal(spoken?.deliveredAt, now + 10, "delivered_at is not overwritten");

  const read = await store.markConversationRead(conversation.id, asha.id, now + 30);
  assert.equal(read.length, 1, "only the recipient's unread messages are marked");
  assert.equal(read[0]!.readAt, now + 30);
  assert.equal(await store.unreadCount(conversation.id, asha.id), 0);
  assert.equal(
    (await store.markConversationRead(conversation.id, asha.id, now + 40)).length,
    0,
    "marking read twice changes nothing",
  );
  assert.equal(await store.findMessage("00000000-0000-4000-8000-000000000000"), null);

  // ---------------------------------------------------------------- contacts
  const contact = await store.addContact(asha.id, bharat.id, false);
  assert.equal(contact.isTrusted, false, "new contacts start untrusted");
  assert.equal((await store.listContacts(asha.id)).length, 1);
  assert.equal((await store.findContact(asha.id, bharat.id))?.isTrusted, false);

  const trusted = await store.addContact(asha.id, bharat.id, true);
  assert.equal(trusted.isTrusted, true, "re-adding upserts the trust flag");
  assert.equal((await store.listContacts(asha.id)).length, 1, "no duplicate contact row");

  assert.equal((await store.updateContact(asha.id, bharat.id, { isTrusted: false }))?.isTrusted, false);
  assert.equal((await store.updateContact(asha.id, bharat.id, {}))?.isTrusted, false, "COALESCE keeps the value");
  assert.equal(await store.updateContact(asha.id, "00000000-0000-4000-8000-000000000000", {}), null);

  await store.deleteContact(asha.id, bharat.id);
  assert.equal(await store.findContact(asha.id, bharat.id), null);

  // ---------------------------------------------------------------- settings
  const defaults = await store.getSettings(asha.id);
  assert.equal(defaults.userId, asha.id);
  assert.equal(defaults.speakMessages, false, "privacy-first default: nothing is spoken");
  assert.equal(defaults.onlyDuringCalls, true);
  assert.equal(defaults.trustedContactsOnly, true);
  assert.equal(defaults.languageTag, "en-IN");
  assert.equal(defaults.emojiMode, "DESCRIBE_IMPORTANT");

  const updated = await store.updateSettings(asha.id, { speakMessages: true, speechRate: 1.25 });
  assert.equal(updated.speakMessages, true);
  assert.equal(updated.speechRate, 1.25);
  assert.equal(updated.languageTag, "en-IN", "unspecified fields keep their value");

  const reread = await store.getSettings(asha.id);
  assert.equal(reread.speakMessages, true, "settings persist");
  assert.equal(reread.speechRate, 1.25);

  // ---------------------------------------------------------------- devices
  await store.upsertDevice({
    deviceId: "device-1",
    userId: asha.id,
    platform: "android",
    pushToken: "token-1",
    appVersion: "1.1.1",
    updatedAt: now,
  });
  await store.upsertDevice({
    deviceId: "device-1",
    userId: asha.id,
    platform: "android",
    pushToken: "token-2",
    appVersion: "1.1.2",
    updatedAt: now + 1,
  });
  const devices = await store.devicesForUsers([asha.id]);
  assert.equal(devices.length, 1, "the device id is the conflict key");
  assert.equal(devices[0]!.pushToken, "token-2");
  assert.equal(devices[0]!.appVersion, "1.1.2");

  await store.upsertDevice({
    deviceId: "device-2",
    userId: asha.id,
    platform: "android",
    pushToken: null,
    appVersion: "1.1.2",
    updatedAt: now,
  });
  assert.equal(
    (await store.devicesForUsers([asha.id])).length,
    1,
    "a device without a push token is not a push target",
  );
  assert.deepEqual(await store.devicesForUsers([]), [], "empty input short-circuits");
  assert.equal(
    (await store.devicesForUsers([asha.id, bharat.id])).length,
    1,
    "ANY(...) lookup returns the registered device",
  );

  await store.deleteDevice(asha.id, "device-1");
  assert.equal((await store.devicesForUsers([asha.id])).length, 0);

  // ------------------------------------------------- malformed identifiers
  // Ids are `uuid` columns, and PostgreSQL rejects a non-UUID string with
  // 22P02 — a 500 at the API unless the boundary or these guards catch it
  // first. The store's contract is that a malformed id is simply "no such
  // row": this runs only against real PostgreSQL, where the cast actually
  // happens and an in-memory store could not tell us the truth.
  const garbage = "not-a-uuid";
  assert.equal(await store.findUserById(garbage), null, "malformed user id");
  assert.equal(await store.findConversation(garbage), null, "malformed conversation id");
  assert.equal(await store.findMessage(garbage), null, "malformed message id");
  assert.deepEqual(await store.listMessages(garbage, 50), [], "malformed conversation id in listMessages");
  assert.equal(await store.findContact(garbage, asha.id), null, "malformed owner id");
  assert.equal(await store.findContact(asha.id, garbage), null, "malformed contact id");

  // ...and that is not superstition: the database really does refuse a
  // malformed uuid where the column is `uuid`-typed, which is exactly the 500
  // the guards above keep from ever happening.
  await assert.rejects(
    () => t.rawQuery(`SELECT * FROM users WHERE id = $1`, [garbage]),
    (error: NodeJS.ErrnoException) => {
      assert.equal(error.code, "22P02", `expected a uuid syntax error, got ${String(error.code)}`);
      return true;
    },
    "raw query with a malformed uuid must be rejected by PostgreSQL",
  );

  // Idempotent mutations are a no-op for an unknown id, not an error.
  await store.markMessageDelivered(garbage, now);
  await store.markMessageSpoken(garbage, now);
  assert.deepEqual(await store.markConversationRead(garbage, asha.id, now), []);
  await store.deleteContact(asha.id, garbage);

  // The conversation still exists and is untouched by any of the above.
  const stillThere = await store.findConversation(conversation.id);
  assert.ok(stillThere, "valid ids keep working after malformed ones");
}
