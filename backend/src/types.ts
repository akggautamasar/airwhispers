/**
 * Domain + storage contract.
 *
 * Two implementations exist: an in-memory store (development, tests, single-node
 * demos) and a PostgreSQL store (production). Both must satisfy this interface so
 * the HTTP/WebSocket layer never knows which one is underneath.
 */

export type MessagePriority = "NORMAL" | "SPEAK_NOW";
export type EmojiMode = "IGNORE" | "DESCRIBE_IMPORTANT" | "READ_ALL";

export interface User {
  id: string;
  email: string;
  displayName: string;
  handle: string | null;
  createdAt: number;
}

export interface UserWithSecret extends User {
  passwordHash: string;
}

export interface RefreshTokenRecord {
  tokenHash: string;
  userId: string;
  deviceId: string | null;
  expiresAt: number;
  revokedAt: number | null;
}

export interface Conversation {
  id: string;
  createdAt: number;
  memberIds: string[];
}

export interface Message {
  id: string;
  clientMessageId: string;
  conversationId: string;
  senderId: string;
  recipientId: string;
  text: string;
  priority: MessagePriority;
  createdAt: number;
  deliveredAt: number | null;
  readAt: number | null;
  spokenAt: number | null;
}

export interface Contact {
  ownerUserId: string;
  contactUserId: string;
  isTrusted: boolean;
  createdAt: number;
}

export interface UserSettings {
  userId: string;
  speakMessages: boolean;
  onlyDuringCalls: boolean;
  trustedContactsOnly: boolean;
  preferBluetooth: boolean;
  languageTag: string;
  speechRate: number;
  pitch: number;
  emojiMode: EmojiMode;
}

export interface Device {
  deviceId: string;
  userId: string;
  platform: string;
  pushToken: string | null;
  appVersion: string | null;
  updatedAt: number;
}

export interface InsertMessageResult {
  message: Message;
  /** false when this was an idempotent replay of an existing clientMessageId. */
  created: boolean;
}

export interface Store {
  createUser(input: {
    email: string;
    displayName: string;
    passwordHash: string;
    handle?: string | null;
  }): Promise<User>;
  findUserByEmail(email: string): Promise<UserWithSecret | null>;
  findUserById(id: string): Promise<User | null>;
  updateUser(id: string, patch: { displayName?: string }): Promise<User>;

  saveRefreshToken(record: RefreshTokenRecord): Promise<void>;
  findRefreshToken(tokenHash: string): Promise<RefreshTokenRecord | null>;
  revokeRefreshToken(tokenHash: string, at: number): Promise<void>;
  revokeAllRefreshTokens(userId: string): Promise<void>;

  getOrCreateConversation(a: string, b: string): Promise<Conversation>;
  findConversation(id: string): Promise<Conversation | null>;
  listConversations(userId: string): Promise<Conversation[]>;

  insertMessage(message: Message): Promise<InsertMessageResult>;
  findMessage(id: string): Promise<Message | null>;
  listMessages(conversationId: string, limit: number): Promise<Message[]>;
  lastMessages(conversationIds: string[]): Promise<Map<string, Message>>;
  unreadCount(conversationId: string, userId: string): Promise<number>;
  markConversationRead(conversationId: string, userId: string, at: number): Promise<Message[]>;
  markMessageDelivered(messageId: string, at: number): Promise<void>;
  markMessageSpoken(messageId: string, at: number): Promise<void>;

  addContact(ownerUserId: string, contactUserId: string, isTrusted: boolean): Promise<Contact>;
  listContacts(ownerUserId: string): Promise<Contact[]>;
  findContact(ownerUserId: string, contactUserId: string): Promise<Contact | null>;
  updateContact(
    ownerUserId: string,
    contactUserId: string,
    patch: { isTrusted?: boolean },
  ): Promise<Contact | null>;
  deleteContact(ownerUserId: string, contactUserId: string): Promise<void>;

  getSettings(userId: string): Promise<UserSettings>;
  updateSettings(userId: string, patch: Partial<Omit<UserSettings, "userId">>): Promise<UserSettings>;

  upsertDevice(device: Device): Promise<void>;
  deleteDevice(userId: string, deviceId: string): Promise<void>;
  devicesForUsers(userIds: string[]): Promise<Device[]>;

  close(): Promise<void>;
}

export const DEFAULT_SETTINGS: Omit<UserSettings, "userId"> = {
  speakMessages: false,
  onlyDuringCalls: true,
  trustedContactsOnly: true,
  preferBluetooth: true,
  languageTag: "en-IN",
  speechRate: 1,
  pitch: 1,
  emojiMode: "DESCRIBE_IMPORTANT",
};
