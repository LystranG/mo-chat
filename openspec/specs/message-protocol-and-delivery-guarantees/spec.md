## ADDED Requirements

### Requirement: Fixed header framing + protobuf body
All TCP chat traffic MUST be encoded as a fixed-length header followed by a variable-length protobuf body.

Header fields MUST be:
- magic (4 bytes)
- version (1 byte)
- msgType (1 byte)
- serializer (1 byte, currently protobuf only)
- bodyLength (4 bytes, big-endian)
- body (N bytes)

#### Scenario: oversized frame is rejected
- **WHEN** a received frame exceeds the configured maximum frame length (default 64KB)
- **THEN** the server rejects the frame and closes the channel or returns an error response

### Requirement: Message type and serializer enums
The server MUST interpret `msgType` and `serializer` as enums.

The phase-1 `msgType` numeric mapping MUST be:
- 1: CLIENT_HEARTBEAT
- 2: SERVER_HEARTBEAT
- 3: PRIVATE_MESSAGE
- 4: GROUP_MESSAGE
- 5: SEND_ACK
- 6: ERROR_RESPONSE

Supported `serializer` values MUST include protobuf, with numeric mapping:
- 1: PROTOBUF

#### Scenario: unknown msgType
- **WHEN** a frame is received with an unknown msgType
- **THEN** the server returns an error response or closes the channel

### Requirement: Error responses include error codes
When returning an error response, the server MUST use msgType=ERROR_RESPONSE and the protobuf body MUST include an error code that is stable and machine-readable.

The phase-1 error code set MUST include at least:
- 1000: SESSION_INVALID
- 1001: SESSION_EXPIRED
- 1100: RATE_LIMITED
- 1200: INVALID_FRAME
- 1201: INVALID_BODY
- 1202: UNSUPPORTED_VERSION
- 1203: UNSUPPORTED_SERIALIZER
- 1300: NOT_FRIEND
- 1301: FRIEND_BLOCKED
- 1400: NOT_IN_GROUP
- 1500: MQ_PUBLISH_FAILED
- 1501: INTERNAL_ERROR

#### Scenario: blocked user sends private message
- **WHEN** a user sends a private message but the friendship state is blocked
- **THEN** the server returns an error response with code FRIEND_BLOCKED

### Requirement: Protobuf body and UTF-8 validation
Message bodies MUST be protobuf-encoded. If a message type contains plaintext text fields, those fields MUST be validated as UTF-8.

#### Scenario: malformed plaintext encoding
- **WHEN** a received message includes a plaintext text field that is not valid UTF-8
- **THEN** the server rejects the message and returns validation failure

### Requirement: Protobuf schemas per msgType
Each msgType MUST map to a protobuf message schema.

At minimum, schemas MUST support:
- Heartbeat: client heartbeat and server heartbeat.
- Private message: includes `sessionId`, `clientMsgId`, `toUid`, `nonce` (bytes, 12 bytes), `ciphertext` (bytes).
- Group message: includes `sessionId`, `clientMsgId`, `groupId`, and plaintext text content.
- Send ACK: includes `clientMsgId`, `msgId`, and server timestamp.
- Error response: includes `errorCode` and an optional error message.

All Snowflake IDs MUST be represented as protobuf `int64`.

#### Scenario: required fields present for private message
- **WHEN** the server receives a PRIVATE_MESSAGE body
- **THEN** the body contains `sessionId`, `clientMsgId`, `toUid`, `nonce`, and `ciphertext` fields and passes validation

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

### Requirement: Delivery results are normalized for routing decisions
Internal gateway delivery attempts MUST return a bounded result set that distinguishes successful delivery from stale route, offline recipient, and channel write failure.

#### Scenario: stale route response triggers re-resolution
- **WHEN** a targeted gateway delivery attempt returns a stale-route result
- **THEN** `message-service` refreshes route resolution before deciding whether to retry delivery or enqueue offline replay
