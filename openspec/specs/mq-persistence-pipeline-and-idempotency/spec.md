## ADDED Requirements

### Requirement: RocketMQ sync-flush producer contract
The message service MUST publish chat messages to RocketMQ with synchronous flush mode and MUST not acknowledge sender success before broker confirmation.

#### Scenario: broker persistence failure
- **WHEN** RocketMQ returns publish failure or timeout
- **THEN** sender success ACK is not emitted and server returns `ERROR_RESPONSE` with `MQ_PUBLISH_FAILED (1500)`

### Requirement: Batch consume with transactional persistence
Persistence workers MUST consume messages in batches and MUST commit PostgreSQL transaction before acknowledging queue consumption success.

#### Scenario: transaction commit succeeds
- **WHEN** batch persistence transaction commits successfully
- **THEN** worker sends queue consumption confirmation

#### Scenario: transaction commit fails
- **WHEN** transaction fails or rolls back
- **THEN** worker does not confirm queue consumption and message remains retryable

### Requirement: Redis-backed idempotency window
The server MUST prevent duplicate message acceptance for identical (`senderUid`, `clientMsgId`) within a Redis TTL window of 5 minutes.

#### Scenario: duplicate clientMsgId within window
- **WHEN** a sender submits a message with a `clientMsgId` already seen for that sender within TTL window
- **THEN** the server returns an idempotent duplicate response (including the original generated `msgId`) and does not publish a duplicate message
