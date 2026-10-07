/**
 * Identifier shape, shared by the HTTP boundary and the store.
 *
 * Every id this backend hands out is a `randomUUID()`, and the PostgreSQL
 * columns that hold them are `uuid`-typed. Fed a non-UUID string, PostgreSQL
 * raises `invalid input syntax for type uuid` (22P02), which reaches the client
 * as a 500 — an avoidable failure that looks like an outage instead of a bad
 * request. Both layers therefore agree on one predicate:
 *
 *   * `src/app.ts` rejects a malformed path parameter with `400 bad_request`;
 *   * `src/store.pg.ts` treats a malformed id as "no such row" rather than
 *     letting it reach the database.
 *
 * The Android client never constructs ids itself (it echoes what the API
 * returned), so this is about malformed or hostile input only.
 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
