import { readFileSync } from "node:fs";
import { Pool } from "pg";

/**
 * Applies sql/schema.sql to DATABASE_URL. Idempotent (CREATE ... IF NOT EXISTS),
 * so it is safe to run on every deploy:
 *
 *   DATABASE_URL=postgres://... npm run schema
 */
const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required");
  process.exit(1);
}

const sql = readFileSync(new URL("../sql/schema.sql", import.meta.url), "utf8");
const pool = new Pool({ connectionString: url });

try {
  await pool.query(sql);
  console.log("AirWhispers schema applied");
} catch (error) {
  console.error("Failed to apply schema:", error);
  process.exitCode = 1;
} finally {
  await pool.end();
}
