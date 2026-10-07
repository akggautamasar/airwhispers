import {
  createHash,
  createHmac,
  randomBytes,
  randomInt,
  randomUUID,
  scrypt as scryptCallback,
  timingSafeEqual,
  createSign,
  type ScryptOptions,
} from "node:crypto";
import { promisify } from "node:util";
import { CODE_ALPHABET, CODE_LENGTH } from "./types.js";

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
 *  - Device secrets: scrypt with a per-credential random salt (memory-hard, stdlib).
 *  - Access tokens: HS256 JWT, short lived, refreshed silently via the device secret.
 *  - Codes: short random identifiers from an unambiguous alphabet (no 0/o/1/l/i).
 *  - FCM OAuth: RS256 assertion signed with the service-account key.
 *
 * There are no passwords anywhere in this product — a device either holds its
 * secret or it is a brand new identity.
 */

const SCRYPT_KEYLEN = 64;
// Device secrets are 256-bit random values, not human passwords: a *modest*
// scrypt cost keeps silent re-authentication snappy while still making an
// offline attack on a leaked credential database pointless.
const SCRYPT_COST = 4096;

export async function hashSecret(secret: string): Promise<string> {
  const salt = randomBytes(16);
  const derived = await scrypt(secret.normalize("NFKC"), salt, SCRYPT_KEYLEN, {
    N: SCRYPT_COST,
  });
  return `scrypt$${SCRYPT_COST}$${salt.toString("base64url")}$${derived.toString("base64url")}`;
}

export async function verifySecret(secret: string, stored: string): Promise<boolean> {
  const parts = stored.split("$");
  if (parts.length !== 4 || parts[0] !== "scrypt") return false;
  const cost = Number(parts[1]);
  const salt = Buffer.from(parts[2]!, "base64url");
  const expected = Buffer.from(parts[3]!, "base64url");
  const derived = await scrypt(secret.normalize("NFKC"), salt, expected.length, {
    N: cost,
  });
  return derived.length === expected.length && timingSafeEqual(derived, expected);
}

// ----------------------------------------------------------------- device codes

/**
 * A code is the user's whole address book entry: 6 characters, read out loud.
 *
 * 32^6 ≈ 1.07 billion combinations, and registration is rate limited, so codes
 * are not feasibly enumerable. They are stored and compared lowercase; the app
 * may display them uppercase, with or without a dash.
 */
export function newCode(): string {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i++) {
    code += CODE_ALPHABET[randomInt(0, CODE_ALPHABET.length)];
  }
  return code;
}

/**
 * 256-bit device secret: proves this installation already owns an identity.
 * Stored only as an scrypt hash (see [hashSecret]).
 */
export function newDeviceSecret(): string {
  return randomBytes(32).toString("base64url");
}

/** Accepts `k7m2pq`, `K7M-2PQ`, ` k7 m2 pq ` — whatever a human types. */
export function normaliseCode(input: string): string {
  return input.toLowerCase().replace(/[^a-z0-9]/g, "");
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
