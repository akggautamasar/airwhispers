import { Pool, type PoolClient, type QueryResultRow } from "pg";
import { randomUUID } from "node:crypto";
import { isUuid } from "./ids.js";
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

  async createUser(input: { code: string; displayName: string }): Promise<User> {
    try {
      const rows = await this.q<UserRow>(
        `INSERT INTO users (id, code, display_name, created_at)
         VALUES ($1, $2, $3, $4) RETURNING *`,
        [randomUUID(), input.code.toLowerCase(), input.displayName, Date.now()],
      );
      return toUser(rows[0]!);
    } catch (error) {
      if (isUniqueViolation(error)) throw new Error("code_taken");
      throw error;
    }
  }

  async findUserByCode(code: string): Promise<User | null> {
    const rows = await this.q<UserRow>(`SELECT * FROM users WHERE code = $1`, [code.toLowerCase()]);
    return rows[0] ? toUser(rows[0]) : null;
  }

  async findUserById(id: string): Promise<User | null> {
    if (!isUuid(id)) return null;
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

  async countUsers(): Promise<number> {
    const rows = await this.q<{ count: string }>(`SELECT count(*)::text AS count FROM users`);
    return Number(rows[0]?.count ?? 0);
  }

  async saveCredential(credential: DeviceCredential): Promise<void> {
    await this.q(
      `INSERT INTO device_credentials
         (device_id, user_id, secret_hash, platform, app_version, created_at, last_seen_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (device_id, user_id) DO UPDATE SET
         secret_hash = EXCLUDED.secret_hash,
         platform = EXCLUDED.platform,
         app_version = EXCLUDED.app_version,
         last_seen_at = EXCLUDED.last_seen_at`,
      [
        credential.deviceId,
        credential.userId,
        credential.secretHash,
        credential.platform,
        credential.appVersion,
        credential.createdAt,
        credential.lastSeenAt,
      ],
    );
  }

  async credentialsForDevice(deviceId: string): Promise<DeviceCredential[]> {
    const rows = await this.q<CredentialRow>(`SELECT * FROM device_credentials WHERE device_id = $1`, [deviceId]);
    return rows.map(toCredential);
  }

  async touchCredential(deviceId: string, userId: string, at: number): Promise<void> {
    await this.q(
      `UPDATE device_credentials SET last_seen_at = $3 WHERE device_id = $1 AND user_id = $2`,
      [deviceId, userId, at],
    );
  }

  async revokeCredential(deviceId: string, userId: string): Promise<void> {
    await this.q(`DELETE FROM device_credentials WHERE device_id = $1 AND user_id = $2`, [deviceId, userId]);
  }

  async getOrCreateConversation(a: string, b: string): Promise<Conversation> {
    const [first, second] = [a, b].sort();
    const pairKey = `${first}:${second}`;

    const existing = await this.q<ConversationRow>(`SELECT * FROM conversations WHERE pair_key = $1`, [pairKey]);
    if (existing[0]) {
      return { id: existing[0].id, createdAt: Number(existing[0].created_at), memberIds: [first!, second!] };
    }

    return await this.tx(async (client) => {
      const inserted = await client.query<ConversationRow>(
        `INSERT INTO conversations (id, pair_key, created_at) VALUES ($1, $2, $3)
         ON CONFLICT (pair_key) DO UPDATE SET pair_key = EXCLUDED.pair_key
         RETURNING *`,
        [randomUUID(), pairKey, Date.now()],
      );
      const conversation = inserted.rows[0]!;
      for (const member of [first!, second!]) {
        await client.query(
          `INSERT INTO conversation_members (conversation_id, user_id) VALUES ($1, $2)
           ON CONFLICT DO NOTHING`,
          [conversation.id, member],
        );
      }
      return { id: conversation.id, createdAt: Number(conversation.created_at), memberIds: [first!, second!] };
    });
  }

  async findConversation(id: string): Promise<Conversation | null> {
    if (!isUuid(id)) return null;
    const rows = await this.q<ConversationRow>(`SELECT * FROM conversations WHERE id = $1`, [id]);
    if (!rows[0]) return null;
    const members = await this.q<{ user_id: string }>(
      `SELECT user_id FROM conversation_members WHERE conversation_id = $1`,
      [id],
    );
    return {
      id: rows[0].id,
      createdAt: Number(rows[0].created_at),
      memberIds: members.map((m) => m.user_id),
    };
  }

  async listConversations(userId: string): Promise<Conversation[]> {
    const rows = await this.q<ConversationRow>(
      `SELECT c.* FROM conversations c
       JOIN conversation_members m ON m.conversation_id = c.id
       WHERE m.user_id = $1`,
      [userId],
    );
    const out: Conversation[] = [];
    for (const row of rows) {
      const conversation = await this.findConversation(row.id);
      if (conversation) out.push(conversation);
    }
    return out;
  }

  async peerIdsOf(userId: string): Promise<string[]> {
    const rows = await this.q<{ user_id: string }>(
      `SELECT DISTINCT other.user_id
       FROM conversation_members mine
       JOIN conversation_members other ON other.conversation_id = mine.conversation_id
       WHERE mine.user_id = $1 AND other.user_id <> $1`,
      [userId],
    );
    return rows.map((row) => row.user_id);
  }

  async insertMessage(message: Message): Promise<InsertMessageResult> {
    const existing = await this.q<MessageRow>(
      `SELECT * FROM messages WHERE sender_id = $1 AND client_message_id = $2`,
      [message.senderId, message.clientMessageId],
    );
    if (existing[0]) return { message: toMessage(existing[0]), created: false };

    try {
      const rows = await this.q<MessageRow>(
        `INSERT INTO messages
           (id, client_message_id, conversation_id, sender_id, recipient_id, text, priority,
            created_at, delivered_at, read_at, spoken_at)
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
    } catch (error) {
      if (!isUniqueViolation(error)) throw error;
    }
    // Lost a race: the other writer's row wins.
    const rows = await this.q<MessageRow>(
      `SELECT * FROM messages WHERE sender_id = $1 AND client_message_id = $2`,
      [message.senderId, message.clientMessageId],
    );
    return { message: toMessage(rows[0]!), created: false };
  }

  async findMessage(id: string): Promise<Message | null> {
    if (!isUuid(id)) return null;
    const rows = await this.q<MessageRow>(`SELECT * FROM messages WHERE id = $1`, [id]);
    return rows[0] ? toMessage(rows[0]) : null;
  }

  async listMessages(conversationId: string, limit: number): Promise<Message[]> {
    if (!isUuid(conversationId)) return [];
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
      `SELECT count(*)::text AS count FROM messages
       WHERE conversation_id = $1 AND recipient_id = $2 AND read_at IS NULL`,
      [conversationId, userId],
    );
    return Number(rows[0]?.count ?? 0);
  }

  async markConversationRead(conversationId: string, userId: string, at: number): Promise<Message[]> {
    if (!isUuid(conversationId) || !isUuid(userId)) return [];
    const rows = await this.q<MessageRow>(
      `UPDATE messages
         SET read_at = $3, delivered_at = COALESCE(delivered_at, $3)
       WHERE conversation_id = $1 AND recipient_id = $2 AND read_at IS NULL
       RETURNING *`,
      [conversationId, userId, at],
    );
    return rows.map(toMessage);
  }

  async markMessageDelivered(messageId: string, at: number): Promise<void> {
    if (!isUuid(messageId)) return;
    await this.q(`UPDATE messages SET delivered_at = COALESCE(delivered_at, $2) WHERE id = $1`, [messageId, at]);
  }

  async markMessageSpoken(messageId: string, at: number): Promise<void> {
    if (!isUuid(messageId)) return;
    await this.q(`UPDATE messages SET spoken_at = $2, delivered_at = COALESCE(delivered_at, $2) WHERE id = $1`, [
      messageId,
      at,
    ]);
  }

  async setTrust(ownerUserId: string, peerUserId: string, trusted: boolean): Promise<Trust> {
    const now = Date.now();
    const rows = await this.q<TrustRow>(
      `INSERT INTO trusts (owner_user_id, peer_user_id, trusted, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $4)
       ON CONFLICT (owner_user_id, peer_user_id) DO UPDATE
         SET trusted = EXCLUDED.trusted, updated_at = EXCLUDED.updated_at
       RETURNING *`,
      [ownerUserId, peerUserId, trusted, now],
    );
    return toTrust(rows[0]!);
  }

  async findTrust(ownerUserId: string, peerUserId: string): Promise<Trust | null> {
    const rows = await this.q<TrustRow>(
      `SELECT * FROM trusts WHERE owner_user_id = $1 AND peer_user_id = $2`,
      [ownerUserId, peerUserId],
    );
    return rows[0] ? toTrust(rows[0]) : null;
  }

  async upsertDevice(device: Device): Promise<void> {
    await this.q(
      `INSERT INTO devices (device_id, user_id, platform, push_token, app_version, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6)
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
    return rows.map(toDevice);
  }

  async close(): Promise<void> {
    await this.pool.end();
  }
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: string }).code === "23505";
}

interface UserRow extends QueryResultRow {
  id: string;
  code: string;
  display_name: string;
  created_at: string | number;
}
interface CredentialRow extends QueryResultRow {
  device_id: string;
  user_id: string;
  secret_hash: string;
  platform: string;
  app_version: string | null;
  created_at: string | number;
  last_seen_at: string | number;
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
interface TrustRow extends QueryResultRow {
  owner_user_id: string;
  peer_user_id: string;
  trusted: boolean;
  created_at: string | number;
  updated_at: string | number;
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
    code: row.code,
    displayName: row.display_name,
    createdAt: Number(row.created_at),
  };
}

function toCredential(row: CredentialRow): DeviceCredential {
  return {
    deviceId: row.device_id,
    userId: row.user_id,
    secretHash: row.secret_hash,
    platform: row.platform,
    appVersion: row.app_version,
    createdAt: Number(row.created_at),
    lastSeenAt: Number(row.last_seen_at),
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

function toTrust(row: TrustRow): Trust {
  return {
    ownerUserId: row.owner_user_id,
    peerUserId: row.peer_user_id,
    trusted: row.trusted,
    createdAt: Number(row.created_at),
    updatedAt: Number(row.updated_at),
  };
}

function toDevice(row: DeviceRow): Device {
  return {
    deviceId: row.device_id,
    userId: row.user_id,
    platform: row.platform,
    pushToken: row.push_token,
    appVersion: row.app_version,
    updatedAt: Number(row.updated_at),
  };
}
