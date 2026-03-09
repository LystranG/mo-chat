## 1. Project Bootstrap and Module Boundaries

- [x] 1.1 Initialize Micronaut application skeleton under `com.github.lystran.mochat` with Gradle Java 25 toolchain.
- [x] 1.2 Add core dependencies (Netty, protobuf, PostgreSQL, Redis, Caffeine, RocketMQ, TLS/native-image plugins, Apache Commons utilities).
- [x] 1.3 Create module structure for `message-module` and `persistence-module` with interface/event contracts.
- [x] 1.4 Add environment configuration profiles for local runtime (Redis, PostgreSQL, RocketMQ, TLS cert paths).
- [x] 1.5 Collect and archive Context7 references for Netty io_uring, Micronaut, RocketMQ, and GraalVM implementation baselines.
- [x] 1.6 Produce baseline DDL SQL file and indexing plan (e.g., `docs/ddl/phase1.sql`).

## 2. Protocol Contracts and Transport Pipeline

- [x] 2.1 Define protobuf schemas for: login/session, heartbeat, private/group messages, ACK, and error responses.
- [x] 2.2 Implement fixed header framing (magic/version/msgType/serializer/bodyLen big-endian) with maxFrameLength default 64KB (configurable).
- [x] 2.3 Generate protobuf Java classes and integrate codec handlers into Netty pipeline.
- [x] 2.4 Implement Netty bootstrap with boss/worker Reactor model and `io_uring` preference with fallback to Netty system default.
- [x] 2.5 Add TLS 1.3 handler setup using self-signed certificate profile and enforce TLS-only channels.
- [x] 2.6 Add heartbeat scheduler and timeout-driven channel/session cleanup.
- [x] 2.7 Implement per-channel token-bucket rate limiting handler with `HashedWheelTimer` refill scheduling.

## 3. User Login, Session, Identity Key, and History APIs

- [x] 3.1 Implement passwordless login/register HTTP endpoint; require base64 X25519 public key for first-time user and reject mismatched keys for existing users.
- [x] 3.2 Implement server-side public key validation (base64 decode, length 32 bytes) and persistence (immutable, no TTL).
- [x] 3.3 Implement Redis-backed session issuance/validation with Caffeine L2 session cache.
- [x] 3.4 Enforce session ID validation in chat TCP requests.
- [x] 3.5 Implement `seq`-based history HTTP API on existing `GET /history`, including `cursorSeq` pagination and `startSeq/endSeq` range query, with unified max `limit=50`.

## 4. Social Graph and Group Management HTTP APIs

- [x] 4.1 Implement friend request APIs (send, list sent, list received, handle accept/reject) including base64 `sign` passthrough.
- [x] 4.2 Implement friend list API.
- [x] 4.3 Implement delete friend, block friend, and unblock friend APIs; persist `blocked_by` semantics.
- [x] 4.4 Implement group create API and group list API.
- [x] 4.5 Implement leave group API.
- [x] 4.6 Implement group join request APIs (send join request, owner lists requests, owner handles accept/reject) including base64 `sign` passthrough.
- [x] 4.7 Implement group owner admin APIs (kick member, dissolve group).

## 5. Message Acceptance, Validation, IDs, and Delivery Guarantees

- [x] 5.1 Implement message validator: schema/type checks, maxFrameLength enforcement, UTF-8 validation for group plaintext.
- [x] 5.2 Implement private message validator: `nonce` exactly 12 bytes, `ciphertext` required.
- [x] 5.3 Implement relationship validation before accept: private requires active friendship and not blocked; group requires active membership.
- [x] 5.4 Implement Redis idempotency guard for (`senderUid`, `clientMsgId`) with 5-minute TTL and mapping to generated `msgId`.
- [x] 5.5 Implement server Snowflake `msgId` generator and authoritative timestamp injection.
- [x] 5.6 Implement sender ACK after RocketMQ sync flush; ACK includes `clientMsgId` + `msgId`.
- [x] 5.7 Implement online recipient push without receiver ACK; if recipient is offline or channel write fails, enqueue offline message.
- [x] 5.8 Implement offline queue fallback in Redis (cap 50 per user) and login-triggered offline replay.

## 6. Group Chat Fanout and L2 Message Cache

- [x] 6.1 Implement group message routing flow and per-recipient online channel fanout.
- [x] 6.2 Implement post-DB-commit group cache updater (Redis + Caffeine) and cap to 500 messages.
- [x] 6.3 Implement cache tail-eviction behavior and cache warm/read path.

## 7. Persistence Module and Data Model

- [x] 7.1 Implement PostgreSQL migrations for users, friendships, friend requests, groups, memberships, join requests, and messages.
- [x] 7.2 Implement RocketMQ batch consumer in persistence module with transactional writes.
- [x] 7.3 Implement queue confirmation only after transaction commit success; preserve retry on failure.
- [x] 7.4 Persist message protobuf body bytes as base64 text (private includes nonce+ciphertext; group includes plaintext).
- [x] 7.5 Implement efficient `seq`-based history query SQL and indexes: conversation history by `conversation_id + seq`, plus private/group `seq` indexes for ordered retrieval.

## 8. Reliability, Packaging, and Verification

- [x] 8.1 Add integration tests for protocol framing, max frame length, and error responses.
- [x] 8.2 Add integration tests for login/register key validation and session auth.
- [x] 8.3 Add integration tests for friend request lifecycle, block/unblock, group join/admin flows.
- [x] 8.4 Add integration tests for sender ACK mapping (clientMsgId->msgId), idempotency behavior, and offline queue.
- [x] 8.5 Add integration tests for group cache update ordering (after DB commit) and 500-entry eviction.
- [x] 8.6 Verify real GraalVM native binary build passes in an environment with `native-image` available (fresh verification on 2026-03-09 used Oracle GraalVM `25.0.1`; `./gradlew :app:nativeCompile -g .gradle` actually generated `app/build/native/nativeCompile/mo-chat` and emitted native image completion logs including the output directory).
- [x] 8.7 Add operational runbook docs for TLS cert bootstrap, dependency startup, and fallback transport mode.
