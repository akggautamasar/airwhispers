import { loadConfig } from "./config.js";
import { buildApp } from "./app.js";
import { MemoryStore } from "./store.memory.js";
import { PgStore } from "./store.pg.js";
import { RealtimeHub } from "./realtime.js";
import { FcmPusher } from "./push.js";
import { verifyAccessToken } from "./crypto.js";
import { readFileSync } from "node:fs";
import type { Store } from "./types.js";

const config = loadConfig();
const version = readVersion();

const log = (level: "info" | "warn" | "error", message: string, meta?: Record<string, unknown>): void => {
  const line = JSON.stringify({ time: new Date().toISOString(), level, message, ...meta });
  if (level === "error") console.error(line);
  else if (level === "warn") console.warn(line);
  else console.log(line);
};

const store: Store = config.databaseUrl
  ? PgStore.fromConnectionString(config.databaseUrl)
  : new MemoryStore();

log("info", "store.selected", { kind: config.databaseUrl ? "postgres" : "memory" });

const hub = new RealtimeHub({
  path: "/api/v1/realtime",
  verifyToken: (token) => verifyAccessToken(token, config.jwtSecret),
  // Presence: tell the people this device talks to when it comes and goes.
  presenceAudience: (userId) => store.peerIdsOf(userId),
  // Typing indicators are forwarded to the other member of the conversation.
  conversationMembers: async (conversationId) => (await store.findConversation(conversationId))?.memberIds ?? [],
  log: (level, message, meta) => log(level, message, meta),
  onAuthenticated: (userId) => log("info", "realtime.device_online", { userId }),
});

const pusher = new FcmPusher(config, (level, message, meta) => log(level, message, meta));

const app = buildApp({ config, store, hub, pusher, version });
hub.attach(app.server);

try {
  await app.listen({ port: config.port, host: config.host });
  log("info", "server.listening", { port: config.port, host: config.host, version, push: pusher.enabled });
} catch (error) {
  log("error", "server.listen_failed", { error: String(error) });
  process.exit(1);
}

let shuttingDown = false;
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    if (shuttingDown) return;
    shuttingDown = true;
    log("info", "server.shutdown", { signal });
    void (async () => {
      hub.close();
      await app.close();
      await store.close();
      process.exit(0);
    })();
  });
}

/** Reads package.json by walking up from this module (works from src/ and dist/). */
function readVersion(): string {
  let dir = new URL(".", import.meta.url);
  for (let depth = 0; depth < 4; depth++) {
    try {
      const raw = readFileSync(new URL("package.json", dir), "utf8");
      const parsed = JSON.parse(raw) as { name?: string; version?: string };
      if (parsed.name === "airwhispers-backend" || parsed.version) return parsed.version ?? "0.0.0";
    } catch {
      // keep walking up
    }
    dir = new URL("../", dir);
  }
  return "0.0.0";
}
