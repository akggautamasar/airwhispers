-- AirWhispers schema (PostgreSQL 14+).
--
-- There are no user accounts: a row in `users` IS a device identity, addressed
-- by the short `code` its owner reads out loud.
--
-- Conventions
--   * ids are uuid
--   * timestamps are epoch milliseconds in bigint columns (`*_at`) so the API and
--     the Android client agree on one representation
--   * message text is stored as-is today; the column is isolated behind the store
--     interface so end-to-end encryption can replace it without touching the API

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
  id           uuid PRIMARY KEY,
  -- lowercase, unique, unambiguous alphabet (no 0/o/1/l/i). See CODE_ALPHABET.
  code         text NOT NULL UNIQUE,
  display_name text NOT NULL,
  created_at   bigint NOT NULL
);

-- Proof that a physical installation owns an identity, so it can mint access
-- tokens forever without a login screen. Secrets are stored only as scrypt
-- hashes. (device_id, user_id) is the key because a reinstalled app keeps its
-- generated device id but gets a fresh identity.
CREATE TABLE IF NOT EXISTS device_credentials (
  device_id    text NOT NULL,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  secret_hash  text NOT NULL,
  platform     text NOT NULL DEFAULT 'android',
  app_version  text,
  created_at   bigint NOT NULL,
  last_seen_at bigint NOT NULL,
  PRIMARY KEY (device_id, user_id)
);
CREATE INDEX IF NOT EXISTS device_credentials_user_idx ON device_credentials (user_id);

CREATE TABLE IF NOT EXISTS conversations (
  id         uuid PRIMARY KEY,
  -- normalised "a:b" key for 1:1 chats; NULL for future group conversations
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

-- Speech consent, owned by the listener: `owner` allows `peer` to trigger
-- speech on the owner's device. One-directional on purpose.
CREATE TABLE IF NOT EXISTS trusts (
  owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  peer_user_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  trusted       boolean NOT NULL DEFAULT false,
  created_at    bigint NOT NULL,
  updated_at    bigint NOT NULL,
  PRIMARY KEY (owner_user_id, peer_user_id)
);

-- Optional cloud push (FCM flavor). The standalone app never needs this table.
CREATE TABLE IF NOT EXISTS devices (
  device_id   text PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform    text NOT NULL DEFAULT 'android',
  push_token  text,
  app_version text,
  updated_at  bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_user_idx ON devices (user_id);
