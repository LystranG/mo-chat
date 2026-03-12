## MODIFIED Requirements

### Requirement: RocketMQ sync-flush producer contract
`message-service` MUST publish chat messages to RocketMQ with synchronous flush mode and MUST not acknowledge sender success before broker confirmation. The RocketMQ record MUST be the handoff boundary between synchronous message acceptance and asynchronous durable persistence.

#### Scenario: broker persistence failure
- **WHEN** RocketMQ returns publish failure or timeout
- **THEN** sender success ACK is not emitted and the system returns `ERROR_RESPONSE` with `MQ_PUBLISH_FAILED (1500)`

### Requirement: Batch consume with transactional persistence
`persistence-service` MUST consume messages from MQ and MUST commit the PostgreSQL transaction before acknowledging queue consumption success. Conversation state advancement and cache updates triggered by the message MUST occur only after the transaction commits successfully.

#### Scenario: transaction commit succeeds
- **WHEN** `persistence-service` commits the message persistence transaction successfully
- **THEN** it acknowledges queue consumption success and performs post-commit state advancement and cache update work

#### Scenario: transaction commit fails
- **WHEN** the message persistence transaction fails or rolls back
- **THEN** `persistence-service` does not confirm queue consumption and the message remains retryable

### Requirement: Redis-backed idempotency window
`message-service` MUST own the Redis idempotency window for duplicate message acceptance using the key (`senderUid`, `clientMsgId`) within a TTL window of 5 minutes. A duplicate request within the window MUST return the original acceptance result and MUST not publish a duplicate MQ record.

#### Scenario: duplicate clientMsgId within window
- **WHEN** a sender submits a message with a `clientMsgId` already seen for that sender within the TTL window
- **THEN** `message-service` returns the original generated message metadata and does not publish a duplicate message

## ADDED Requirements

### Requirement: Persistence ownership is isolated from gateway delivery
`persistence-service` MUST persist message truth and conversation progress independently of whether online gateway delivery has already completed.

#### Scenario: realtime delivery precedes durable commit
- **WHEN** an online recipient receives a message through gateway delivery before the persistence transaction commits
- **THEN** `persistence-service` still performs the durable write and conversation advancement as the authoritative completion of persistence
