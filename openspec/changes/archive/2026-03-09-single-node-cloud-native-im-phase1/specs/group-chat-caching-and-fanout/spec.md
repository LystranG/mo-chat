## ADDED Requirements

### Requirement: Group message cache update after durable success
Group message cache MUST be updated only after PostgreSQL transaction commit succeeds for the message.

#### Scenario: persistence confirmation received
- **WHEN** persistence worker commits the database transaction for a group message
- **THEN** the system appends that message to group cache

### Requirement: Two-level cache with bounded retention
Group message cache MUST use Caffeine + Redis two-level cache and MUST keep at most 500 recent messages per group.

#### Scenario: cache capacity exceeded
- **WHEN** adding a new group message would exceed 500 cached messages
- **THEN** the oldest tail message is evicted and cache size remains 500

### Requirement: Online-first fanout behavior
When recipient channel is online, the system MUST push group messages directly over active channel before relying on offline path.

#### Scenario: recipient online
- **WHEN** a group member has active channel session
- **THEN** the server sends message on active channel
