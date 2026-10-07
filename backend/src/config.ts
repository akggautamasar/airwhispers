/**
 * Runtime configuration. Everything has a safe default so that
 * `npm run dev` works with no infrastructure at all (in-memory store).
 */
export interface Config {
  port: number;
  host: string;
  /** Postgres connection string; when absent the in-memory store is used. */
  databaseUrl?: string;
  /** HS256 signing secret. MUST be set in production. */
  jwtSecret: string;
  accessTokenTtlSeconds: number;
  /**
   * Whether a brand-new device may mint an identity on this server.
   *
   * Turn this off on a private server: existing devices keep working (they
   * resume with their device secret), but nobody new can join.
   */
  allowNewDevices: boolean;
  /** Google service-account JSON for Firebase Cloud Messaging (optional). */
  fcmServiceAccountJson?: string;
  fcmProjectId?: string;
  /** Emit `message.created` with the message text in the push payload. */
  pushIncludesContent: boolean;
  logLevel: string;
  /** Trust X-Forwarded-For (only enable behind a known proxy). */
  trustProxy: boolean;
}

function bool(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined) return fallback;
  return ["1", "true", "yes", "on"].includes(value.toLowerCase());
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const isProduction = env.NODE_ENV === "production";
  const secret = env.JWT_SECRET ?? "";
  if (isProduction && secret.length < 32) {
    throw new Error("JWT_SECRET must be set to at least 32 characters in production");
  }
  return {
    port: Number(env.PORT ?? 8080),
    host: env.HOST ?? "0.0.0.0",
    databaseUrl: env.DATABASE_URL,
    jwtSecret: secret || "dev-only-insecure-secret-change-me-please-32",
    accessTokenTtlSeconds: Number(env.ACCESS_TOKEN_TTL_SECONDS ?? 3600),
    allowNewDevices: bool(env.ALLOW_NEW_DEVICES, true),
    fcmServiceAccountJson: env.GOOGLE_SERVICE_ACCOUNT_JSON,
    fcmProjectId: env.FCM_PROJECT_ID,
    pushIncludesContent: bool(env.PUSH_INCLUDES_CONTENT, false),
    logLevel: env.LOG_LEVEL ?? (isProduction ? "info" : "debug"),
    trustProxy: bool(env.TRUST_PROXY, false),
  };
}
