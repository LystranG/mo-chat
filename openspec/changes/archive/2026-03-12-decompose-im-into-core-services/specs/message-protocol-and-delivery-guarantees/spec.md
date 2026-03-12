## MODIFIED Requirements

### Requirement: Message IDs and timestamping
Client requests that represent a user message MUST include a `clientMsgId`. `message-service` MUST generate a Snowflake `msgId`, a per-conversation `seq`, and a server timestamp when accepting the message for downstream processing.

#### Scenario: accepted message metadata enrichment
- **WHEN** `message-service` accepts a message with a valid `clientMsgId`
- **THEN** it attaches a generated `msgId`, a generated per-conversation `seq`, and a server timestamp before publishing downstream

### Requirement: Sender ACK after RocketMQ sync persistence
`message-service` MUST return send-success ACK to the sender only after RocketMQ confirms synchronous publish acceptance. The ACK MUST include both `clientMsgId` and the generated `msgId`, and MUST NOT imply database commit completion or recipient delivery completion.

#### Scenario: broker write success emits sender ACK
- **WHEN** RocketMQ returns a successful synchronous publish result for the accepted message
- **THEN** `message-service` sends success ACK to the sender without waiting for `persistence-service` to finish database commit

### Requirement: Online delivery without receiver ACK
For online recipients, `message-service` MUST resolve the recipient's active gateway route and MUST attempt targeted delivery through the owning `access-gateway` instance without requiring receiver ACK before treating the send request as accepted.

#### Scenario: recipient is online on a remote gateway
- **WHEN** the recipient has an active route owned by a different `access-gateway` instance
- **THEN** `message-service` performs targeted delivery to that gateway instance without waiting for recipient ACK before the send request is considered accepted

### Requirement: Offline queue for offline recipients
If the recipient has no valid active route, or targeted delivery does not complete successfully after one route refresh, `message-service` MUST enqueue an offline delivery envelope for deferred replay.

#### Scenario: targeted delivery falls back to offline queue
- **WHEN** `message-service` cannot complete targeted delivery because the route is absent, stale after refresh, or write-failed
- **THEN** it enqueues an offline delivery envelope for deferred replay

## ADDED Requirements

### Requirement: Delivery results are normalized for routing decisions
Internal gateway delivery attempts MUST return a bounded result set that distinguishes successful delivery from stale route, offline recipient, and channel write failure.

#### Scenario: stale route response triggers re-resolution
- **WHEN** a targeted gateway delivery attempt returns a stale-route result
- **THEN** `message-service` refreshes route resolution before deciding whether to retry delivery or enqueue offline replay
