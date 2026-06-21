# MoChat Phase 1 Design (Validated)

This document expands the phase-1 requirements into a coding-ready design with explicit flows, concurrency invariants, and consistency rules.

Authoritative requirement sources:
- `docs/phase1-requirements.md`
- `openspec/changes/single-node-cloud-native-im-phase1/specs/*/spec.md`
- `docs/ddl/phase1.sql`

## 1. Goals and Success Criteria

Goals:
- High throughput message ingest and fanout on a single node.
- Thread-safe, deterministic per-conversation ordering via `seq`.
- Cache consistency: group cache is updated only after DB commit.
- Clear module boundaries and abstractions to minimize future refactors.

Non-goals:
- Client implementation.
- Multi-node clustering now (but interfaces are designed for it).

## 2. Architecture

### 2.1 Module split

The monolith is a modular system with three modules:

1) Connection module (Netty)
- Owns TCP lifecycle: TLS 1.3, framing, codec, heartbeat, per-channel rate limiting.
- Performs *syntactic* validation only (frame size, protobuf parseability).
- Routes inbound messages to the logic module via EventBus.
- Owns channel writes to clients.

2) Logic module (stateless)
- Owns *business* validation and decisions:
  - session validation
  - relationship/membership checks
  - idempotency
  - `seq`/`msgId` assignment
  - RocketMQ publish
  - offline queue
  - private receipt (second-stage ACK)
- Emits outbound events (SEND_ACK, ERROR_RESPONSE, message deliveries, DELIVERED_ACK) via EventBus.

3) Persistence module
- Owns RocketMQ batch consume.
- Writes messages and conversation state in a PostgreSQL transaction.
- Updates group cache only after transaction commit.

### 2.2 Core abstractions (cloud-native evolution points)

These MUST be interfaces with replaceable implementations:

- EventBus
  - Phase-1 default: Redis Pub/Sub
  - Also provide: in-process implementation for tests / degenerate single-node deployments

- UserChannelDirectory
  - Phase-1: in-memory mapping (userId -> channel)
  - Future: distributed directory / routing

- ConversationSeqGenerator
  - Phase-1 default: Redis atomic counter per conversation
  - Requirement: strict monotonic increase; gaps are allowed
  - On Redis key miss: initialize from DB `conversations.latest_seq`

- ConversationLock
  - Phase-1 default: JUC lock per conversation
  - Future: Redis distributed lock
  - Lock granularity: conversationId

- IdempotencyStore
  - Backed by Redis with TTL (default 5 minutes)
  - Key: (senderUid, clientMsgId)
  - Value: (msgId, seq, serverTime)

- OfflineQueueStore
  - Backed by Redis list per recipient user
  - Cap: 50 entries per user
  - Entry SHOULD be a small reference (conversationId, seq, msgId) to avoid duplicating payload

### 2.3 Threading model (throughput + safety)

- Netty event loop threads MUST NOT block on:
  - Redis calls
  - DB calls
  - RocketMQ client calls
  - cryptographic work

Implementation rule:
- Connection module decodes on event loop, then publishes inbound events asynchronously.
- Logic module runs on a dedicated worker pool.
- Persistence module runs on its own worker pool.

### 2.4 Cache and consistency strategy

Session cache:
- Redis is authoritative (sessionId -> userId).
- Caffeine is L2 for hot lookups.

Group message cache (last 500 messages per group):
- L1: Caffeine
- L2: Redis
- Update trigger: after DB transaction commit only.
- Representation (recommended): Redis ZSET keyed by group conversationId with score=seq and value=payload_base64.

Public key cache:
- Redis may cache userId -> 32-byte public key (no TTL).

## 3. Protocol

## 3. Protocol

### 3.1 Fixed header framing

Header (11 bytes) + body (N bytes):
- magic: 4 bytes
- version: 1 byte
- msgType: 1 byte
- serializer: 1 byte
- bodyLength: 4 bytes (big-endian)

Decoder configuration (Netty LengthFieldBasedFrameDecoder):
- maxFrameLength: default 64KB (configurable)
- lengthFieldOffset: 7
- lengthFieldLength: 4
- lengthAdjustment: 0
- initialBytesToStrip: 0

### 3.2 Enums

msgType (1 byte):
- 1 CLIENT_HEARTBEAT
- 2 SERVER_HEARTBEAT
- 3 PRIVATE_MESSAGE
- 4 GROUP_MESSAGE
- 5 SEND_ACK
- 6 ERROR_RESPONSE
- 7 CLIENT_RECEIVE_ACK
- 8 DELIVERED_ACK

serializer (1 byte):
- 1 PROTOBUF

### 3.3 Protobuf schema baseline

All IDs use `int64`.

This is a *design-time* schema sketch (the actual `.proto` can be generated from it during implementation):

```proto
syntax = "proto3";
package mochat.v1;

message Heartbeat {
  int64 serverTimeMs = 1;
}

message SendAck {
  int64 clientMsgId = 1;
  int64 msgId = 2;
  int64 seq = 3;
  int64 serverTimeMs = 4;
}

message ErrorResponse {
  int32 errorCode = 1;
  string message = 2;
}

message PrivateMessageReq {
  string sessionId = 1;
  int64 clientMsgId = 2;
  int64 conversationId = 3; // friendship id
  int64 toUid = 4;
  bytes nonce = 5;          // 12 bytes
  bytes ciphertext = 6;
}

message GroupMessageReq {
  string sessionId = 1;
  int64 clientMsgId = 2;
  int64 conversationId = 3; // group id
  int64 groupId = 4;
  string text = 5;
}

message ChatMessageDelivery {
  int64 msgId = 1;
  int64 seq = 2;
  int64 serverTimeMs = 3;
  int64 conversationId = 4;
  int64 fromUid = 5;

  oneof payload {
    PrivatePayload privatePayload = 10;
    GroupPayload groupPayload = 11;
  }
}

message PrivatePayload {
  int64 toUid = 1;
  bytes nonce = 2;
  bytes ciphertext = 3;
}

message GroupPayload {
  int64 groupId = 1;
  string text = 2;
}

message ClientReceiveAck {
  string sessionId = 1;
  int64 conversationId = 2;     // friendship id
  int64 latestReceivedSeq = 3;  // highest contiguous received seq
}

message DeliveredAck {
  int64 conversationId = 1;     // friendship id
  int64 toUid = 2;
  int64 latestReceivedSeq = 3;
  int64 serverTimeMs = 4;
}
```

Notes:
- `latestReceivedSeq` MUST be the highest *contiguous* seq received by the client for that conversation.
- The persisted message payload uses base64(protobuf body bytes) stored in `messages.payload_base64`.

### 3.4 Error codes

The error code set is in `docs/phase1-requirements.md` and `openspec/.../specs/message-protocol-and-delivery-guarantees/spec.md`.

## 4. Data Model

DDL is the source of truth: `docs/ddl/phase1.sql`.

Key points:
- `conversations.id`
  - private: friendship id
  - group: group id
- `conversations.latest_seq` and `conversations.latest_message_time` are updated by persistence (max(seq) wins).
- `conversations.uid_1_seq` / `conversations.uid_2_seq` are updated by logic on CLIENT_RECEIVE_ACK (monotonic max).
- `messages` are queried by `(conversation_id, seq)`.

## 5. Detailed Flows

This section is intentionally explicit so implementation can follow it without guesswork.

### 5.1 Startup

1) Load configuration (io_uring, TLS cert paths, Redis, RocketMQ, Postgres, maxFrameLength, heartbeat intervals, workerId).
2) Initialize Redis client and verify connectivity.
3) Initialize EventBus (phase-1 default: Redis Pub/Sub).
4) Initialize Netty server:
   - boss/worker event loops
   - prefer io_uring; fall back to default
   - TLS handler
   - framing decoder/encoder
   - protobuf decoder/encoder
   - rate limiter
   - heartbeat handler
5) Initialize RocketMQ producer (ordered publish by conversationId) and consumer (persistence module).
6) Initialize DB pool and migration runner (later: Flyway/Liquibase).

### 5.2 Login/Register (HTTP)

WHEN client calls login with username:
- IF user exists:
  - validate provided public key (if present) equals stored; otherwise reject
  - issue sessionId
- IF user does not exist:
  - require base64 X25519 public key
  - validate base64 decode and 32-byte length
  - persist user and public key
  - issue sessionId

Session handling:
- store sessionId -> userId in Redis (and Caffeine L2)

### 5.3 Heartbeat (TCP)

1) Connection module sends SERVER_HEARTBEAT every configured interval.
2) Client responds with CLIENT_HEARTBEAT.
3) If no response within timeout: close channel and remove from UserChannelDirectory.

### 5.4 Friend request -> friendship -> private conversation

1) Send friend request (HTTP)
   - store friend_requests(sign base64, pending)
2) Receiver accepts (HTTP)
   - create user_friendships row (ok)
   - create conversations row:
     - id = friendshipId
     - type = 0
     - latest_seq = 0
     - uid_1_seq = 0, uid_2_seq = 0
3) Block/unblock (HTTP)
   - update user_friendships.status and blocked_by

### 5.5 Group create -> group conversation

1) Create group (HTTP)
   - create groups row
   - create group_memberships row for owner
   - create conversations row:
     - id = groupId
     - type = 1
     - latest_seq = 0
2) Join request / owner handle (HTTP)
   - store group_join_requests(sign base64)
   - on accept: create group_memberships

### 5.6 Private message send (first-stage ACK)

Inbound:
1) Connection module receives frame (PRIVATE_MESSAGE), validates framing and protobuf parse.
2) Connection module publishes an InboundPrivateMessage event to EventBus.

Logic:
3) Logic module consumes event and validates:
   - sessionId valid
   - friendship exists and status ok
   - not blocked
   - nonce length == 12
4) Logic module enters ConversationLock(conversationId) to serialize ordering.
5) Idempotency check by (senderUid, clientMsgId):
   - if exists: emit SEND_ACK with stored (msgId, seq), return
6) Seq assignment:
   - nextSeq = ConversationSeqGenerator.next(conversationId)
   - if Redis counter missing: initialize from DB conversations.latest_seq
7) msgId and serverTime assignment (Snowflake + server clock).
8) Publish to RocketMQ as ordered message with shardingKey=conversationId.
9) On RocketMQ send success:
   - write idempotency mapping (senderUid, clientMsgId) -> (msgId, seq, serverTime)
   - emit SEND_ACK to sender
10) Attempt online delivery to receiver:
   - emit outbound ChatMessageDelivery to receiver
   - if receiver offline or write fails: push offline queue entry

### 5.7 Private second-stage receipt (CLIENT_RECEIVE_ACK -> DELIVERED_ACK)

1) Receiver client sends CLIENT_RECEIVE_ACK(sessionId, conversationId, latestReceivedSeq).
2) Connection module validates framing/protobuf and publishes receipt event.
3) Logic module validates session and that user is a conversation participant.
   - validate latestReceivedSeq <= server-known conversation seq (Redis counter or DB latest_seq)
4) Update conversations.uid_1_seq or uid_2_seq:
   - set to max(existing, latestReceivedSeq)
5) If sender is online:
   - emit DELIVERED_ACK to sender
6) If sender is offline:
   - do not enqueue a special receipt; sender uses HTTP query as fallback

### 5.8 Group message send

Same structure as private send, except:
- Validate membership.
- No second-stage receipts.
- Delivery fanout to all online members.

### 5.9 Persistence (MQ -> DB -> caches)

1) Persistence module batch consumes ordered messages.
2) In a DB transaction:
   - insert messages row (conversation_id, seq, msgId, senderUid, server_time, payload_base64)
   - update conversations.latest_seq/latest_message_time only if incoming seq is greater
3) Commit.
4) After commit:
   - for group: update Redis + Caffeine group cache (cap 500)
5) Only after commit: acknowledge MQ consumption.

### 5.10 History query (HTTP)

Input:
- conversationId
- cursorSeq (optional)
- limit (optional; default 50)

Output:
- list ordered by seq
- each item includes seq, msgId, server_time, payload_base64

### 5.12 Offline queue replay (login-triggered)

1) On successful login, logic module drains up to 50 offline queue entries for the user.
2) For each entry, attempt online delivery.
3) If delivery fails, keep remaining entries for later retry (or leave for HTTP history sync).

### 5.11 Conversation state queries (HTTP)

1) Peer latest received seq (private only)
- Determine whether requester is uid_1 or uid_2 via user_friendships(uid_1 < uid_2)
- Return the other side's uid_x_seq

2) Latest message time and latest persisted seq (private + group)
- Read conversations.latest_seq/latest_message_time

## 6. Design Validation Checklist (Blind Spots)

High throughput:
- Netty event loop never blocks on Redis/DB/MQ.
- Per-conversation locks prevent global contention.
- Ordered publish uses shardingKey=conversationId.

Thread safety:
- ConversationLock serializes seq assignment + publish.
- Seq generator is atomic (Redis INCR).
- Receipt seq updates are monotonic max.

Cache consistency:
- Group cache updated only after DB commit.
- Cache eviction bounded (500).

Event bus reliability:
- Redis Pub/Sub is at-most-once; DB + seq-based HTTP sync is the correctness backstop.

## 7. Open Risks (Accepted)

- RocketMQ async flush: ACK may be returned before durable disk persistence.
- Seq gaps: if seq is allocated but publish fails, seq can be skipped.
- Redis Pub/Sub drops: transient loss of real-time events; clients must rely on history sync.
