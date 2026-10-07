import Fastify, { type FastifyError, type FastifyInstance, type FastifyRequest } from "fastify";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import type { Config } from "./config.js";
import {
  AuthError,
  ConflictError,
  ForbiddenError,
  NotFoundError,
  RateLimitError,
  ValidationError,
  asCode,
  asDeviceId,
  asMessageText,
  asObject,
  asOneOf,
  asOptionalBoolean,
  asOptionalString,
  asString,
  asUuid,
  asDisplayName,
} from "./validators.js";
import {
  hashSecret,
  newCode,
  newDeviceSecret,
  normaliseCode,
  signAccessToken,
  verifyAccessToken,
  verifySecret,
} from "./crypto.js";
import type { FcmPusher } from "./push.js";
import type { RealtimeHub } from "./realtime.js";
import type { Conversation, Device, Message, MessagePriority, Store, User } from "./types.js";

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

/**
 * The whole HTTP surface: one file, easy to audit.
 *
 * Identity model in one paragraph: a device registers once (`POST /device/register`)
 * and receives a short code — its only address — plus a long-lived device secret
 * that lets it mint fresh access tokens with no user interaction, forever
 * (`POST /device/token`). There is no email, no password, no sign-up form and no
 * account recovery, because there is nothing to recover: losing the secret means
 * registering again and being handed a *new* code.
 */
export function buildApp(deps: AppDeps): FastifyInstance {
  const { config, store, hub, pusher } = deps;
  const version = deps.version ?? "1.0.0";
  const limiter = new RateLimiter();

  const app = Fastify({
    logger: { level: config.logLevel },
    trustProxy: config.trustProxy,
    bodyLimit: 128 * 1024,
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
    code: user.code,
    displayName: user.displayName,
    createdAt: iso(user.createdAt),
  });

  const messageDto = (message: Message, speakable = false) => ({
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
    spoken: message.spokenAt !== null,
    /** Whether the recipient currently lets this sender whisper to them. */
    speakEligible: speakable,
  });

  const conversationDto = async (conversation: Conversation, viewerId: string) => {
    const peerId = conversation.memberIds.find((id) => id !== viewerId) ?? viewerId;
    const peer = await store.findUserById(peerId);
    const last = (await store.lastMessages([conversation.id])).get(conversation.id);
    const unread = await store.unreadCount(conversation.id, viewerId);
    const iAllow = await store.findTrust(viewerId, peerId);
    const theyAllow = await store.findTrust(peerId, viewerId);
    return {
      id: conversation.id,
      createdAt: iso(conversation.createdAt),
      peer: peer
        ? { ...userDto(peer), online: hub.isOnline(peerId) }
        : { id: peerId, code: "------", displayName: "Unknown", createdAt: iso(conversation.createdAt), online: false },
      lastMessage: last ? messageDto(last, theyAllow?.trusted === true) : null,
      unreadCount: unread,
      /** May this peer trigger speech on *this* device? */
      youAllowSpeak: iAllow?.trusted === true,
      /** May this device's messages be spoken on the peer's device? */
      peerAllowsSpeak: theyAllow?.trusted === true,
    };
  };

  /** Ensures the caller is a member of a conversation. */
  const requireConversation = async (conversationId: string, userId: string): Promise<Conversation> => {
    const conversation = await store.findConversation(conversationId);
    if (!conversation) throw new NotFoundError("Conversation not found");
    if (!conversation.memberIds.includes(userId)) throw new ForbiddenError("Not a member of this conversation");
    return conversation;
  };

  const issueTokens = (user: User, deviceId: string) => {
    const access = signAccessToken({ sub: user.id, deviceId }, config.jwtSecret, config.accessTokenTtlSeconds);
    return {
      accessToken: access.token,
      expiresIn: access.expiresIn,
      tokenType: "Bearer" as const,
    };
  };

  /**
   * Allocates an identity with a code nobody else on this server holds.
   * `createUser` refuses duplicates, so a collision is retried a few times.
   */
  const createIdentity = async (displayName: string): Promise<User> => {
    for (let attempt = 0; attempt < 8; attempt++) {
      try {
        return await store.createUser({ code: newCode(), displayName });
      } catch (error) {
        if ((error as Error).message !== "code_taken") throw error;
      }
    }
    throw new ConflictError("Could not allocate a free code, please retry");
  };

  /** Checks the `{deviceId, deviceSecret}` pair against every credential we hold. */
  const credentialFor = async (deviceId: string, secret: string) => {
    const credentials = await store.credentialsForDevice(deviceId);
    for (const credential of credentials) {
      if (await verifySecret(secret, credential.secretHash)) return credential;
    }
    return null;
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

  /** What the app asks first: "are you an AirWhispers server, and what do I need?" */
  app.get(`${API}/server`, async () => ({
    name: "airwhispers",
    version,
    apiVersion: "v1",
    realtimePath: `${API}/realtime`,
    registrationOpen: config.allowNewDevices,
    registration: "device", // no accounts: a device registers once and gets a code
    codeLength: 6,
    connectedSockets: hub.connectionCount(),
  }));

  // ------------------------------------------------------------------ devices

  /**
   * One endpoint for both halves of a device's life:
   *  - without a secret: mint a new identity + code (first launch),
   *  - with a secret: prove this installation is known and get fresh tokens.
   */
  app.post(`${API}/device/register`, async (request, reply) => {
    limiter.check(`register:${request.ip}`, 60, 60_000);
    const body = asObject(request.body);
    const deviceId = asDeviceId(body.deviceId);
    const platform = asOptionalString(body.platform, "platform", 20) ?? "android";
    const appVersion = asOptionalString(body.appVersion, "appVersion", 40) ?? null;
    const secret = asOptionalString(body.deviceSecret, "deviceSecret", 512);

    if (secret) {
      const credential = await credentialFor(deviceId, secret);
      if (!credential) throw new AuthError("This device is not recognised by this server");
      const user = await store.findUserById(credential.userId);
      if (!user) throw new AuthError("This device is not recognised by this server");
      await store.touchCredential(deviceId, user.id, Date.now());
      reply.status(200);
      return { user: userDto(user), tokens: issueTokens(user, deviceId) };
    }

    if (!config.allowNewDevices) {
      throw new ForbiddenError("This server is not accepting new devices");
    }

    const displayName = asDisplayName(body.displayName ?? "Someone");
    const user = await createIdentity(displayName);
    const newSecret = newDeviceSecret();
    const now = Date.now();
    await store.saveCredential({
      deviceId,
      userId: user.id,
      secretHash: await hashSecret(newSecret),
      platform,
      appVersion,
      createdAt: now,
      lastSeenAt: now,
    });
    request.log.info({ code: user.code }, "device.registered");
    reply.status(201);
    return { user: userDto(user), tokens: issueTokens(user, deviceId), deviceSecret: newSecret };
  });

  /** Silent re-authentication: no UI, no user input, just a fresh access token. */
  app.post(`${API}/device/token`, async (request) => {
    limiter.check(`token:${request.ip}`, 120, 60_000);
    const body = asObject(request.body);
    const deviceId = asDeviceId(body.deviceId);
    const secret = asString(body.deviceSecret, "deviceSecret", { min: 16, max: 512 });
    const credential = await credentialFor(deviceId, secret);
    if (!credential) throw new AuthError("This device is not recognised by this server");
    const user = await store.findUserById(credential.userId);
    if (!user) throw new AuthError("This device is not recognised by this server");
    await store.touchCredential(deviceId, user.id, Date.now());
    return { user: userDto(user), tokens: issueTokens(user, deviceId) };
  });

  /**
   * Forget this identity on the server and hand out a brand-new code next time.
   * Also drops the device's conversations from its point of view.
   */
  app.post(`${API}/device/forget`, async (request, reply) => {
    limiter.check(`forget:${request.ip}`, 10, 60_000);
    const body = asObject(request.body);
    const deviceId = asDeviceId(body.deviceId);
    const secret = asString(body.deviceSecret, "deviceSecret", { min: 16, max: 512 });
    const credential = await credentialFor(deviceId, secret);
    if (!credential) throw new AuthError("This device is not recognised by this server");
    await store.revokeCredential(deviceId, credential.userId);
    request.log.info({ userId: credential.userId }, "device.forgotten");
    reply.status(204);
    return null;
  });

  // --------------------------------------------------------------------- me

  app.get(`${API}/me`, async (request) => {
    const { userId } = await authenticate(request);
    const user = await store.findUserById(userId);
    if (!user) throw new NotFoundError("Device not found");
    return { user: userDto(user) };
  });

  app.patch(`${API}/me`, async (request) => {
    const { userId } = await authenticate(request);
    const body = asObject(request.body);
    const displayName = asOptionalString(body.displayName, "displayName", 40);
    const user = await store.updateUser(userId, displayName ? { displayName: asDisplayName(displayName) } : {});
    // Everyone who talks to this device should see the new name.
    hub.broadcastToUsers(await store.peerIdsOf(userId), {
      type: "peer.updated",
      data: { userId, displayName: user.displayName },
    });
    return { user: userDto(user) };
  });

  // ------------------------------------------------------------ conversations

  app.get(`${API}/conversations`, async (request) => {
    const { userId } = await authenticate(request);
    const conversations = await store.listConversations(userId);
    const items = await Promise.all(conversations.map((c) => conversationDto(c, userId)));
    items.sort((a, b) => (b.lastMessage?.createdAt ?? b.createdAt).localeCompare(a.lastMessage?.createdAt ?? a.createdAt));
    return { conversations: items };
  });

  /** Open (or create) a chat with the device that owns `code`. */
  app.post(`${API}/conversations`, async (request, reply) => {
    const { userId } = await authenticate(request);
    limiter.check(`chat:${userId}`, 60, 60_000);
    const body = asObject(request.body);
    const code = normaliseCode(asCode(body.code));
    const peer = await store.findUserByCode(code);
    if (!peer) throw new NotFoundError("No device is using that code");
    if (peer.id === userId) throw new ValidationError("That code is this device");

    const conversation = await store.getOrCreateConversation(userId, peer.id);
    reply.status(201);
    return { conversation: await conversationDto(conversation, userId) };
  });

  app.get(`${API}/conversations/:id/messages`, async (request) => {
    const { userId } = await authenticate(request);
    const params = request.params as { id: string };
    const query = request.query as { limit?: string };
    const limit = Math.min(Math.max(Number(query.limit ?? 100) || 100, 1), 200);
    await requireConversation(params.id, userId);
    const messages = await store.listMessages(params.id, limit);

    // Seeing the history implies delivery; read receipts stay explicit.
    await Promise.all(
      messages
        .filter((m) => m.recipientId === userId && m.deliveredAt === null)
        .map((m) => store.markMessageDelivered(m.id, Date.now())),
    );
    const peerId = messages.find((m) => m.recipientId === userId)?.senderId ?? null;
    const speakable = peerId ? (await store.findTrust(userId, peerId))?.trusted === true : false;
    return { messages: messages.map((m) => messageDto(m, speakable)) };
  });

  app.post(`${API}/conversations/:id/messages`, async (request, reply) => {
    const { userId } = await authenticate(request);
    limiter.check(`send:${userId}`, 120, 60_000);
    const params = request.params as { id: string };
    const body = asObject(request.body);
    const conversation = await requireConversation(params.id, userId);

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

    // Consent is owned by the listener: the sender only learns whether it may
    // be spoken, never forces it.
    const recipientAllowsSpeech = (await store.findTrust(recipientId, userId))?.trusted === true;

    if (created) {
      const sender = await store.findUserById(userId);
      const dto = messageDto(message, recipientAllowsSpeech);
      // Fast path: anyone with an open socket (sender's other devices + recipient).
      hub.broadcastToUsers([recipientId, userId], { type: "message.created", data: dto });

      // Slow path: cloud push for devices that are not connected.
      const devices = await store.devicesForUsers([recipientId]);
      if (devices.length > 0 && !hub.isOnline(recipientId)) {
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
    return { message: messageDto(message, recipientAllowsSpeech) };
  });

  /**
   * Speech consent, per peer: "you may whisper to this device".
   * Only the owner of the device being spoken to can set it.
   */
  app.patch(`${API}/conversations/:id/trust`, async (request) => {
    const { userId } = await authenticate(request);
    limiter.check(`trust:${userId}`, 60, 60_000);
    const params = request.params as { id: string };
    const body = asObject(request.body);
    const trusted = asOptionalBoolean(body.trusted, "trusted");
    if (trusted === undefined) throw new ValidationError("trusted must be true or false");

    const conversation = await requireConversation(params.id, userId);
    const peerId = conversation.memberIds.find((id) => id !== userId);
    if (!peerId) throw new ValidationError("Conversation has no peer");

    await store.setTrust(userId, peerId, trusted);
    hub.broadcastToUsers([peerId], {
      type: "peer.updated",
      data: { userId, allowsSpeak: trusted },
    });
    if (peerId) hub.broadcastToUsers([peerId], { type: "conversation.updated", data: { conversationId: conversation.id } });
    return { conversation: await conversationDto(conversation, userId) };
  });

  app.post(`${API}/messages/:id/read`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const params = request.params as { id: string };
    const message = await store.findMessage(params.id);
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
   * Purely informational for the sender ("whispered"), never a delivery gate.
   */
  app.post(`${API}/messages/:id/spoken`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const params = request.params as { id: string };
    const message = await store.findMessage(params.id);
    if (!message) throw new NotFoundError("Message not found");
    if (message.recipientId !== userId) throw new ForbiddenError("Only the recipient can report speech");

    const now = Date.now();
    await store.markMessageSpoken(message.id, now);
    const refreshed = await store.findMessage(message.id);
    if (refreshed) {
      hub.broadcastToUsers([message.senderId], { type: "message.updated", data: messageDto(refreshed, true) });
    }
    reply.status(204);
    return null;
  });

  // ------------------------------------------------------------------ devices

  app.post(`${API}/devices`, async (request, reply) => {
    const { userId } = await authenticate(request);
    const body = asObject(request.body);
    const deviceId = asString(body.deviceId, "deviceId", { min: 4, max: 128 });
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
      "peer.updated",
      "presence.updated",
      "typing",
      "auth.required",
      "auth.ok",
      "pong",
      "error",
    ],
    clientFrames: ["auth", "ping", "typing"],
    connectedSockets: hub.connectionCount(),
  }));

  // ------------------------------------------------------- browser test console

  // A tiny page so two browser tabs (or two phones) can be paired in seconds
  // without installing anything. It talks to exactly the same public API as the
  // Android app; it is a demo, not the product.
  const demoPage = loadDemoPage();
  const serveDemo = async (_request: FastifyRequest, reply: import("fastify").FastifyReply) => {
    if (!demoPage) {
      reply.type("text/plain").send("The demo console is not part of this build.");
      return;
    }
    reply.type("text/html; charset=utf-8").send(demoPage);
  };
  app.get("/", serveDemo);
  app.get("/demo", serveDemo);

  return app;
}

function loadDemoPage(): string | null {
  // Works from src/ (dev) and from dist/src/ (build): try both layouts.
  for (const relative of ["../public/index.html", "../../public/index.html"]) {
    try {
      return readFileSync(new URL(relative, import.meta.url), "utf8");
    } catch {
      // try the next candidate
    }
  }
  return null;
}

/** Exported for tests that need to assert on code handling. */
export const __testing = { normaliseCode, asUuid };
