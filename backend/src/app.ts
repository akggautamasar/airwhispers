import Fastify, { type FastifyError, type FastifyInstance, type FastifyRequest } from "fastify";
import { randomUUID } from "node:crypto";
import type { Config } from "./config.js";
import {
  AuthError,
  ConflictError,
  ForbiddenError,
  NotFoundError,
  RateLimitError,
  ValidationError,
  asBoolean,
  asEmail,
  asMessageText,
  asNumber,
  asObject,
  asOneOf,
  asOptionalBoolean,
  asOptionalNumber,
  asOptionalString,
  asPassword,
  asString,
  asUuid,
} from "./validators.js";
import {
  hashPassword,
  newRefreshToken,
  sha256,
  signAccessToken,
  verifyAccessToken,
  verifyPassword,
} from "./crypto.js";
import type { FcmPusher } from "./push.js";
import type { RealtimeHub } from "./realtime.js";
import {
  type Contact,
  type Conversation,
  type Device,
  type Message,
  type MessagePriority,
  type Store,
  type User,
  type UserSettings,
  type UserWithSecret,
} from "./types.js";

export interface AppDeps {
  config: Config;
  store: Store;
  hub: RealtimeHub;
  pusher: FcmPusher;
  version?: string;
}

const API = "/api/v1";

interface RateBucket {
  count: number;
  resetAt: number;
}

/** Fixed-window limiter. Enough for abuse protection without Redis. */
class RateLimiter {
  private buckets = new Map<string, RateBucket>();

  check(key: string, limit: number, windowMs: number): void {
    const now = Date.now();
    const bucket = this.buckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      this.buckets.set(key, { count: 1, resetAt: now + windowMs });
      return;
    }
    bucket.count++;
    if (bucket.count > limit) {
      throw new RateLimitError("Too many requests, slow down", bucket.resetAt - now);
    }
    if (this.buckets.size > 10_000) this.prune(now);
  }

  private prune(now: number): void {
    for (const [key, bucket] of this.buckets) {
      if (bucket.resetAt <= now) this.buckets.delete(key);
    }
  }
}

export function buildApp(deps: AppDeps): FastifyInstance {
  const { config, store, hub, pusher } = deps;
  const version = deps.version ?? "1.0.0";
  const limiter = new RateLimiter();

  const app = Fastify({
    logger: { level: config.logLevel },
    trustProxy: config.trustProxy,
    bodyLimit: 256 * 1024,
  });

  // Endpoints such as POST /messages/:id/read take no parameters, and clients
  // legitimately send an empty body with a JSON content type. Without this the
  // platform rejects them with FST_ERR_CTP_EMPTY_JSON_BODY.
  try {
    app.removeContentTypeParser("application/json");
  } catch {
    // Not registered by default: nothing to remove.
  }
  app.addContentTypeParser("application/json", { parseAs: "string" }, (_request, body, done) => {
    const raw = typeof body === "string" ? body.trim() : "";
    if (raw.length === 0) {
      done(null, {});
      return;
    }
    try {
      done(null, JSON.parse(raw));
    } catch {
      done(new ValidationError("Body must be valid JSON"), undefined);
    }
  });

  // ------------------------------------------------------------------ helpers

  const iso = (millis: number): string => new Date(millis).toISOString();

  const authenticate = async (request: FastifyRequest): Promise<{ userId: string; deviceId: string | null }> => {
    const header = request.headers.authorization ?? "";
    const token = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : "";
    if (!token) throw new AuthError();
    const claims = verifyAccessToken(token, config.jwtSecret);
    if (!claims) throw new AuthError("Session expired");
    return { userId: claims.sub, deviceId: claims.deviceId ?? null };
  };

  const userDto = (user: User) => ({
    id: user.id,
    email: user.email,
    displayName: user.displayName,
    handle: user.handle,
    createdAt: iso(user.createdAt),
  });

  const messageDto = (message: Message) => ({
    id: message.id,
    clientMessageId: message.clientMessageId,
    conversationId: message.conversationId,
    senderId: message.senderId,
    recipientId: message.recipientId,
    text: message.text,
    createdAt: iso(message.createdAt),
    priority: message.priority,
    deliveryStatus: message.deliveredAt ? "delivered" : "sent",
    readStatus: message.readAt ? "read" : "unread",
    // Call Assist may speak it unless the sender asked for silence.
    speakEligible: true,
  });

  const settingsDto = (settings: UserSettings) => ({
    speakMessages: settings.speakMessages,
    onlyDuringCalls: settings.onlyDuringCalls,
    trustedContactsOnly: settings.trustedContactsOnly,
    preferBluetooth: settings.preferBluetooth,
    languageTag: settings.languageTag,
    speechRate: settings.speechRate,
    pitch: settings.pitch,
    emojiMode: settings.emojiMode,
  });

  const conversationDto = async (conversation: Conversation, viewerId: string) => {
    const peerId = conversation.memberIds.find((id) => id !== viewerId) ?? viewerId;
    const peer = await store.findUserById(peerId);
    const last = (await store.lastMessages([conversation.id])).get(conversation.id);
    const unread = await store.unreadCount(conversation.id, viewerId);
    const contact = await store.findContact(viewerId, peerId);
    return {
      id: conversation.id,
      peer: peer
        ? userDto(peer)
        : { id: peerId, email: "unknown@airwhispers", displayName: "Unknown", handle: null, createdAt: iso(Date.now()) },
      lastMessage: last ? messageDto(last) : null,
      unreadCount: unread,
      peerTrusted: contact?.isTrusted ?? false,
    };
  };

  const contactDto = (contact: Contact, user: User, conversationId: string | null) => ({
    id: contact.contactUserId,
    user: userDto(user),
    isTrusted: contact.isTrusted,
    conversationId,
  });

  /**
   * Path parameters are client input like any other: a malformed id must fail
   * validation (400) instead of reaching the database, where PostgreSQL answers
   * `invalid input syntax for type uuid` and the API would return a 500.
   */
  const idParam = (params: unknown, key: string): string =>
    asUuid((params as Record<string, unknown>)[key], key);

  /** Ensures the caller is a member of a conversation. */
  const requireConversation = async (conversationId: string, userId: string): Promise<Conversation> => {
    const conversation = await store.findConversation(conversationId);
    if (!conversation) throw new NotFoundError("Conversation not found");
    if (!conversation.memberIds.includes(userId)) throw new ForbiddenError("Not a member of this conversation");
    return conversation;
  };

  const issueTokens = async (user: User, deviceId: string | null) => {
    const access = signAccessToken({ sub: user.id, deviceId: deviceId ?? undefined }, config.jwtSecret, config.accessTokenTtlSeconds);
    const refresh = newRefreshToken();
    await store.saveRefreshToken({
      tokenHash: refresh.hash,
      userId: user.id,
      deviceId,
      expiresAt: Date.now() + config.refreshTokenTtlSeconds * 1000,
      revokedAt: null,
    });
    return {
      accessToken: access.token,
      refreshToken: refresh.token,
      expiresIn: access.expiresIn,
      tokenType: "Bearer" as const,
    };
  };

  // ------------------------------------------------------------ error handling

  app.setErrorHandler<FastifyError & { code?: string; retryAfterMs?: number }>((error, request, reply) => {
    const status = (() => {
      if (error instanceof ValidationError) return 400;
      if (error instanceof AuthError) return 401;
      if (error instanceof ForbiddenError) return 403;
      if (error instanceof NotFoundError) return 404;
      if (error instanceof ConflictError) return 409;
      if (error instanceof RateLimitError) return 429;
      const code = error.statusCode;
      return code && code >= 400 && code < 600 ? code : 500;
    })();

    if (status >= 500) {
      request.log.error({ err: error }, "request.failed");
    } else if (status !== 404) {
      request.log.info({ code: error.code, path: request.url }, "request.rejected");
    }

    reply.status(status).send({
      error: {
        code: error.code ?? (status >= 500 ? "internal_error" : "bad_request"),
        message: status >= 500 ? "Unexpected server error" : error.message,
        retryAfterMs: error instanceof RateLimitError ? error.retryAfterMs : undefined,
      },
    });
  });

  app.setNotFoundHandler((_request, reply) => {
    reply.status(404).send({ error: { code: "not_found", message: "No such endpoint" } });
  });

  // ------------------------------------------------------------------- health

  app.get("/healthz", async () => ({ status: "ok", version, time: iso(Date.now()) }));
  app.get(`${API}/health`, async () => ({ status: "ok", version, time: iso(Date.now()) }));

  // --------------------------------------------------------------------- auth

  app.post(`${API}/auth/register`, async (request, reply) => {
    limiter.check(`register:${request.ip}`, 10, 60_000);
    const body = asObject(request.body);
    const email = asEmail(body.email);
    const password = asPassword(body.password);
    const displayName = asString(body.displayName ?? email.split("@")[0], "displayName", { min: 1, max: 80 });
    const deviceId = asOptionalString(body.deviceId, "deviceId", 64) ?? null;

    if (await store.findUserByEmail(email)) throw new ConflictError("That email is already registered");

    const user = await store.createUser({ email, displayName, passwordHash: await hashPassword(password) });
    const tokens = await issueTokens(user, deviceId);
    await store.getSettings(user.id);
    reply.status(201);
    return { user: userDto(user), tokens };
  });

  app.post(`${API}/auth/login`, async (request) => {
    limiter.check(`login:${request.ip}`, 20, 60_000);
    const body = asObject(request.body);
    const email = asEmail(body.email);
    const password = asString(body.password, "password", { min: 1, max: 200 });
    const deviceId = asOptionalString(body.deviceId, "deviceId", 64) ?? null;

    const user = await store.findUserByEmail(email);
    // Always run a hash comparison so timing does not reveal whether the email exists.
    const ok = user ? await verifyPassword(password, user.passwordHash) : await verifyPassword(password, "scrypt$16384$AAAA$AAAA");
    if (!user || !ok) throw new AuthError("Email or password is incorrect");

    const tokens = await issueTokens(user, deviceId);
    return { user: userDto(user), tokens };
  });

  app.post(`${API}/auth/refresh`, async (request) => {
    limiter.check(`refresh:${request.ip}`, 60, 60_000);
    const body = asObject(request.body);
    const token = asString(body.refreshToken, "refreshToken", { min: 10, max: 400 });
    const hash = sha256(token);
    const record = await store.findRefreshToken(hash);
    if (!record || record.revokedAt !== null || record.expiresAt < Date.now()) {
      throw new AuthError("Refresh token is not valid");
    }
    const user = await store.findUserById(record.userId);
    if (!user) throw new AuthError("Refresh token is not valid");

    // Rotation: the presented token dies here.
    await store.revokeRefreshToken(hash, Date.now());
    return await issueTokens(user, record.deviceId);
  });

  app.post(`${API}/auth/logout`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const body = request.body ? asObject(request.body) : {};
    const token = asOptionalString(body.refreshToken, "refreshToken", 400);
    if (token) await store.revokeRefreshToken(sha256(token), Date.now());
    else await store.revokeAllRefreshTokens(userId);
    reply.status(204);
    return null;
  });

  // -------------------------------------------------------------------- users

  app.get(`${API}/users/me`, async (request) => {
    const { userId } = await authenticate(request);
    const user = await store.findUserById(userId);
    if (!user) throw new NotFoundError("User not found");
    return { user: userDto(user) };
  });

  app.patch(`${API}/users/me`, async (request) => {
    const { userId } = await authenticate(request);
    const body = asObject(request.body);
    const displayName = asOptionalString(body.displayName, "displayName", 80);
    const user = await store.updateUser(userId, displayName ? { displayName } : {});
    return { user: userDto(user) };
  });

  // ------------------------------------------------------------ conversations

  app.get(`${API}/conversations`, async (request) => {
    const { userId } = await authenticate(request);
    const conversations = await store.listConversations(userId);
    const items = await Promise.all(conversations.map((c) => conversationDto(c, userId)));
    items.sort((a, b) => (b.lastMessage?.createdAt ?? "").localeCompare(a.lastMessage?.createdAt ?? ""));
    return { conversations: items };
  });

  app.post(`${API}/conversations`, async (request, reply) => {
    const { userId } = await authenticate(request);
    limiter.check(`conversation:${userId}`, 60, 60_000);
    const body = asObject(request.body);
    const peerUserId = asUuid(body.peerUserId, "peerUserId");
    if (peerUserId === userId) throw new ValidationError("You cannot start a conversation with yourself");
    const peer = await store.findUserById(peerUserId);
    if (!peer) throw new NotFoundError("That user does not exist");

    const conversation = await store.getOrCreateConversation(userId, peerUserId);
    reply.status(201);
    return { conversation: await conversationDto(conversation, userId) };
  });

  app.get(`${API}/conversations/:id/messages`, async (request) => {
    const { userId } = await authenticate(request);
    const conversationId = idParam(request.params, "id");
    const query = request.query as { limit?: string };
    const limit = Math.min(Math.max(Number(query.limit ?? 100) || 100, 1), 200);
    await requireConversation(conversationId, userId);
    const messages = await store.listMessages(conversationId, limit);

    // Seeing the history implies delivery; read receipts stay explicit.
    await Promise.all(
      messages
        .filter((m) => m.recipientId === userId && m.deliveredAt === null)
        .map((m) => store.markMessageDelivered(m.id, Date.now())),
    );
    return { messages: messages.map(messageDto) };
  });

  app.post(`${API}/conversations/:id/messages`, async (request, reply) => {
    const { userId } = await authenticate(request);
    limiter.check(`send:${userId}`, 120, 60_000);
    const conversation = await requireConversation(idParam(request.params, "id"), userId);
    const body = asObject(request.body);

    const clientMessageId = asString(body.clientMessageId, "clientMessageId", { min: 4, max: 120 });
    const text = asMessageText(body.text);
    const priority = asOneOf<MessagePriority>(body.priority ?? "NORMAL", "priority", ["NORMAL", "SPEAK_NOW"]);
    const recipientId = conversation.memberIds.find((id) => id !== userId);
    if (!recipientId) throw new ValidationError("Conversation has no recipient");

    const now = Date.now();
    const { message, created } = await store.insertMessage({
      id: randomUUID(),
      clientMessageId,
      conversationId: conversation.id,
      senderId: userId,
      recipientId,
      text,
      priority,
      createdAt: now,
      deliveredAt: null,
      readAt: null,
      spokenAt: null,
    });

    if (created) {
      const sender = await store.findUserById(userId);
      const dto = messageDto(message);
      // Fast path: anyone with an open socket (sender's other devices + recipient).
      hub.broadcastToUsers([recipientId, userId], { type: "message.created", data: dto });

      // Slow path: cloud push for devices that are not connected.
      const devices = await store.devicesForUsers([recipientId]);
      if (devices.length > 0) {
        const data: Record<string, string> = {
          type: "message.created",
          conversationId: message.conversationId,
          messageId: message.id,
          clientMessageId: message.clientMessageId,
          senderId: message.senderId,
          senderName: sender?.displayName ?? "Someone",
          priority: message.priority,
          createdAt: iso(message.createdAt),
        };
        // Content-free by default: the client fetches the body over TLS. Operators
        // who accept sending bodies through FCM can switch this on.
        if (config.pushIncludesContent) data.text = message.text;
        await pusher.sendData(devices, data, message.priority === "SPEAK_NOW");
      }
    } else {
      request.log.info({ clientMessageId }, "message.duplicate_suppressed");
    }

    reply.status(created ? 201 : 200);
    return { message: messageDto(message) };
  });

  app.post(`${API}/messages/:id/read`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const message = await store.findMessage(idParam(request.params, "id"));
    if (!message) throw new NotFoundError("Message not found");
    if (message.recipientId !== userId) throw new ForbiddenError("Only the recipient can mark a message read");

    const now = Date.now();
    await store.markMessageDelivered(message.id, now);
    const updated = await store.markConversationRead(message.conversationId, userId, now);
    hub.broadcastToUsers([message.senderId], {
      type: "message.read",
      data: { conversationId: message.conversationId, messageIds: updated.map((m) => m.id), readAt: iso(now) },
    });
    reply.status(204);
    return null;
  });

  /**
   * Call Assist feedback: the recipient's phone actually spoke the message.
   * Purely informational for the sender ("read aloud"), never a delivery gate.
   */
  app.post(`${API}/messages/:id/spoken`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const message = await store.findMessage(idParam(request.params, "id"));
    if (!message) throw new NotFoundError("Message not found");
    if (message.recipientId !== userId) throw new ForbiddenError("Only the recipient can report speech");

    const now = Date.now();
    await store.markMessageSpoken(message.id, now);
    const refreshed = await store.findMessage(message.id);
    if (refreshed) {
      hub.broadcastToUsers([message.senderId], { type: "message.updated", data: messageDto(refreshed) });
    }
    reply.status(204);
    return null;
  });

  // ---------------------------------------------------------------- contacts

  app.get(`${API}/contacts`, async (request) => {
    const { userId } = await authenticate(request);
    const contacts = await store.listContacts(userId);
    const conversations = await store.listConversations(userId);
    const items = [];
    for (const contact of contacts) {
      const user = await store.findUserById(contact.contactUserId);
      if (!user) continue;
      const conversation =
        conversations.find((c) => c.memberIds.includes(contact.contactUserId))?.id ?? null;
      items.push(contactDto(contact, user, conversation));
    }
    return { contacts: items };
  });

  app.post(`${API}/contacts`, async (request, reply) => {
    const { userId } = await authenticate(request);
    limiter.check(`contact:${userId}`, 40, 60_000);
    const body = asObject(request.body);
    const email = asEmail(body.email);
    const peer = await store.findUserByEmail(email);
    if (!peer) throw new NotFoundError("No AirWhispers account uses that email");
    if (peer.id === userId) throw new ValidationError("That is your own address");

    // Privacy-first default: a new contact may not trigger speech.
    const contact = await store.addContact(userId, peer.id, false);
    const conversation = await store.getOrCreateConversation(userId, peer.id);
    reply.status(201);
    return { contact: contactDto(contact, peer, conversation.id) };
  });

  app.patch(`${API}/contacts/:id`, async (request) => {
    const { userId } = await authenticate(request);
    const peerId = idParam(request.params, "id");
    const body = asObject(request.body);
    const isTrusted = asOptionalBoolean(body.isTrusted, "isTrusted");
    let contact = await store.findContact(userId, peerId);
    if (!contact) {
      // Trusting someone you have not explicitly added yet is a legitimate
      // action (the chat screen offers exactly this switch).
      const peerExists = await store.findUserById(peerId);
      if (!peerExists) throw new NotFoundError("That user does not exist");
      contact = await store.addContact(userId, peerId, isTrusted ?? false);
    }
    contact = (await store.updateContact(userId, peerId, isTrusted === undefined ? {} : { isTrusted })) ?? contact;
    const peer = await store.findUserById(peerId);
    if (!peer) throw new NotFoundError("Contact not found");
    const conversations = await store.listConversations(userId);
    const conversationId = conversations.find((c) => c.memberIds.includes(peerId))?.id ?? null;
    return { contact: contactDto(contact, peer, conversationId) };
  });

  app.delete(`${API}/contacts/:id`, async (request, reply) => {
    const { userId } = await authenticate(request);
    await store.deleteContact(userId, idParam(request.params, "id"));
    reply.status(204);
    return null;
  });

  // ---------------------------------------------------------------- settings

  app.get(`${API}/settings`, async (request) => {
    const { userId } = await authenticate(request);
    return { settings: settingsDto(await store.getSettings(userId)) };
  });

  app.patch(`${API}/settings`, async (request) => {
    const { userId } = await authenticate(request);
    const body = asObject(request.body);
    const patch: Partial<UserSettings> = {};
    const speakMessages = asOptionalBoolean(body.speakMessages, "speakMessages");
    if (speakMessages !== undefined) patch.speakMessages = speakMessages;
    const onlyDuringCalls = asOptionalBoolean(body.onlyDuringCalls, "onlyDuringCalls");
    if (onlyDuringCalls !== undefined) patch.onlyDuringCalls = onlyDuringCalls;
    const trustedOnly = asOptionalBoolean(body.trustedContactsOnly, "trustedContactsOnly");
    if (trustedOnly !== undefined) patch.trustedContactsOnly = trustedOnly;
    const preferBluetooth = asOptionalBoolean(body.preferBluetooth, "preferBluetooth");
    if (preferBluetooth !== undefined) patch.preferBluetooth = preferBluetooth;
    const languageTag = asOptionalString(body.languageTag, "languageTag", 16);
    if (languageTag) patch.languageTag = languageTag;
    const speechRate = asOptionalNumber(body.speechRate, "speechRate", 0.3, 2);
    if (speechRate !== undefined) patch.speechRate = speechRate;
    const pitch = asOptionalNumber(body.pitch, "pitch", 0.5, 2);
    if (pitch !== undefined) patch.pitch = pitch;
    if (body.emojiMode !== undefined) {
      patch.emojiMode = asOneOf(body.emojiMode, "emojiMode", ["IGNORE", "DESCRIBE_IMPORTANT", "READ_ALL"] as const);
    }
    return { settings: settingsDto(await store.updateSettings(userId, patch)) };
  });

  // ----------------------------------------------------------------- devices

  app.post(`${API}/devices`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const body = asObject(request.body);
    const deviceId = asString(body.deviceId, "deviceId", { min: 4, max: 64 });
    const device: Device = {
      deviceId,
      userId,
      platform: asOptionalString(body.platform, "platform", 20) ?? "android",
      pushToken: asOptionalString(body.pushToken, "pushToken", 4096) ?? null,
      appVersion: asOptionalString(body.appVersion, "appVersion", 40) ?? null,
      updatedAt: Date.now(),
    };
    await store.upsertDevice(device);
    reply.status(204);
    return null;
  });

  app.delete(`${API}/devices/:deviceId`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const params = request.params as { deviceId: string };
    await store.deleteDevice(userId, params.deviceId);
    reply.status(204);
    return null;
  });

  // ------------------------------------------------------------------- misc

  app.get(`${API}/realtime/info`, async () => ({
    path: `${API}/realtime`,
    protocol: "airwhispers.realtime.v1",
    events: [
      "message.created",
      "message.updated",
      "message.read",
      "conversation.updated",
      "contact.updated",
      "auth.required",
      "auth.ok",
      "pong",
      "error",
    ],
    connectedSockets: hub.connectionCount(),
  }));

  return app;
}

/** Exported for tests that need to assert on hashing behaviour. */
export const __testing = { hashPassword, verifyPassword, asBoolean, asNumber, asUuid, asEmail };
export type { UserWithSecret };
