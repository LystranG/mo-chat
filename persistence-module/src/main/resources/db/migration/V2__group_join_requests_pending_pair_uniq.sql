CREATE UNIQUE INDEX IF NOT EXISTS group_join_requests_pending_pair_uniq
  ON group_join_requests (group_id, from_uid)
  WHERE status = 'pending';
