import { Pool, type PoolClient, type QueryResultRow } from "pg";
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
 * PostgreSQL store (production path).
 *
 * Schema: see sql/schema.sql. Timestamps are epoch milliseconds stored as bigint
 * so the API and the Android client share one unambiguous representation.
 */
export class PgStore implements Store {
  constructor(private readonly pool: Pool) {}

  static fromConnectionString(url: string): PgStore {
    return new PgStore(new Pool({ connectionString: url, max: 10 }));
  }

  private async q<T extends QueryResultRow>(sql: string, params: unknown[] = []): Promise<T[]> {
    const result = await this.pool.query<T>(sql, params);
    return result.rows;
  }

  private async tx<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const value = await fn(client);
      await client.query("COMMIT");
      return value;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async createUser(input: {
    email: string;
    displayName: string;
    passwordHash: string;
    handle?: string | null;
  }): Promise<User> {
    const rows = await this.q<UserRow>(
      `INSERT INTO users (id, email, display_name, handle, password_hash, created_at)
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING *`,
      [randomUUID(), input.email.toLowerCase(), input.displayName, input.handle ?? null, input.passwordHash, Date.now()],
    );
    return toUser(rows[0]!);
  }

  async findUserByEmail(email: string): Promise<UserWithSecret | null> {
    const rows = await this.q<UserRow>(`SELECT * FROM users WHERE email = $1`, [email.toLowerCase()]);
    const row = rows[0];
    return row ? { ...toUser(row), passwordHash: row.password_hash } : null;
  }

  async findUserById(id: string): Promise<User | null> {
    const rows = await this.q<UserRow>(`SELECT * FROM users WHERE id = $1`, [id]);
    return rows[0] ? toUser(rows[0]) : null;
  }

  async updateUser(id: string, patch: { displayName?: string }): Promise<User> {
    const rows = await this.q<UserRow>(
      `UPDATE users SET display_name = COALESCE($2, display_name) WHERE id = $1 RETURNING *`,
      [id, patch.displayName ?? null],
    );
    if (!rows[0]) throw new Error("user_not_found");
    return toUser(rows[0]);
  }

  async saveRefreshToken(record: RefreshTokenRecord): Promise<void> {
    await this.q(
      `INSERT INTO refresh_tokens (token_hash, user_id, device_id, expires_at, revoked_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [record.tokenHash, record.userId, record.deviceId, record.expiresAt, record.revokedAt],
    );
  }

  async findRefreshToken(tokenHash: string): Promise<RefreshTokenRecord | null> {
    const rows = await this.q<RefreshRow>(`SELECT * FROM refresh_tokens WHERE token_hash = $1`, [tokenHash]);
    const row = rows[0];
    return row
      ? {
          tokenHash: row.token_hash,
          userId: row.user_id,
          deviceId: row.device_id,
          expiresAt: Number(row.expires_at),
          revokedAt: row.revoked_at === null ? null : Number(row.revoked_at),
        }
      : null;
  }

  async revokeRefreshToken(tokenHash: string, at: number): Promise<void> {
    await this.q(`UPDATE refresh_tokens SET revoked_at = $2 WHERE token_hash = $1 AND revoked_at IS NULL`, [tokenHash, at]);
  }

  async revokeAllRefreshTokens(userId: string): Promise<void> {
    await this.q(`UPDATE refresh_tokens SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL`, [userId, Date.now()]);
  }

  private pairKey(a: string, b: string): string {
    return [a, b].sort().join(":");
  }

  async getOrCreateConversation(a: string, b: string): Promise<Conversation> {
    const key = this.pairKey(a, b);
    const existing = await this.q<ConversationRow>(`SELECT * FROM conversations WHERE pair_key = $1`, [key]);
    if (existing[0]) return await this.hydrateConversation(existing[0]);
    try {
      const created = await this.tx(async (client) => {
        const row = await client.query<ConversationRow>(
          `INSERT INTO conversations (id, pair_key, created_at) VALUES ($1, $2, $3) RETURNING *`,
          [randomUUID(), key, Date.now()],
        );
        const conversationId = row.rows[0]!.id;
        await client.query(
          `INSERT INTO conversation_members (conversation_id, user_id) VALUES ($1, $2), ($1, $3)`,
          [conversationId, a, b],
        );
        return row.rows[0]!;
      });
      return await this.hydrateConversation(created);
    } catch {
      // Lost a race: another request created it first.
      const rows = await this.q<ConversationRow>(`SELECT * FROM conversations WHERE pair_key = $1`, [key]);
      return await this.hydrateConversation(rows[0]!);
    }
  }

  private async hydrateConversation(row: ConversationRow): Promise<Conversation> {
    const members = await this.q<{ user_id: string }>(
      `SELECT user_id FROM conversation_members WHERE conversation_id = $1`,
      [row.id],
    );
    return { id: row.id, createdAt: Number(row.created_at), memberIds: members.map((m) => m.user_id) };
  }

  async findConversation(id: string): Promise<Conversation | null> {
    const rows = await this.q<ConversationRow>(`SELECT * FROM conversations WHERE id = $1`, [id]);
    return rows[0] ? await this.hydrateConversation(rows[0]) : null;
  }

  async listConversations(userId: string): Promise<Conversation[]> {
    const rows = await this.q<ConversationRow>(
      `SELECT c.* FROM conversations c
       JOIN conversation_members m ON m.conversation_id = c.id
       WHERE m.user_id = $1`,
      [userId],
    );
    const out: Conversation[] = [];
    for (const row of rows) out.push(await this.hydrateConversation(row));
    return out;
  }

  async insertMessage(message: Message): Promise<InsertMessageResult> {
    const rows = await this.q<MessageRow>(
      `INSERT INTO messages (id, client_message_id, conversation_id, sender_id, recipient_id, text, priority, created_at, delivered_at, read_at, spoken_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (sender_id, client_message_id) DO NOTHING
       RETURNING *`,
      [
        message.id,
        message.clientMessageId,
        message.conversationId,
        message.senderId,
        message.recipientId,
        message.text,
        message.priority,
        message.createdAt,
        message.deliveredAt,
        message.readAt,
        message.spokenAt,
      ],
    );
    if (rows[0]) return { message: toMessage(rows[0]), created: true };
    const existing = await this.q<MessageRow>(
      `SELECT * FROM messages WHERE sender_id = $1 AND client_message_id = $2`,
      [message.senderId, message.clientMessageId],
    );
    return { message: toMessage(existing[0]!), created: false };
  }

  async findMessage(id: string): Promise<Message | null> {
    const rows = await this.q<MessageRow>(`SELECT * FROM messages WHERE id = $1`, [id]);
    return rows[0] ? toMessage(rows[0]) : null;
  }

  async listMessages(conversationId: string, limit: number): Promise<Message[]> {
    const rows = await this.q<MessageRow>(
      `SELECT * FROM (
         SELECT * FROM messages WHERE conversation_id = $1 ORDER BY created_at DESC LIMIT $2
       ) recent ORDER BY created_at ASC`,
      [conversationId, limit],
    );
    return rows.map(toMessage);
  }

  async lastMessages(conversationIds: string[]): Promise<Map<string, Message>> {
    const out = new Map<string, Message>();
    if (conversationIds.length === 0) return out;
    const rows = await this.q<MessageRow>(
      `SELECT DISTINCT ON (conversation_id) * FROM messages
       WHERE conversation_id = ANY($1::uuid[])
       ORDER BY conversation_id, created_at DESC`,
      [conversationIds],
    );
    for (const row of rows) out.set(row.conversation_id, toMessage(row));
    return out;
  }

  async unreadCount(conversationId: string, userId: string): Promise<number> {
    const rows = await this.q<{ count: string }>(
      `SELECT COUNT(*)::text AS count FROM messages
       WHERE conversation_id = $1 AND recipient_id = $2 AND read_at IS NULL`,
      [conversationId, userId],
    );
    return Number(rows[0]?.count ?? "0");
  }

  async markConversationRead(conversationId: string, userId: string, at: number): Promise<Message[]> {
    const rows = await this.q<MessageRow>(
      `UPDATE messages SET read_at = $3, delivered_at = COALESCE(delivered_at, $3)
       WHERE conversation_id = $1 AND recipient_id = $2 AND read_at IS NULL
       RETURNING *`,
      [conversationId, userId, at],
    );
    return rows.map(toMessage);
  }

  async markMessageDelivered(messageId: string, at: number): Promise<void> {
    await this.q(`UPDATE messages SET delivered_at = COALESCE(delivered_at, $2) WHERE id = $1`, [messageId, at]);
  }

  async markMessageSpoken(messageId: string, at: number): Promise<void> {
    await this.q(
      `UPDATE messages SET spoken_at = $2, delivered_at = COALESCE(delivered_at, $2) WHERE id = $1`,
      [messageId, at],
    );
  }

  async addContact(ownerUserId: string, contactUserId: string, isTrusted: boolean): Promise<Contact> {
    const rows = await this.q<ContactRow>(
      `INSERT INTO contacts (owner_user_id, contact_user_id, is_trusted, created_at)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (owner_user_id, contact_user_id)
       DO UPDATE SET is_trusted = EXCLUDED.is_trusted
       RETURNING *`,
      [ownerUserId, contactUserId, isTrusted, Date.now()],
    );
    return toContact(rows[0]!);
  }

  async listContacts(ownerUserId: string): Promise<Contact[]> {
    const rows = await this.q<ContactRow>(
      `SELECT * FROM contacts WHERE owner_user_id = $1 ORDER BY created_at ASC`,
      [ownerUserId],
    );
    return rows.map(toContact);
  }

  async findContact(ownerUserId: string, contactUserId: string): Promise<Contact | null> {
    const rows = await this.q<ContactRow>(
      `SELECT * FROM contacts WHERE owner_user_id = $1 AND contact_user_id = $2`,
      [ownerUserId, contactUserId],
    );
    return rows[0] ? toContact(rows[0]) : null;
  }

  async updateContact(
    ownerUserId: string,
    contactUserId: string,
    patch: { isTrusted?: boolean },
  ): Promise<Contact | null> {
    const rows = await this.q<ContactRow>(
      `UPDATE contacts SET is_trusted = COALESCE($3, is_trusted)
       WHERE owner_user_id = $1 AND contact_user_id = $2 RETURNING *`,
      [ownerUserId, contactUserId, patch.isTrusted ?? null],
    );
    return rows[0] ? toContact(rows[0]) : null;
  }

  async deleteContact(ownerUserId: string, contactUserId: string): Promise<void> {
    await this.q(`DELETE FROM contacts WHERE owner_user_id = $1 AND contact_user_id = $2`, [
      ownerUserId,
      contactUserId,
    ]);
  }

  async getSettings(userId: string): Promise<UserSettings> {
    const rows = await this.q<SettingsRow>(`SELECT * FROM user_settings WHERE user_id = $1`, [userId]);
    if (rows[0]) return toSettings(rows[0]);
    return await this.updateSettings(userId, {});
  }

  async updateSettings(userId: string, patch: Partial<Omit<UserSettings, "userId">>): Promise<UserSettings> {
    const current = (await this.q<SettingsRow>(`SELECT * FROM user_settings WHERE user_id = $1`, [userId]))[0];
    const base = current
      ? toSettings(current)
      : ({ userId, ...DEFAULT_SETTINGS } as UserSettings);
    const next: UserSettings = { ...base, ...patch, userId };
    await this.q(
      `INSERT INTO user_settings (user_id, speak_messages, only_during_calls, trusted_contacts_only, prefer_bluetooth, language_tag, speech_rate, pitch, emoji_mode)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
       ON CONFLICT (user_id) DO UPDATE SET
         speak_messages = EXCLUDED.speak_messages,
         only_during_calls = EXCLUDED.only_during_calls,
         trusted_contacts_only = EXCLUDED.trusted_contacts_only,
         prefer_bluetooth = EXCLUDED.prefer_bluetooth,
         language_tag = EXCLUDED.language_tag,
         speech_rate = EXCLUDED.speech_rate,
         pitch = EXCLUDED.pitch,
         emoji_mode = EXCLUDED.emoji_mode`,
      [
        next.userId,
        next.speakMessages,
        next.onlyDuringCalls,
        next.trustedContactsOnly,
        next.preferBluetooth,
        next.languageTag,
        next.speechRate,
        next.pitch,
        next.emojiMode,
      ],
    );
    return next;
  }

  async upsertDevice(device: Device): Promise<void> {
    await this.q(
      `INSERT INTO devices (device_id, user_id, platform, push_token, app_version, updated_at)
       VALUES ($1,$2,$3,$4,$5,$6)
       ON CONFLICT (device_id) DO UPDATE SET
         user_id = EXCLUDED.user_id,
         platform = EXCLUDED.platform,
         push_token = EXCLUDED.push_token,
         app_version = EXCLUDED.app_version,
         updated_at = EXCLUDED.updated_at`,
      [device.deviceId, device.userId, device.platform, device.pushToken, device.appVersion, device.updatedAt],
    );
  }

  async deleteDevice(userId: string, deviceId: string): Promise<void> {
    await this.q(`DELETE FROM devices WHERE device_id = $1 AND user_id = $2`, [deviceId, userId]);
  }

  async devicesForUsers(userIds: string[]): Promise<Device[]> {
    if (userIds.length === 0) return [];
    const rows = await this.q<DeviceRow>(
      `SELECT * FROM devices WHERE user_id = ANY($1::uuid[]) AND push_token IS NOT NULL`,
      [userIds],
    );
    return rows.map((row) => ({
      deviceId: row.device_id,
      userId: row.user_id,
      platform: row.platform,
      pushToken: row.push_token,
      appVersion: row.app_version,
      updatedAt: Number(row.updated_at),
    }));
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}

interface UserRow extends QueryResultRow {
  id: string;
  email: string;
  display_name: string;
  handle: string | null;
  password_hash: string;
  created_at: string | number;
}
interface RefreshRow extends QueryResultRow {
  token_hash: string;
  user_id: string;
  device_id: string | null;
  expires_at: string | number;
  revoked_at: string | number | null;
}
interface ConversationRow extends QueryResultRow {
  id: string;
  pair_key: string | null;
  created_at: string | number;
}
interface MessageRow extends QueryResultRow {
  id: string;
  client_message_id: string;
  conversation_id: string;
  sender_id: string;
  recipient_id: string;
  text: string;
  priority: string;
  created_at: string | number;
  delivered_at: string | number | null;
  read_at: string | number | null;
  spoken_at: string | number | null;
}
interface ContactRow extends QueryResultRow {
  owner_user_id: string;
  contact_user_id: string;
  is_trusted: boolean;
  created_at: string | number;
}
interface SettingsRow extends QueryResultRow {
  user_id: string;
  speak_messages: boolean;
  only_during_calls: boolean;
  trusted_contacts_only: boolean;
  prefer_bluetooth: boolean;
  language_tag: string;
  speech_rate: number;
  pitch: number;
  emoji_mode: string;
}
interface DeviceRow extends QueryResultRow {
  device_id: string;
  user_id: string;
  platform: string;
  push_token: string | null;
  app_version: string | null;
  updated_at: string | number;
}

function toUser(row: UserRow): User {
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name,
    handle: row.handle,
    createdAt: Number(row.created_at),
  };
}

function toMessage(row: MessageRow): Message {
  return {
    id: row.id,
    clientMessageId: row.client_message_id,
    conversationId: row.conversation_id,
    senderId: row.sender_id,
    recipientId: row.recipient_id,
    text: row.text,
    priority: row.priority === "SPEAK_NOW" ? "SPEAK_NOW" : "NORMAL",
    createdAt: Number(row.created_at),
    deliveredAt: row.delivered_at === null ? null : Number(row.delivered_at),
    readAt: row.read_at === null ? null : Number(row.read_at),
    spokenAt: row.spoken_at === null ? null : Number(row.spoken_at),
  };
}

function toContact(row: ContactRow): Contact {
  return {
    ownerUserId: row.owner_user_id,
    contactUserId: row.contact_user_id,
    isTrusted: row.is_trusted,
    createdAt: Number(row.created_at),
  };
}

function toSettings(row: SettingsRow): UserSettings {
  return {
    userId: row.user_id,
    speakMessages: row.speak_messages,
    onlyDuringCalls: row.only_during_calls,
    trustedContactsOnly: row.trusted_contacts_only,
    preferBluetooth: row.prefer_bluetooth,
    languageTag: row.language_tag,
    speechRate: Number(row.speech_rate),
    pitch: Number(row.pitch),
    emojiMode: (row.emoji_mode as UserSettings["emojiMode"]) ?? "DESCRIBE_IMPORTANT",
  };
}
