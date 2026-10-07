import { randomUUID } from "node:crypto";
import {
  DEFAULT_SETTINGS,
  type Contact,
  type Conversation,
  type Device,
  type InsertMessageResult,
  type Message,
  type RefreshTokenRecord,
  type Store,
  type User,
  type UserSettings,
  type UserWithSecret,
} from "./types.js";

/**
 * In-memory store: used for `npm run dev` (zero infrastructure), for the test
 * suite, and for small self-hosted demos. Data lives as long as the process.
 */
export class MemoryStore implements Store {
  private users = new Map<string, UserWithSecret>();
  private usersByEmail = new Map<string, string>();
  private refreshTokens = new Map<string, RefreshTokenRecord>();
  private conversations = new Map<string, Conversation>();
  private directIndex = new Map<string, string>();
  private messages = new Map<string, Message>();
  private contacts = new Map<string, Contact>();
  private settings = new Map<string, UserSettings>();
  private devices = new Map<string, Device>();

  private directKey(a: string, b: string): string {
    return [a, b].sort().join("::");
  }

  async createUser(input: {
    email: string;
    displayName: string;
    passwordHash: string;
    handle?: string | null;
  }): Promise<User> {
    const user: UserWithSecret = {
      id: randomUUID(),
      email: input.email.toLowerCase(),
      displayName: input.displayName,
      handle: input.handle ?? null,
      createdAt: Date.now(),
      passwordHash: input.passwordHash,
    };
    this.users.set(user.id, user);
    this.usersByEmail.set(user.email, user.id);
    return strip(user);
  }

  async findUserByEmail(email: string): Promise<UserWithSecret | null> {
    const id = this.usersByEmail.get(email.toLowerCase());
    return id ? (this.users.get(id) ?? null) : null;
  }

  async findUserById(id: string): Promise<User | null> {
    const user = this.users.get(id);
    return user ? strip(user) : null;
  }

  async updateUser(id: string, patch: { displayName?: string }): Promise<User> {
    const user = this.users.get(id);
    if (!user) throw new Error("user_not_found");
    if (patch.displayName !== undefined) user.displayName = patch.displayName;
    return strip(user);
  }

  async saveRefreshToken(record: RefreshTokenRecord): Promise<void> {
    this.refreshTokens.set(record.tokenHash, record);
  }

  async findRefreshToken(tokenHash: string): Promise<RefreshTokenRecord | null> {
    return this.refreshTokens.get(tokenHash) ?? null;
  }

  async revokeRefreshToken(tokenHash: string, at: number): Promise<void> {
    const record = this.refreshTokens.get(tokenHash);
    if (record) record.revokedAt = at;
  }

  async revokeAllRefreshTokens(userId: string): Promise<void> {
    for (const record of this.refreshTokens.values()) {
      if (record.userId === userId && record.revokedAt === null) record.revokedAt = Date.now();
    }
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

  private contactKey(owner: string, contact: string): string {
    return `${owner}::${contact}`;
  }

  async addContact(ownerUserId: string, contactUserId: string, isTrusted: boolean): Promise<Contact> {
    const contact: Contact = { ownerUserId, contactUserId, isTrusted, createdAt: Date.now() };
    this.contacts.set(this.contactKey(ownerUserId, contactUserId), contact);
    return contact;
  }

  async listContacts(ownerUserId: string): Promise<Contact[]> {
    return [...this.contacts.values()]
      .filter((c) => c.ownerUserId === ownerUserId)
      .sort((a, b) => a.createdAt - b.createdAt);
  }

  async findContact(ownerUserId: string, contactUserId: string): Promise<Contact | null> {
    return this.contacts.get(this.contactKey(ownerUserId, contactUserId)) ?? null;
  }

  async updateContact(
    ownerUserId: string,
    contactUserId: string,
    patch: { isTrusted?: boolean },
  ): Promise<Contact | null> {
    const contact = this.contacts.get(this.contactKey(ownerUserId, contactUserId));
    if (!contact) return null;
    if (patch.isTrusted !== undefined) contact.isTrusted = patch.isTrusted;
    return contact;
  }

  async deleteContact(ownerUserId: string, contactUserId: string): Promise<void> {
    this.contacts.delete(this.contactKey(ownerUserId, contactUserId));
  }

  async getSettings(userId: string): Promise<UserSettings> {
    const existing = this.settings.get(userId);
    if (existing) return existing;
    const created: UserSettings = { userId, ...DEFAULT_SETTINGS };
    this.settings.set(userId, created);
    return created;
  }

  async updateSettings(userId: string, patch: Partial<Omit<UserSettings, "userId">>): Promise<UserSettings> {
    const current = await this.getSettings(userId);
    const next: UserSettings = { ...current, ...patch, userId };
    this.settings.set(userId, next);
    return next;
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

function strip(user: UserWithSecret): User {
  const { passwordHash: _passwordHash, ...rest } = user;
  return rest;
}
