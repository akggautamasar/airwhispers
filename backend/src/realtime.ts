import { WebSocketServer, WebSocket } from "ws";
import { randomUUID } from "node:crypto";
import type { Server } from "node:http";
import type { AccessTokenClaims } from "./crypto.js";

export interface RealtimeEvent {
  type: string;
  data?: unknown;
  eventId?: string;
  sentAt?: string;
}

interface Session {
  userId: string | null;
  deviceId: string | null;
  authed: boolean;
  alive: boolean;
}

export interface RealtimeDeps {
  path: string;
  verifyToken: (token: string) => AccessTokenClaims | null;
  /**
   * Who should hear that this user came online or went offline (their chat
   * partners). Optional: without it the hub simply skips presence fan-out.
   */
  presenceAudience?: (userId: string) => Promise<string[]>;
  /** Members of a conversation, used to forward ephemeral typing frames. */
  conversationMembers?: (conversationId: string) => Promise<string[]>;
  onAuthenticated?: (userId: string, deviceId: string | null) => void;
  log: (level: "info" | "warn", message: string, meta?: Record<string, unknown>) => void;
}

const AUTH_TIMEOUT_MS = 10_000;
const HEARTBEAT_MS = 45_000;
const MAX_PAYLOAD_BYTES = 64 * 1024;
/** Typing frames are fire-and-forget; don't let one client flood the server. */
const TYPING_MIN_INTERVAL_MS = 1_500;

/**
 * WebSocket hub — the "realtime" half of the product.
 *
 * Protocol (deliberately tiny and stable):
 *   client → { "type": "auth", "data": { "accessToken": "…", "deviceId": "…" } }
 *   server → { "type": "auth.ok" }                     (only after this are events pushed)
 *   client → { "type": "ping" }        server → { "type": "pong" }
 *   client → { "type": "typing", "data": { "conversationId": "…" } }
 *                                      server → { "type": "typing", … } to the other member
 *   server → { "type": "message.created" | "message.updated" | "message.read"
 *            | "presence.updated" | "peer.updated" | "error", "data": { … } }
 *
 * The socket is a *fast path*, never the source of truth: anything missed here is
 * still available from the REST history endpoints.
 */
export class RealtimeHub {
  private readonly wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD_BYTES });
  private readonly sessions = new WeakMap<WebSocket, Session>();
  private readonly byUser = new Map<string, Set<WebSocket>>();
  private readonly lastTypingAt = new WeakMap<WebSocket, number>();
  private heartbeat?: NodeJS.Timeout;

  constructor(private readonly deps: RealtimeDeps) {
    this.wss.on("connection", (socket) => this.onConnection(socket));
    this.heartbeat = setInterval(() => this.pingAll(), HEARTBEAT_MS);
    this.heartbeat.unref?.();
  }

  attach(server: Server): void {
    server.on("upgrade", (request, socket, head) => {
      const url = new URL(request.url ?? "/", "http://localhost");
      if (url.pathname !== this.deps.path) {
        socket.destroy();
        return;
      }
      this.wss.handleUpgrade(request, socket as never, head, (ws) => {
        this.wss.emit("connection", ws, request);
      });
    });
  }

  /** True while a user has at least one authenticated socket. */
  isOnline(userId: string): boolean {
    return (this.byUser.get(userId)?.size ?? 0) > 0;
  }

  private onConnection(socket: WebSocket): void {
    const session: Session = { userId: null, deviceId: null, authed: false, alive: true };
    this.sessions.set(socket, session);

    const authTimer = setTimeout(() => {
      if (!session.authed) {
        this.send(socket, { type: "error", data: { code: "auth_timeout" } });
        socket.close(4001, "auth required");
      }
    }, AUTH_TIMEOUT_MS);
    authTimer.unref?.();

    socket.on("message", (raw) => {
      let frame: { type?: string; data?: Record<string, unknown> };
      try {
        frame = JSON.parse(raw.toString()) as typeof frame;
      } catch {
        this.send(socket, { type: "error", data: { code: "malformed_frame" } });
        return;
      }
      switch (frame.type) {
        case "auth": {
          const token = typeof frame.data?.accessToken === "string" ? frame.data.accessToken : "";
          const claims = this.deps.verifyToken(token);
          if (!claims) {
            this.send(socket, { type: "auth.required", data: { reason: "invalid_token" } });
            socket.close(4003, "invalid token");
            return;
          }
          session.userId = claims.sub;
          session.deviceId = typeof frame.data?.deviceId === "string" ? frame.data.deviceId : null;
          session.authed = true;
          clearTimeout(authTimer);
          const wasEmpty = (this.byUser.get(claims.sub)?.size ?? 0) === 0;
          this.register(claims.sub, socket);
          this.send(socket, { type: "auth.ok", data: { userId: claims.sub } });
          this.deps.onAuthenticated?.(claims.sub, session.deviceId);
          this.deps.log("info", "realtime.authenticated", { userId: claims.sub });
          if (wasEmpty) void this.announcePresence(claims.sub, true);
          return;
        }
        case "typing": {
          if (!session.authed || !session.userId) return;
          const conversationId = typeof frame.data?.conversationId === "string" ? frame.data.conversationId : "";
          if (!conversationId) return;
          const now = Date.now();
          const previous = this.lastTypingAt.get(socket) ?? 0;
          if (now - previous < TYPING_MIN_INTERVAL_MS) return;
          this.lastTypingAt.set(socket, now);
          void this.forwardTyping(conversationId, session.userId);
          return;
        }
        case "ping":
          this.send(socket, { type: "pong" });
          return;
        case "pong":
          session.alive = true;
          return;
        default:
          this.send(socket, { type: "error", data: { code: "unknown_type" } });
      }
    });

    socket.on("pong", () => {
      session.alive = true;
    });

    socket.on("close", () => {
      clearTimeout(authTimer);
      const current = this.sessions.get(socket);
      if (!current?.userId) return;
      const sockets = this.byUser.get(current.userId);
      sockets?.delete(socket);
      if (!sockets || sockets.size === 0) {
        this.byUser.delete(current.userId);
        void this.announcePresence(current.userId, false);
      }
    });

    socket.on("error", () => {
      socket.terminate();
    });
  }

  private async announcePresence(userId: string, online: boolean): Promise<void> {
    if (!this.deps.presenceAudience) return;
    try {
      const audience = await this.deps.presenceAudience(userId);
      this.broadcastToUsers(audience.filter((id) => id !== userId), {
        type: "presence.updated",
        data: { userId, online },
      });
    } catch (error) {
      this.deps.log("warn", "presence.failed", { error: String(error) });
    }
  }

  private async forwardTyping(conversationId: string, fromUserId: string): Promise<void> {
    if (!this.deps.conversationMembers) return;
    try {
      const members = await this.deps.conversationMembers(conversationId);
      if (!members.includes(fromUserId)) return;
      this.broadcastToUsers(members.filter((id) => id !== fromUserId), {
        type: "typing",
        data: { conversationId, userId: fromUserId },
      });
    } catch (error) {
      this.deps.log("warn", "typing.forward_failed", { error: String(error) });
    }
  }

  private register(userId: string, socket: WebSocket): void {
    const set = this.byUser.get(userId) ?? new Set<WebSocket>();
    set.add(socket);
    this.byUser.set(userId, set);
  }

  send(socket: WebSocket, event: RealtimeEvent): void {
    if (socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({ ...event, eventId: event.eventId ?? randomUUID(), sentAt: new Date().toISOString() }));
  }

  /** Fan-out to every device of a user (all of their open sockets). */
  broadcastToUsers(userIds: string[], event: RealtimeEvent): number {
    let sent = 0;
    for (const userId of userIds) {
      const sockets = this.byUser.get(userId);
      if (!sockets) continue;
      for (const socket of sockets) {
        const session = this.sessions.get(socket);
        if (!session?.authed) continue;
        this.send(socket, event);
        sent++;
      }
    }
    return sent;
  }

  connectionCount(): number {
    let total = 0;
    for (const sockets of this.byUser.values()) total += sockets.size;
    return total;
  }

  private pingAll(): void {
    for (const sockets of this.byUser.values()) {
      for (const socket of sockets) {
        const session = this.sessions.get(socket);
        if (session && !session.alive) {
          socket.terminate();
          continue;
        }
        if (session) session.alive = false;
        try {
          socket.ping();
        } catch {
          socket.terminate();
        }
      }
    }
  }

  close(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    for (const sockets of this.byUser.values()) {
      for (const socket of sockets) socket.close(1001, "server shutting down");
    }
    this.wss.close();
  }
}
