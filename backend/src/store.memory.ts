import { randomUUID } from "node:crypto";
import {
  type Conversation,
  type Device,
  type DeviceCredential,
  type InsertMessageResult,
  type Message,
  type Store,
  type Trust,
  type User,
} from "./types.js";

/**
 * In-memory store: used for `npm run dev` (zero infrastructure), for the test
 * suite, and for small self-hosted demos. Data lives as long as the process.
 *
 * Single-instance only — a restart means every device is issued a new code
 * (production uses PostgreSQL, see store.pg.ts).
 */
export class MemoryStore implements Store {
  private users = new Map<string, User>();
  private usersByCode = new Map<string, string>();
  private credentials = new Map<string, DeviceCredential>();
  private conversations = new Map<string, Conversation>();
  private directIndex = new Map<string, string>();
  private messages = new Map<string, Message>();
  private trusts = new Map<string, Trust>();
  private devices = new Map<string, Device>();

  private directKey(a: string, b: string): string {
    return [a, b].sort().join("::");
  }

  private credentialKey(deviceId: string, userId: string): string {
    return `${deviceId}::${userId}`;
  }

  private trustKey(owner: string, peer: string): string {
    return `${owner}::${peer}`;
  }

  async createUser(input: { code: string; displayName: string }): Promise<User> {
    const code = input.code.toLowerCase();
    if (this.usersByCode.has(code)) throw new Error("code_taken");
    const user: User = {
      id: randomUUID(),
      code,
      displayName: input.displayName,
      createdAt: Date.now(),
    };
    this.users.set(user.id, user);
    this.usersByCode.set(code, user.id);
    return user;
  }

  async findUserByCode(code: string): Promise<User | null> {
    const id = this.usersByCode.get(code.toLowerCase());
    return id ? (this.users.get(id) ?? null) : null;
  }

  async findUserById(id: string): Promise<User | null> {
    return this.users.get(id) ?? null;
  }

  async updateUser(id: string, patch: { displayName?: string }): Promise<User> {
    const user = this.users.get(id);
    if (!user) throw new Error("user_not_found");
    if (patch.displayName !== undefined) user.displayName = patch.displayName;
    return user;
  }

  async countUsers(): Promise<number> {
    return this.users.size;
  }

  async saveCredential(credential: DeviceCredential): Promise<void> {
    this.credentials.set(this.credentialKey(credential.deviceId, credential.userId), { ...credential });
  }

  async credentialsForDevice(deviceId: string): Promise<DeviceCredential[]> {
    return [...this.credentials.values()].filter((c) => c.deviceId === deviceId);
  }

  async touchCredential(deviceId: string, userId: string, at: number): Promise<void> {
    const credential = this.credentials.get(this.credentialKey(deviceId, userId));
    if (credential) credential.lastSeenAt = at;
  }

  async revokeCredential(deviceId: string, userId: string): Promise<void> {
    this.credentials.delete(this.credentialKey(deviceId, userId));
  }

  async getOrCreateConversation(a: string, b: string): Promise<Conversation> {
    const key = this.directKey(a, b);
    const existingId = this.directIndex.get(key);
    if (existingId) {
      const existing = this.conversations.get(existingId);
      if (existing) return existing;
    }
    const conversation: Conversation = {
      id: randomUUID(),
      createdAt: Date.now(),
      memberIds: [a, b],
    };
    this.conversations.set(conversation.id, conversation);
    this.directIndex.set(key, conversation.id);
    return conversation;
  }

  async findConversation(id: string): Promise<Conversation | null> {
    return this.conversations.get(id) ?? null;
  }

  async listConversations(userId: string): Promise<Conversation[]> {
    return [...this.conversations.values()].filter((c) => c.memberIds.includes(userId));
  }

  async peerIdsOf(userId: string): Promise<string[]> {
    const peers = new Set<string>();
    for (const conversation of this.conversations.values()) {
      if (!conversation.memberIds.includes(userId)) continue;
      for (const member of conversation.memberIds) {
        if (member !== userId) peers.add(member);
      }
    }
    return [...peers];
  }

  async insertMessage(message: Message): Promise<InsertMessageResult> {
    const duplicate = [...this.messages.values()].find(
      (m) => m.senderId === message.senderId && m.clientMessageId === message.clientMessageId,
    );
    if (duplicate) return { message: duplicate, created: false };
    this.messages.set(message.id, message);
    return { message, created: true };
  }

  async findMessage(id: string): Promise<Message | null> {
    return this.messages.get(id) ?? null;
  }

  async listMessages(conversationId: string, limit: number): Promise<Message[]> {
    return [...this.messages.values()]
      .filter((m) => m.conversationId === conversationId)
      .sort((a, b) => a.createdAt - b.createdAt)
      .slice(-limit);
  }

  async lastMessages(conversationIds: string[]): Promise<Map<string, Message>> {
    const out = new Map<string, Message>();
    for (const id of conversationIds) {
      const candidates = [...this.messages.values()]
        .filter((m) => m.conversationId === id)
        .sort((a, b) => a.createdAt - b.createdAt);
      const last = candidates.at(-1);
      if (last) out.set(id, last);
    }
    return out;
  }

  async unreadCount(conversationId: string, userId: string): Promise<number> {
    return [...this.messages.values()].filter(
      (m) => m.conversationId === conversationId && m.recipientId === userId && m.readAt === null,
    ).length;
  }

  async markConversationRead(conversationId: string, userId: string, at: number): Promise<Message[]> {
    const updated: Message[] = [];
    for (const message of this.messages.values()) {
      if (message.conversationId === conversationId && message.recipientId === userId && message.readAt === null) {
        message.readAt = at;
        message.deliveredAt = message.deliveredAt ?? at;
        updated.push(message);
      }
    }
    return updated;
  }

  async markMessageDelivered(messageId: string, at: number): Promise<void> {
    const message = this.messages.get(messageId);
    if (message && message.deliveredAt === null) message.deliveredAt = at;
  }

  async markMessageSpoken(messageId: string, at: number): Promise<void> {
    const message = this.messages.get(messageId);
    if (message) {
      message.spokenAt = at;
      message.deliveredAt = message.deliveredAt ?? at;
    }
  }

  async setTrust(ownerUserId: string, peerUserId: string, trusted: boolean): Promise<Trust> {
    const key = this.trustKey(ownerUserId, peerUserId);
    const now = Date.now();
    const existing = this.trusts.get(key);
    const trust: Trust = existing
      ? { ...existing, trusted, updatedAt: now }
      : { ownerUserId, peerUserId, trusted, createdAt: now, updatedAt: now };
    this.trusts.set(key, trust);
    return trust;
  }

  async findTrust(ownerUserId: string, peerUserId: string): Promise<Trust | null> {
    return this.trusts.get(this.trustKey(ownerUserId, peerUserId)) ?? null;
  }

  async upsertDevice(device: Device): Promise<void> {
    this.devices.set(device.deviceId, device);
  }

  async deleteDevice(userId: string, deviceId: string): Promise<void> {
    const device = this.devices.get(deviceId);
    if (device && device.userId === userId) this.devices.delete(deviceId);
  }

  async devicesForUsers(userIds: string[]): Promise<Device[]> {
    return [...this.devices.values()].filter((d) => userIds.includes(d.userId) && d.pushToken !== null);
  }

  async close(): Promise<void> {
    this.users.clear();
    this.messages.clear();
    this.conversations.clear();
  }
}
