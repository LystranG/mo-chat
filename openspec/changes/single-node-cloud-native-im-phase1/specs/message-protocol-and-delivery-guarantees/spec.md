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
Client requests that represent a user message MUST include a `clientMsgId`. The server MUST generate a Snowflake `msgId` and a server timestamp when accepting the message for downstream processing.

#### Scenario: accepted message metadata enrichment
- **WHEN** the server accepts a message with a valid `clientMsgId`
- **THEN** the server attaches `msgId` and server timestamp and forwards enriched message downstream

### Requirement: Sender ACK after RocketMQ sync persistence
The server MUST return send-success ACK to sender only after RocketMQ confirms synchronous flush persistence. The ACK MUST include both `clientMsgId` and the generated `msgId`.

#### Scenario: broker write success
- **WHEN** RocketMQ returns successful sync-flush result for the message
- **THEN** the server sends success ACK to the sender

### Requirement: Online delivery without receiver ACK
For online recipients, the server MUST attempt delivery over the active channel and MUST NOT require receiver ACK.

#### Scenario: recipient is online
- **WHEN** the recipient has an active channel session
- **THEN** the server delivers the message over that channel

### Requirement: Offline queue for offline recipients
If the recipient is offline, the server MUST enqueue the message to an offline queue for deferred delivery.

#### Scenario: recipient is offline
- **WHEN** the recipient does not have an active channel session
- **THEN** the server enqueues the message to offline queue
