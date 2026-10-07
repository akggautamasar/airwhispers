/**
 * Domain + storage contract.
 *
 * AirWhispers has **no accounts**. A device is an identity: the first time the
 * app talks to a server it is handed a short random code (for example `k7m2pq`)
 * that identifies it forever. That code is the only thing another person needs
 * in order to whisper to you.
 *
 * Two store implementations exist: an in-memory store (development, tests,
 * single-node demos) and a PostgreSQL store (production). Both satisfy this
 * interface so the HTTP/WebSocket layer never knows which one is underneath.
 */

export type MessagePriority = "NORMAL" | "SPEAK_NOW";

/** A device identity. `code` is what the human reads out to a friend. */
export interface User {
  id: string;
  /** Short, human-readable, unique. Always stored lowercase. */
  code: string;
  displayName: string;
  createdAt: number;
}

/**
 * A long-lived credential that proves "this device already owns this identity".
 *
 * The secret is generated on the server once, stored only as a salted hash, and
 * kept on the phone inside the Android Keystore. It exists so the app can get a
 * fresh access token without asking the user for anything.
 */
export interface DeviceCredential {
  deviceId: string;
  userId: string;
  secretHash: string;
  platform: string;
  appVersion: string | null;
  createdAt: number;
  lastSeenAt: number;
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

/**
 * "This owner lets that peer trigger speech on their device."
 *
 * Consent is one-directional and owned by the listener: nobody can whisper to a
 * phone whose user did not allow it.
 */
export interface Trust {
  ownerUserId: string;
  peerUserId: string;
  trusted: boolean;
  createdAt: number;
  updatedAt: number;
}

/** Push registration for the optional FCM flavor. */
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
  createUser(input: { code: string; displayName: string }): Promise<User>;
  findUserByCode(code: string): Promise<User | null>;
  findUserById(id: string): Promise<User | null>;
  updateUser(id: string, patch: { displayName?: string }): Promise<User>;
  countUsers(): Promise<number>;

  saveCredential(credential: DeviceCredential): Promise<void>;
  /** Every credential ever issued for a physical device id. */
  credentialsForDevice(deviceId: string): Promise<DeviceCredential[]>;
  touchCredential(deviceId: string, userId: string, at: number): Promise<void>;
  revokeCredential(deviceId: string, userId: string): Promise<void>;

  getOrCreateConversation(a: string, b: string): Promise<Conversation>;
  findConversation(id: string): Promise<Conversation | null>;
  listConversations(userId: string): Promise<Conversation[]>;
  /** Everyone who shares a conversation with this user (used for presence). */
  peerIdsOf(userId: string): Promise<string[]>;

  insertMessage(message: Message): Promise<InsertMessageResult>;
  findMessage(id: string): Promise<Message | null>;
  listMessages(conversationId: string, limit: number): Promise<Message[]>;
  lastMessages(conversationIds: string[]): Promise<Map<string, Message>>;
  unreadCount(conversationId: string, userId: string): Promise<number>;
  markConversationRead(conversationId: string, userId: string, at: number): Promise<Message[]>;
  markMessageDelivered(messageId: string, at: number): Promise<void>;
  markMessageSpoken(messageId: string, at: number): Promise<void>;

  setTrust(ownerUserId: string, peerUserId: string, trusted: boolean): Promise<Trust>;
  findTrust(ownerUserId: string, peerUserId: string): Promise<Trust | null>;

  upsertDevice(device: Device): Promise<void>;
  deleteDevice(userId: string, deviceId: string): Promise<void>;
  devicesForUsers(userIds: string[]): Promise<Device[]>;

  close(): Promise<void>;
}

/**
 * Every new identity starts *silent*: a fresh device never speaks a stranger's
 * message until its owner explicitly allows that person.
 */
export const CODE_PATTERN = /^[a-z0-9]{6}$/;

/** Alphabet without look-alike characters (0/o, 1/l/i) — meant to be read aloud. */
export const CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

export const CODE_LENGTH = 6;

export const MAX_DISPLAY_NAME = 40;
