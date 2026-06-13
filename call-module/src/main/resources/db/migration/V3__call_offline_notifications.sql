CREATE TABLE IF NOT EXISTS call_offline_notifications (
  id              BIGINT PRIMARY KEY,
  user_id         BIGINT NOT NULL,
  type            TEXT   NOT NULL,
  call_id         TEXT   NOT NULL,
  room_name       TEXT   NOT NULL,
  group_id        BIGINT NOT NULL,
  from_user_id    BIGINT NOT NULL,
  payload_json    TEXT   NOT NULL,
  delivered_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS call_offline_notifications_user_pending_created_idx
  ON call_offline_notifications (user_id, created_at, id)
  WHERE delivered_at IS NULL;

CREATE INDEX IF NOT EXISTS call_offline_notifications_call_idx
  ON call_offline_notifications (call_id, room_name);
