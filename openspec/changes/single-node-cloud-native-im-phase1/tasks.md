## 1. Project Bootstrap and Module Boundaries

- [ ] 1.1 Initialize Micronaut application skeleton under `com.github.lystran.mochat` with Gradle Java 25 toolchain.
- [ ] 1.2 Add core dependencies (Netty, protobuf, PostgreSQL, Redis, Caffeine, RocketMQ, TLS/native-image plugins, Apache Commons utilities).
- [ ] 1.3 Create module structure for `message-module` and `persistence-module` with interface/event contracts.
- [ ] 1.4 Add environment configuration profiles for local runtime (Redis, PostgreSQL, RocketMQ, TLS cert paths).
- [ ] 1.5 Collect and archive Context7 references for Netty io_uring, Micronaut, RocketMQ, and GraalVM implementation baselines.
- [ ] 1.6 Produce baseline DDL SQL file and indexing plan (e.g., `docs/ddl/phase1.sql`).

## 2. Protocol Contracts and Transport Pipeline

- [ ] 2.1 Define protobuf schemas for: login/session, heartbeat, private/group messages, ACK, and error responses.
- [ ] 2.2 Implement fixed header framing (magic/version/msgType/serializer/bodyLen big-endian) with maxFrameLength default 64KB (configurable).
- [ ] 2.3 Generate protobuf Java classes and integrate codec handlers into Netty pipeline.
- [ ] 2.4 Implement Netty bootstrap with boss/worker Reactor model and `io_uring` preference with fallback to Netty system default.
- [ ] 2.5 Add TLS 1.3 handler setup using self-signed certificate profile and enforce TLS-only channels.
- [ ] 2.6 Add heartbeat scheduler and timeout-driven channel/session cleanup.
- [ ] 2.7 Implement per-channel token-bucket rate limiting handler with `HashedWheelTimer` refill scheduling.

## 3. User Login, Session, Identity Key, and History APIs

- [ ] 3.1 Implement passwordless login/register HTTP endpoint; require base64 X25519 public key for first-time user and reject mismatched keys for existing users.
- [ ] 3.2 Implement server-side public key validation (base64 decode, length 32 bytes) and persistence (immutable, no TTL).
- [ ] 3.3 Implement Redis-backed session issuance/validation with Caffeine L2 session cache.
- [ ] 3.4 Enforce session ID validation in chat TCP requests.
- [ ] 3.5 Implement cursor-based history HTTP APIs (private and group) with `msgId + limit` and default `limit=50`.

## 4. Social Graph and Group Management HTTP APIs

- [ ] 4.1 Implement friend request APIs (send, list sent, list received, handle accept/reject) including base64 `sign` passthrough.
- [ ] 4.2 Implement friend list API.
- [ ] 4.3 Implement delete friend, block friend, and unblock friend APIs; persist `blocked_by` semantics.
- [ ] 4.4 Implement group create API and group list API.
- [ ] 4.5 Implement leave group API.
- [ ] 4.6 Implement group join request APIs (send join request, owner lists requests, owner handles accept/reject) including base64 `sign` passthrough.
- [ ] 4.7 Implement group owner admin APIs (kick member, dissolve group).

## 5. Message Acceptance, Validation, IDs, and Delivery Guarantees

- [ ] 5.1 Implement message validator: schema/type checks, maxFrameLength enforcement, UTF-8 validation for group plaintext.
- [ ] 5.2 Implement private message validator: `nonce` exactly 12 bytes, `ciphertext` required.
- [ ] 5.3 Implement relationship validation before accept: private requires active friendship and not blocked; group requires active membership.
- [ ] 5.4 Implement Redis idempotency guard for (`senderUid`, `clientMsgId`) with 5-minute TTL and mapping to generated `msgId`.
- [ ] 5.5 Implement server Snowflake `msgId` generator and authoritative timestamp injection.
- [ ] 5.6 Implement sender ACK after RocketMQ sync flush; ACK includes `clientMsgId` + `msgId`.
- [ ] 5.7 Implement online recipient push without receiver ACK; if recipient is offline or channel write fails, enqueue offline message.
- [ ] 5.8 Implement offline queue fallback in Redis (cap 50 per user) and login-triggered offline replay.

## 6. Group Chat Fanout and L2 Message Cache

- [ ] 6.1 Implement group message routing flow and per-recipient online channel fanout.
- [ ] 6.2 Implement post-DB-commit group cache updater (Redis + Caffeine) and cap to 500 messages.
- [ ] 6.3 Implement cache tail-eviction behavior and cache warm/read path.

## 7. Persistence Module and Data Model

- [ ] 7.1 Implement PostgreSQL migrations for users, friendships, friend requests, groups, memberships, join requests, and messages.
- [ ] 7.2 Implement RocketMQ batch consumer in persistence module with transactional writes.
- [ ] 7.3 Implement queue confirmation only after transaction commit success; preserve retry on failure.
- [ ] 7.4 Persist message protobuf body bytes as base64 text (private includes nonce+ciphertext; group includes plaintext).
- [ ] 7.5 Implement efficient history query SQL and indexes: private by user-pair + msgId, group by groupId + msgId.

## 8. Reliability, Packaging, and Verification

- [ ] 8.1 Add integration tests for protocol framing, max frame length, and error responses.
- [ ] 8.2 Add integration tests for login/register key validation and session auth.
- [ ] 8.3 Add integration tests for friend request lifecycle, block/unblock, group join/admin flows.
- [ ] 8.4 Add integration tests for sender ACK mapping (clientMsgId->msgId), idempotency behavior, and offline queue.
- [ ] 8.5 Add integration tests for group cache update ordering (after DB commit) and 500-entry eviction.
- [ ] 8.6 Add GraalVM native image build profile and verify native binary build passes.
- [ ] 8.7 Add operational runbook docs for TLS cert bootstrap, dependency startup, and fallback transport mode.
