/**
 * Small, dependency-free input validation.
 *
 * Every value that enters the system passes through here: the API is the trust
 * boundary, and hand-rolled validators keep the supply chain tiny.
 */

export class ValidationError extends Error {
  readonly code = "bad_request";
  constructor(message: string) {
    super(message);
    this.name = "ValidationError";
  }
}

export class AuthError extends Error {
  readonly code = "unauthorized";
  constructor(message = "This device is not recognised") {
    super(message);
    this.name = "AuthError";
  }
}

export class ForbiddenError extends Error {
  readonly code = "forbidden";
  constructor(message = "Not allowed") {
    super(message);
    this.name = "ForbiddenError";
  }
}

export class NotFoundError extends Error {
  readonly code = "not_found";
  constructor(message = "Not found") {
    super(message);
    this.name = "NotFoundError";
  }
}

export class ConflictError extends Error {
  readonly code = "conflict";
  constructor(message = "Already exists") {
    super(message);
    this.name = "ConflictError";
  }
}

export class RateLimitError extends Error {
  readonly code = "rate_limited";
  constructor(
    message: string,
    readonly retryAfterMs: number,
  ) {
    super(message);
    this.name = "RateLimitError";
  }
}

export function asString(value: unknown, field: string, opts: { min?: number; max?: number } = {}): string {
  if (typeof value !== "string") throw new ValidationError(`${field} must be a string`);
  const trimmed = value.trim();
  const min = opts.min ?? 0;
  const max = opts.max ?? 10_000;
  if (trimmed.length < min) throw new ValidationError(`${field} must be at least ${min} characters`);
  if (trimmed.length > max) throw new ValidationError(`${field} must be at most ${max} characters`);
  return trimmed;
}

export function asOptionalString(value: unknown, field: string, max = 10_000): string | undefined {
  if (value === undefined || value === null) return undefined;
  return asString(value, field, { max });
}

/**
 * A device code: `k7m2pq`. Case, spaces and dashes are forgiven because humans
 * read these out loud.
 */
export function asCode(value: unknown): string {
  const raw = asString(value, "code", { min: 3, max: 40 });
  const normalised = raw.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (normalised.length !== 6) throw new ValidationError("A code is 6 characters, like k7m2pq");
  return normalised;
}

/** The client-generated installation id (a UUID string in practice). */
export function asDeviceId(value: unknown): string {
  return asString(value, "deviceId", { min: 8, max: 128 });
}

/** A device secret; opaque and long. */
export function asDeviceSecret(value: unknown): string {
  return asString(value, "deviceSecret", { min: 16, max: 512 });
}

export function asDisplayName(value: unknown): string {
  const name = asString(value, "displayName", { min: 1, max: 40 });
  // Control characters would let a sender paint over the UI.
  return name.replace(/[\p{Cc}\p{Cf}]/gu, "").trim() || "Someone";
}

export function asOneOf<T extends string>(value: unknown, field: string, allowed: readonly T[]): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) {
    throw new ValidationError(`${field} must be one of: ${allowed.join(", ")}`);
  }
  return value as T;
}

export function asBoolean(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") throw new ValidationError(`${field} must be a boolean`);
  return value;
}

export function asOptionalBoolean(value: unknown, field: string): boolean | undefined {
  if (value === undefined || value === null) return undefined;
  return asBoolean(value, field);
}

export function asNumber(value: unknown, field: string, min: number, max: number): number {
  if (typeof value !== "number" || Number.isNaN(value)) throw new ValidationError(`${field} must be a number`);
  if (value < min || value > max) throw new ValidationError(`${field} must be between ${min} and ${max}`);
  return value;
}

export function asOptionalNumber(value: unknown, field: string, min: number, max: number): number | undefined {
  if (value === undefined || value === null) return undefined;
  return asNumber(value, field, min, max);
}

export function asUuid(value: unknown, field: string): string {
  const raw = asString(value, field, { min: 8, max: 64 });
  if (!/^[0-9a-fA-F-]{8,64}$/.test(raw)) throw new ValidationError(`${field} must be an identifier`);
  return raw;
}

export function asObject(value: unknown, field = "body"): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ValidationError(`${field} must be an object`);
  }
  return value as Record<string, unknown>;
}

/** Limits a request body size for free-text fields (defence in depth). */
export function asMessageText(value: unknown): string {
  const text = asString(value, "text", { min: 1, max: 2000 });
  if (text.length === 0) throw new ValidationError("text must not be empty");
  return text;
}
