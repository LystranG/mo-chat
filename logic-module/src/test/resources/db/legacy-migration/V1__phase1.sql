-- MoChat Phase 1 baseline schema (PostgreSQL)
-- Derived from docs/ddl/phase1.sql and extended with the agreed
-- conversation/message sequencing fields used by persistence.

CREATE TABLE IF NOT EXISTS users (
  id           BIGINT PRIMARY KEY,
  username     TEXT   NOT NULL UNIQUE,
  public_key   BYTEA  NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT users_public_key_len_chk CHECK (octet_length(public_key) = 32)
);

CREATE TABLE IF NOT EXISTS user_friendships (
  id           BIGINT PRIMARY KEY,
  uid_1        BIGINT NOT NULL,
  uid_2        BIGINT NOT NULL,
  status       TEXT   NOT NULL,
  blocked_by   SMALLINT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT user_friendships_uid_order_chk CHECK (uid_1 < uid_2),
  CONSTRAINT user_friendships_status_chk CHECK (status IN ('ok', 'blocked')),
  CONSTRAINT user_friendships_blocked_by_chk CHECK (
    (status = 'ok' AND blocked_by IS NULL) OR
    (status = 'blocked' AND blocked_by IN (0, 1))
  ),
  CONSTRAINT user_friendships_pair_uniq UNIQUE (uid_1, uid_2)
);

CREATE INDEX IF NOT EXISTS user_friendships_uid1_idx ON user_friendships (uid_1);
CREATE INDEX IF NOT EXISTS user_friendships_uid2_idx ON user_friendships (uid_2);
CREATE INDEX IF NOT EXISTS user_friendships_status_idx ON user_friendships (status);

CREATE TABLE IF NOT EXISTS friend_requests (
  id           BIGINT PRIMARY KEY,
  from_uid     BIGINT NOT NULL,
  to_uid       BIGINT NOT NULL,
  sign         TEXT   NOT NULL,
  status       TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  handled_at   TIMESTAMPTZ,
  CONSTRAINT friend_requests_status_chk CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS friend_requests_to_status_created_idx ON friend_requests (to_uid, status, created_at);
CREATE INDEX IF NOT EXISTS friend_requests_from_status_created_idx ON friend_requests (from_uid, status, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS friend_requests_pending_pair_uniq
  ON friend_requests (from_uid, to_uid)
  WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS groups (
  id           BIGINT PRIMARY KEY,
  owner_uid    BIGINT NOT NULL,
  name         TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS groups_owner_idx ON groups (owner_uid);

CREATE TABLE IF NOT EXISTS group_memberships (
  id           BIGINT PRIMARY KEY,
  group_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  role         TEXT   NOT NULL,
  status       TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT group_memberships_role_chk CHECK (role IN ('owner', 'member')),
  CONSTRAINT group_memberships_status_chk CHECK (status IN ('active', 'left', 'kicked')),
  CONSTRAINT group_memberships_pair_uniq UNIQUE (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS group_memberships_user_idx ON group_memberships (user_id, status);
CREATE INDEX IF NOT EXISTS group_memberships_group_idx ON group_memberships (group_id, status);

CREATE TABLE IF NOT EXISTS group_join_requests (
  id           BIGINT PRIMARY KEY,
  group_id     BIGINT NOT NULL,
  from_uid     BIGINT NOT NULL,
  sign         TEXT   NOT NULL,
  status       TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  handled_by   BIGINT,
  handled_at   TIMESTAMPTZ,
  CONSTRAINT group_join_requests_status_chk CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled'))
);

CREATE INDEX IF NOT EXISTS group_join_requests_group_status_created_idx ON group_join_requests (group_id, status, created_at);
CREATE INDEX IF NOT EXISTS group_join_requests_from_status_created_idx ON group_join_requests (from_uid, status, created_at);

CREATE TABLE IF NOT EXISTS conversations (
  id                   BIGINT PRIMARY KEY,
  type                 SMALLINT NOT NULL,
  latest_seq           BIGINT NOT NULL DEFAULT 0,
  latest_message_time  BIGINT NOT NULL DEFAULT 0,
  uid_1_seq            BIGINT NOT NULL DEFAULT 0,
  uid_2_seq            BIGINT NOT NULL DEFAULT 0,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT conversations_type_chk CHECK (type IN (0, 1)),
  CONSTRAINT conversations_latest_seq_chk CHECK (latest_seq >= 0),
  CONSTRAINT conversations_uid_1_seq_chk CHECK (uid_1_seq >= 0),
  CONSTRAINT conversations_uid_2_seq_chk CHECK (uid_2_seq >= 0)
);

CREATE TABLE IF NOT EXISTS messages (
  msg_id         BIGINT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  seq            BIGINT NOT NULL,
  client_msg_id  BIGINT NOT NULL,
  kind           TEXT   NOT NULL,
  sender_uid     BIGINT NOT NULL,
  peer_uid_low   BIGINT,
  peer_uid_high  BIGINT,
  group_id       BIGINT,
  server_ts_ms   BIGINT NOT NULL,
  payload_base64 TEXT   NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT messages_kind_chk CHECK (kind IN ('private', 'group')),
  CONSTRAINT messages_seq_chk CHECK (seq > 0),
  CONSTRAINT messages_routing_fields_chk CHECK (
    (kind = 'private'
      AND group_id IS NULL
      AND peer_uid_low IS NOT NULL
      AND peer_uid_high IS NOT NULL
    ) OR
    (kind = 'group'
      AND group_id IS NOT NULL
      AND peer_uid_low IS NULL
      AND peer_uid_high IS NULL
    )
  ),
  CONSTRAINT messages_conversation_fk FOREIGN KEY (conversation_id) REFERENCES conversations (id),
  CONSTRAINT messages_conversation_seq_uniq UNIQUE (conversation_id, seq)
);

CREATE UNIQUE INDEX IF NOT EXISTS messages_sender_client_msg_uniq ON messages (sender_uid, client_msg_id);
CREATE INDEX IF NOT EXISTS messages_conversation_seq_idx ON messages (conversation_id, seq DESC);
CREATE INDEX IF NOT EXISTS messages_private_pair_seq_idx ON messages (peer_uid_low, peer_uid_high, seq DESC) WHERE kind = 'private';
CREATE INDEX IF NOT EXISTS messages_group_seq_idx ON messages (group_id, seq DESC) WHERE kind = 'group';
