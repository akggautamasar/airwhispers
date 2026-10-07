/**
 * The PostgreSQL store, verified against real PostgreSQL.
 *
 * The API tests run over the in-memory store, so `store.pg.ts` — the code
 * production actually runs — is only ever type-checked. That is how a real bug
 * hid in this project once already: the in-memory store accepted a malformed id
 * silently while PostgreSQL answered `invalid input syntax for type uuid`
 * (22P02), which the API turned into a 500 for what was a client mistake.
 *
 * This test therefore needs a database and skips loudly without one:
 *
 *   docker compose up -d db
 *   DATABASE_URL=$TEST_DATABASE_URL npm run schema
 *   TEST_DATABASE_URL=postgres://… npm test
 *
 * It covers the part of the store the guards touch (a malformed id is "no such
 * row", never a database error) and proves that a *raw* query really is
 * rejected, so the guards cannot be mistaken for superstition.
 */

import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Pool } from "pg";
import { PgStore } from "../src/store.pg.js";

const url = process.env.TEST_DATABASE_URL;

/**
 * Locates `sql/schema.sql` by walking up from this module: `tsc` does not copy
 * `sql/` into `dist/`, so a path relative to the compiled file would be wrong
 * (which is exactly how this test first failed in CI).
 */
function schemaSql(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let depth = 0; depth < 5; depth += 1) {
    const candidate = join(dir, "sql", "schema.sql");
    try {
      return readFileSync(candidate, "utf8").replace(/CREATE EXTENSION[^;]*;/gi, "");
    } catch {
      dir = resolve(dir, "..");
    }
  }
  throw new Error("sql/schema.sql not found near the test directory");
}

describe("PostgreSQL store", () => {
  test(
    "a malformed id is 'no such row', and PostgreSQL proves why",
    { skip: url ? false : "TEST_DATABASE_URL is not set — the PostgreSQL store was not verified" },
    async () => {
      const pool = new Pool({ connectionString: url, max: 4 });
      try {
        await pool.query(schemaSql());
        await pool.query(
          `TRUNCATE messages, conversation_members, conversations, trusts,
                    device_credentials, devices, users CASCADE`,
        );
        const store = new PgStore(pool);
        const garbage = "not-a-uuid";

        // A malformed id must never reach a `uuid` column: these return
        // "nothing" instead of throwing.
        assert.equal(await store.findUserById(garbage), null);
        assert.equal(await store.findConversation(garbage), null);
        assert.equal(await store.findMessage(garbage), null);
        assert.deepEqual(await store.listMessages(garbage, 50), []);
        await store.markMessageDelivered(garbage, Date.now());
        await store.markMessageSpoken(garbage, Date.now());
        assert.deepEqual(await store.markConversationRead(garbage, garbage, Date.now()), []);

        // ... and the reason the guards exist, checked directly: bypassing them
        // makes PostgreSQL reject the same string, which is what used to become
        // a 500. If this ever stops being true, the guards are dead weight.
        await assert.rejects(
          () => pool.query(`SELECT * FROM users WHERE id = $1`, [garbage]),
          (error: NodeJS.ErrnoException) => {
            assert.equal(error.code, "22P02", `expected a uuid syntax error, got ${String(error.code)}`);
            return true;
          },
        );

        // A well-formed id still works, so the guard is not simply refusing
        // everything.
        const anu = await store.createUser({ code: "k7m2pq", displayName: "Anu" });
        assert.equal((await store.findUserById(anu.id))?.code, "k7m2pq");
      } finally {
        await pool.end();
      }
    },
  );
});
