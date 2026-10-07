import { test, describe, skip } from "node:test";
import { hasTestDatabase, makeStore, runStoreContract } from "./store-contract.js";

/**
 * The PostgreSQL store contract — the only test that exercises `store.pg.ts` and
 * `sql/schema.sql` together, which is what production actually runs.
 *
 * It needs a real server: `TEST_DATABASE_URL=postgres://…`. CI supplies one as a
 * service container and is the authority on this path. Without the variable the
 * suite skips loudly, because an unverified production store is exactly the kind of
 * thing that only fails in production.
 *
 * (An emulator was tried and rejected — see the note in `store-contract.ts`.)
 */
describe("Store contract (PostgreSQL)", () => {
  if (!hasTestDatabase()) {
    skip("TEST_DATABASE_URL is not set — the PostgreSQL store was not verified");
    return;
  }

  test("all documented store behaviours hold", async () => {
    const harness = await makeStore();
    try {
      await runStoreContract(harness);
    } finally {
      await harness.close();
    }
  });
});
