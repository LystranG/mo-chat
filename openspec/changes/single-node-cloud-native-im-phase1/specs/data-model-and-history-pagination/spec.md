## ADDED Requirements

### Requirement: Relational persistence model coverage
PostgreSQL schema MUST include entities for users (including identity public key), user friendships (uid_1 < uid_2 pair, ok/blocked status, blocked_by), friend requests (with base64 `sign`), groups (with owner id), group memberships, group join requests (with base64 `sign`), and message records required by private/group chat flows.

All table primary keys MUST be Snowflake-generated BIGINT values.

#### Scenario: schema initialization
- **WHEN** persistence module initializes database schema
- **THEN** all required entity tables and key relations are present for chat domain operations

### Requirement: Baseline DDL SQL is produced
The project MUST produce a baseline DDL SQL file describing the phase-1 schema.

#### Scenario: DDL file exists
- **WHEN** a developer checks the repository documentation
- **THEN** a DDL SQL file is present and reflects the required tables and relations

### Requirement: Cursor-based history query
History query APIs MUST support cursor pagination using `msgId + limit` and MUST default to `limit=50` when omitted.

#### Scenario: default page size request
- **WHEN** history API request omits `limit`
- **THEN** API returns at most 50 records starting from provided cursor

### Requirement: Message validation before acceptance
The system MUST validate message structure and business constraints before publish, including sender authorization and payload format validity.

#### Scenario: invalid message payload
- **WHEN** message fails validation rules
- **THEN** system rejects message and does not publish to RocketMQ

### Requirement: Message body persisted as base64 protobuf
When persisting chat messages, the system MUST store the protobuf message body as base64 text in PostgreSQL so that history APIs can return the same payload over HTTP.

#### Scenario: message persisted
- **WHEN** persistence worker stores a chat message
- **THEN** the stored message body is base64 encoding of the protobuf body bytes
