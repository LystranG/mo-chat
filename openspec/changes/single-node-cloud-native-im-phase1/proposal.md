## Why

The project needs a production-oriented, single-node IM foundation that can sustain high throughput while keeping architecture boundaries ready for later microservice decomposition. This phase defines the minimum complete capability set (transport, reliability, persistence, security, and operations) to start implementation with low rework risk.

## What Changes

- Build a Micronaut + Java 25 server with Netty master/slave Reactor and Linux `io_uring` AIO support for IM TCP traffic.
- Define a fixed-header TCP framing protocol + protobuf body, with message type enums, max frame length limits, and ACK/error semantics.
- Define client/server message IDs: client-provided `clientMsgId` for idempotency and server-generated Snowflake `msgId` for global identity and cursor pagination.
- Add TLS 1.3 (self-signed certificate) for client-server transport encryption.
- Implement login/session + history query HTTP APIs (no password, passwordless bootstrap), and use session ID for chat authentication.
- Require X25519 identity public key upload at user bootstrap (base64 in HTTP), validate and persist it (immutable, no TTL).
- Add private chat E2EE (X25519 + AES-GCM, 12-byte nonce) and ciphertext-at-rest for private messages.
- Add group chat flow with Caffeine + Redis L2 cache (top 500 recent messages, tail eviction).
- Add RocketMQ async message pipeline with sync flush, ACK-on-success, persistence worker batch consume, transaction commit then queue confirm.
- Add PostgreSQL persistence model for users, friendships (with block/unblock), friend requests, groups (with owner), memberships, join requests, and messages; produce baseline DDL SQL.
- Add Redis-backed idempotency, offline queue, and public-key cache (no receiver ACK / retry in phase-1).
- Enforce heartbeat, channel lifecycle cleanup, and per-channel token-bucket rate limiting handler.
- Implement social graph and group management HTTP APIs (friend requests, blocks, group admin flows) and enforce relationship validation before message acceptance.
- Split code into messaging module and persistence module with event-driven decoupling and extension interfaces for cloud-native evolution.

## Capabilities

### New Capabilities
- `transport-and-connection-lifecycle`: Netty pipeline, io_uring reactor model, TLS, heartbeat, channel/session lifecycle.
- `login-session-and-user-bootstrap`: HTTP login/history APIs, passwordless bootstrap, X25519 public key registration, session issuance and validation.
- `message-protocol-and-delivery-guarantees`: fixed-header framing + protobuf body, clientMsgId/msgId rules, timestamping, sender ACK, online fast path, offline queue.
- `private-chat-e2ee-and-key-management`: E2EE private messaging (X25519 + AES-GCM, nonce), immutable identity public keys, ciphertext persistence.
- `group-chat-caching-and-fanout`: group messaging, Redis + Caffeine message cache update strategy, capped retention/eviction behavior.
- `mq-persistence-pipeline-and-idempotency`: RocketMQ sync-flush produce/consume workflow, transactional persistence confirmation, Redis idempotency window.
- `data-model-and-history-pagination`: PostgreSQL schema for user/friend/group relations and cursor-based history (`msgId + limit`).
- `social-graph-and-group-management`: friend requests, blocks, group creation/join flows, group owner/admin operations, and related HTTP APIs.
- `modular-architecture-and-native-runtime`: messaging/persistence module boundaries, extension interfaces, GraalVM native image readiness.

### Modified Capabilities
- None (no existing spec capability under `openspec/specs/` yet).

## Impact

- Affected code: full server bootstrap, Netty transport/pipeline handlers, HTTP controllers, MQ producer/consumer, persistence workers, cache/session/idempotency layers.
- Affected dependencies: Micronaut, Netty native transport (`io_uring`), protobuf runtime/compiler, PostgreSQL driver, Redis client, Caffeine, RocketMQ client, TLS/cert tooling, GraalVM Native Build Tools.
- Affected systems: TCP chat channel, HTTP API gateway surface, Redis/PostgreSQL/RocketMQ integration, observability and error handling paths.
- Operations impact: requires local environment profile (Docker Compose recommended) for Redis/PostgreSQL/RocketMQ and certificate generation workflow.
