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
  constructor(message = "Authentication required") {
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

export function asEmail(value: unknown): string {
  const email = asString(value, "email", { min: 3, max: 254 }).toLowerCase();
  if (!/^[^@\s]+@[^@\s.]+\.[^@\s]+$/.test(email)) throw new ValidationError("email is not valid");
  return email;
}

export function asPassword(value: unknown): string {
  const password = asString(value, "password", { min: 8, max: 200 });
  if (password.length < 8) throw new ValidationError("password must be at least 8 characters");
  return password;
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
  const text = asString(value, "text", { min: 1, max: 4000 });
  if (text.length === 0) throw new ValidationError("text must not be empty");
  return text;
}
