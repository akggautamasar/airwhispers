-- AirWhispers schema (PostgreSQL 14+).
--
-- Conventions
--   * ids are uuid
--   * timestamps are epoch milliseconds in bigint columns (`*_at`) so the API and
--     the Android client agree on one representation
--   * message text is stored as-is today; the column is isolated behind the store
--     interface so end-to-end encryption can replace it without touching the API

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
  id            uuid PRIMARY KEY,
  email         text NOT NULL UNIQUE,
  display_name  text NOT NULL,
  handle        text,
  password_hash text NOT NULL,
  created_at    bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash  text PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id   text,
  expires_at  bigint NOT NULL,
  revoked_at  bigint
);
CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE IF NOT EXISTS conversations (
  id         uuid PRIMARY KEY,
  -- normalised "a:b" key for 1:1 conversations; NULL for future group chats
  pair_key   text UNIQUE,
  created_at bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX IF NOT EXISTS conversation_members_user_idx ON conversation_members (user_id);

CREATE TABLE IF NOT EXISTS messages (
  id                uuid PRIMARY KEY,
  client_message_id text NOT NULL,
  conversation_id   uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sender_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  text              text NOT NULL,
  priority          text NOT NULL DEFAULT 'NORMAL',
  created_at        bigint NOT NULL,
  delivered_at      bigint,
  read_at           bigint,
  spoken_at         bigint,
  -- idempotency: a sender may not create the same client message twice
  UNIQUE (sender_id, client_message_id)
);
CREATE INDEX IF NOT EXISTS messages_conversation_idx ON messages (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS messages_unread_idx ON messages (recipient_id, read_at);

CREATE TABLE IF NOT EXISTS contacts (
  owner_user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  contact_user_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  is_trusted       boolean NOT NULL DEFAULT false,
  created_at       bigint NOT NULL,
  PRIMARY KEY (owner_user_id, contact_user_id)
);

CREATE TABLE IF NOT EXISTS user_settings (
  user_id                uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  speak_messages         boolean NOT NULL DEFAULT false,
  only_during_calls      boolean NOT NULL DEFAULT true,
  trusted_contacts_only  boolean NOT NULL DEFAULT true,
  prefer_bluetooth       boolean NOT NULL DEFAULT true,
  language_tag           text NOT NULL DEFAULT 'en-IN',
  speech_rate            real NOT NULL DEFAULT 1,
  pitch                  real NOT NULL DEFAULT 1,
  emoji_mode             text NOT NULL DEFAULT 'DESCRIBE_IMPORTANT'
);

CREATE TABLE IF NOT EXISTS devices (
  device_id   text PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform    text NOT NULL DEFAULT 'android',
  push_token  text,
  app_version text,
  updated_at  bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_user_idx ON devices (user_id);
