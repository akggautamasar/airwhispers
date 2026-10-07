import {
  createHash,
  createHmac,
  randomBytes,
  randomUUID,
  scrypt as scryptCallback,
  timingSafeEqual,
  createSign,
  type ScryptOptions,
} from "node:crypto";
import { promisify } from "node:util";

/** node:util promisify does not carry the options overload, so type it here. */
const scrypt = promisify(scryptCallback) as (
  password: string,
  salt: string | Buffer,
  keylen: number,
  options: ScryptOptions,
) => Promise<Buffer>;

/**
 * Security primitives, deliberately implemented on node:crypto so the backend has
 * no native build dependencies.
 *
 *  - Passwords: scrypt with a per-user random salt (memory-hard, stdlib).
 *  - Access tokens: HS256 JWT, short lived.
 *  - Refresh tokens: opaque 256-bit random values, stored only as SHA-256 hashes,
 *    rotated on every use.
 *  - FCM OAuth: RS256 assertion signed with the service-account key.
 */

const SCRYPT_KEYLEN = 64;
const SCRYPT_COST = 16384;

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const derived = await scrypt(password.normalize("NFKC"), salt, SCRYPT_KEYLEN, {
    N: SCRYPT_COST,
  });
  return `scrypt$${SCRYPT_COST}$${salt.toString("base64url")}$${derived.toString("base64url")}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "scrypt") return false;
  const cost = Number(parts[1]);
  const salt = Buffer.from(parts[2]!, "base64url");
  const expected = Buffer.from(parts[3]!, "base64url");
  const derived = await scrypt(password.normalize("NFKC"), salt, expected.length, {
    N: cost,
  });
  return derived.length === expected.length && timingSafeEqual(derived, expected);
}

// --------------------------------------------------------------------- JWT (HS256)

export interface AccessTokenClaims {
  sub: string;
  iat: number;
  exp: number;
  typ: "access";
  deviceId?: string;
}

function base64url(input: Buffer | string): string {
  return Buffer.from(input).toString("base64url");
}

export function signAccessToken(
  claims: Omit<AccessTokenClaims, "iat" | "exp" | "typ">,
  secret: string,
  ttlSeconds: number,
): { token: string; expiresIn: number } {
  const iat = Math.floor(Date.now() / 1000);
  const exp = iat + ttlSeconds;
  const payload: AccessTokenClaims = { ...claims, iat, exp, typ: "access" };
  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = base64url(JSON.stringify(payload));
  const signature = createHmac("sha256", secret).update(`${header}.${body}`).digest();
  return { token: `${header}.${body}.${base64url(signature)}`, expiresIn: ttlSeconds };
}

export function verifyAccessToken(token: string, secret: string): AccessTokenClaims | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [header, body, signature] = parts as [string, string, string];
  const expected = createHmac("sha256", secret).update(`${header}.${body}`).digest();
  const provided = Buffer.from(signature, "base64url");
  if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return null;
  try {
    const claims = JSON.parse(Buffer.from(body, "base64url").toString("utf8")) as AccessTokenClaims;
    if (claims.typ !== "access") return null;
    if (typeof claims.exp !== "number" || claims.exp * 1000 < Date.now()) return null;
    return claims;
  } catch {
    return null;
  }
}

// -------------------------------------------------------------- refresh tokens

export function newRefreshToken(): { token: string; hash: string } {
  const token = randomBytes(32).toString("base64url");
  return { token, hash: sha256(token) };
}

export function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

export function newId(): string {
  return randomUUID();
}

// --------------------------------------------------------- FCM (RFC 7523 / RS256)

interface ServiceAccount {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

/** Builds the signed assertion Google exchanges for an FCM access token. */
export function buildGoogleAssertion(serviceAccount: ServiceAccount, scope: string): string {
  const iat = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(
    JSON.stringify({
      iss: serviceAccount.client_email,
      scope,
      aud: serviceAccount.token_uri ?? "https://oauth2.googleapis.com/token",
      iat,
      exp: iat + 3600,
    }),
  );
  const signer = createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const signature = signer.sign(serviceAccount.private_key);
  return `${header}.${claims}.${signature.toString("base64url")}`;
}
