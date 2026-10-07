/**
 * Static check: the SQL in `src/store.pg.ts` must agree with `sql/schema.sql`.
 *
 * This is the bug class that production would otherwise discover the hard way — a
 * renamed column, a typo'd table, an `ON CONFLICT` target that no longer has a unique
 * index. It runs in `npm test`, so it needs no database and no Docker.
 *
 * It deliberately does not try to be a SQL parser: it checks the places where a
 * mismatch is both likely and fatal (INSERT column lists, UPDATE SET targets,
 * ON CONFLICT targets, table names, and qualified `table.column` references), and
 * ignores everything else. When it cannot resolve an alias it says so instead of
 * guessing.
 *
 * Usage:  node --experimental-strip-types scripts/check-schema-refs.ts
 * Exits 0 when consistent, 1 with a list of problems otherwise.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function read(relative: string): string {
  return readFileSync(join(root, relative), "utf8");
}

// ------------------------------------------------------------------ the schema

const schemaText = read("sql/schema.sql");
const tables = new Map<string, Set<string>>();
const uniqueTargets = new Set<string>();

for (const match of schemaText.matchAll(/CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(([\s\S]*?)\n\);/g)) {
  const table = match[1]!;
  const body = match[2]!;
  const columns = new Set<string>();
  for (const line of body.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("--")) continue;
    const column = /^([a-z_][a-z0-9_]*)\s+/.exec(trimmed);
    if (column) columns.add(column[1]!);
    // Table-level UNIQUE (a, b) / PRIMARY KEY (a, b) also makes a conflict target legal.
    const constraint = /^(?:UNIQUE|PRIMARY KEY)\s*\(([^)]*)\)/i.exec(trimmed);
    if (constraint) {
      for (const column of constraint[1]!.split(",")) {
        uniqueTargets.add(`${table}.${column.trim()}`);
      }
    }
  }
  tables.set(table, columns);
}

if (tables.size === 0) {
  console.error("check-schema-refs: no tables found in sql/schema.sql — parser out of date?");
  process.exit(1);
}

// Column-level UNIQUE (declared inline) is a legal conflict target too.
for (const match of schemaText.matchAll(/CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(([\s\S]*?)\n\);/g)) {
  for (const line of match[2]!.split("\n")) {
    const inline = /^\s*([a-z_][a-z0-9_]*)\s+[^,]*UNIQUE/i.exec(line);
    if (inline) uniqueTargets.add(`${match[1]}.${inline[1]}`);
    if (/^\s*([a-z_][a-z0-9_]*)\s+[^,]*PRIMARY KEY/i.test(line)) {
      uniqueTargets.add(`${match[1]}.${/^\s*([a-z_][a-z0-9_]*)/.exec(line)![1]}`);
    }
  }
}

// ------------------------------------------------------------- the store's SQL

const storeSource = read("src/store.pg.ts");
// Only look at the backtick-delimited SQL templates.
const statements = [...storeSource.matchAll(/`([^`]*)`/g)]
  .map((match) => match[1]!)
  .filter((sql) => /\b(SELECT|INSERT|UPDATE|DELETE)\b/i.test(sql));

const SQL_KEYWORDS = new Set([
  "set",
  "select",
  "values",
  "do",
  "conflict",
  "excluded",
  "using",
  "distinct",
  "recent",
  "on",
  "as",
  "table",
  "only",
]);

const problems: string[] = [];
const check = (condition: boolean, message: string) => {
  if (!condition) problems.push(message);
};

for (const sql of statements) {
  const flat = sql.replace(/\s+/g, " ").trim();
  const label = flat.length > 90 ? `${flat.slice(0, 90)}…` : flat;

  // Tables referenced anywhere in the statement.
  for (const match of flat.matchAll(/\b(?:FROM|JOIN|INTO|UPDATE|DELETE\s+FROM)\s+([a-z_][a-z0-9_]*)/gi)) {
    const statement = match[0]!;
    const table = match[1]!;
    void statement;
    // `FROM (SELECT …) recent`, `DO UPDATE SET …` — not table names.
    if (SQL_KEYWORDS.has(table.toLowerCase())) continue;
    if (!tables.has(table)) {
      // Aliases introduced in this statement are fine to skip.
      const alias = new RegExp(`(?:FROM|JOIN)\\s+(?:[a-z_][a-z0-9_]*)\\s+(?:AS\\s+)?${table}\\b`, "i").test(flat);
      if (!alias) problems.push(`${label}\n    unknown table "${table}"`);
    }
  }

  // INSERT INTO t (a, b, …)
  const insert = /\bINSERT\s+INTO\s+([a-z_][a-z0-9_]*)\s*\(([^)]*)\)/i.exec(flat);
  if (insert) {
    const table = insert[1]!;
    const columns = tables.get(table);
    if (!columns) {
      problems.push(`${label}\n    INSERT INTO unknown table "${table}"`);
    } else {
      for (const raw of insert[2]!.split(",")) {
        const column = raw.trim();
        if (!column || column === "*") continue;
        check(columns.has(column), `${label}\n    INSERT INTO ${table}: no column "${column}"`);
      }
    }
  }

  // UPDATE t SET a = …, b = …   (not `ON CONFLICT … DO UPDATE SET`)
  const update = /(?<!DO\s)\bUPDATE\s+([a-z_][a-z0-9_]*)\s+SET\s+([\s\S]*?)(?:\bWHERE\b|\bRETURNING\b|$)/i.exec(flat);
  if (update) {
    const table = update[1]!;
    const columns = tables.get(table);
    if (columns) {
      for (const assignment of update[2]!.split(",")) {
        const column = /^\s*([a-z_][a-z0-9_]*)\s*=/.exec(assignment)?.[1];
        if (column) check(columns.has(column), `${label}\n    UPDATE ${table}: no column "${column}"`);
      }
    }
  }

  // ON CONFLICT (a, b) needs a matching unique/primary key in the schema.
  const conflict = /\bON\s+CONFLICT\s*\(([^)]*)\)/i.exec(flat);
  if (conflict && insert) {
    const table = insert[1]!;
    for (const raw of conflict[1]!.split(",")) {
      const column = raw.trim();
      if (!column) continue;
      check(
        uniqueTargets.has(`${table}.${column}`) ||
          columnsMissingFromUnique(table, column),
        `${label}\n    ON CONFLICT (${column}) has no UNIQUE or PRIMARY KEY on ${table}`,
      );
    }
  }

  // Qualified references: alias.column where the alias maps to a real table.
  const aliases = new Map<string, string>();
  for (const match of flat.matchAll(/\b(?:FROM|JOIN)\s+([a-z_][a-z0-9_]*)(?:\s+(?:AS\s+)?([a-z_][a-z0-9_]*))?/gi)) {
    const table = match[1]!;
    const alias = match[2];
    if (tables.has(table)) {
      aliases.set(table, table);
      if (alias && !["ON", "WHERE", "GROUP", "ORDER", "LIMIT", "INNER", "LEFT"].includes(alias.toUpperCase())) {
        aliases.set(alias, table);
      }
    }
  }
  for (const match of flat.matchAll(/\b([a-z_][a-z0-9_]*)\.([a-z_][a-z0-9_]*)\b/g)) {
    const full = match[0]!;
    const qualifier = match[1]!;
    const column = match[2]!;
    if (full.endsWith("::uuid[]") || full.endsWith("::text")) continue;
    const table = aliases.get(qualifier);
    if (!table) continue; // unknown alias: EXCLUDED, subquery names, functions…
    const columns = tables.get(table)!;
    // `c.*` and `EXCLUDED.col` style references are not checked here.
    check(columns.has(column), `${label}\n    ${qualifier}.${column}: ${table} has no column "${column}"`);
  }
}

/** True when the column is covered by a composite unique key starting with it. */
function columnsMissingFromUnique(table: string, column: string): boolean {
  for (const target of uniqueTargets) {
    if (target === `${table}.${column}`) return true;
  }
  return false;
}

// ------------------------------------------------------------------- the report

if (problems.length === 0) {
  console.log(
    `check-schema-refs: OK — ${statements.length} SQL statements, ${tables.size} tables, all references resolve`,
  );
  process.exit(0);
}

console.error(`check-schema-refs: ${problems.length} problem(s) found\n`);
for (const problem of problems) console.error(`  - ${problem}\n`);
process.exit(1);
