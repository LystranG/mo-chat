## Context

The repository is currently a minimal Gradle Java project with no existing domain implementation. This change introduces a high-throughput, single-node IM system as the first stage of a cloud-native-ready architecture.

Primary constraints and assumptions:
- Runtime and framework: Java 25 + Micronaut.
- Messaging transport: Netty with master/slave Reactor and Linux `io_uring` support.
- Data and infra dependencies: PostgreSQL, Redis, RocketMQ.
- Security requirements: TLS 1.3 for transport, E2EE for private text messages, server-generated timestamp, globally unique message ID.
- Operational target: monolith now, but module boundaries and abstractions must support future microservice split.

## Goals / Non-Goals

**Goals:**
- Deliver complete first-phase backend requirements and architecture for login, private chat, group chat, delivery guarantee, and persistence.
- Define protocol, sender ACK, heartbeat, idempotency, cache, and offline delivery behavior as testable requirements.
- Separate system into messaging and persistence modules with event-driven integration to minimize coupling.
- Ensure implementation readiness for GraalVM native image and containerized deployment.

**Non-Goals:**
- Building any client application.
- Multi-node clustering and cross-region consistency in this phase.
- Full production operations stack (e.g., full observability, multi-tenant billing).

## Decisions

### 1) Modular monolith with explicit ports
Decision:
- Use a modular monolith with two top-level modules: `message-module` and `persistence-module`.
- Expose cross-module interactions through interfaces/events only (e.g., `MessageAcceptedEvent`, `PersistenceAckEvent`).

Rationale:
- Preserves single-node simplicity now while keeping replacement points for future microservice extraction.

Alternatives considered:
- Immediate microservice split: rejected due to higher operational overhead and slower first delivery.
- Pure layered package split without module boundaries: rejected due to weaker evolution path.

### 2) Transport architecture: Netty master/slave Reactor + io_uring
Decision:
- Configure Netty with separated boss/worker groups and Linux native `io_uring` transport where available.
- Keep fallback capability to standard NIO when native transport is unavailable.

Rationale:
- Aligns with throughput target while retaining environment compatibility.

Alternatives considered:
- Java virtual-thread socket stack: rejected for phase-1 because Netty feature ecosystem (handlers, pipeline, heartbeat, backpressure) is central to requirements.

### 3) Protocol and reliability model
Decision:
- Use a fixed-header framing protocol with protobuf as canonical body encoding.
- Client provides `clientMsgId` for idempotency and ACK correlation; server generates Snowflake `msgId` as the global identifier.
- Snowflake generator MUST take `workerId` from configuration; phase-1 default is 1.
- Server adds authoritative timestamp before publishing downstream.
- Producer ACK (sender -> server -> RocketMQ success -> sender ACK) is the only required ACK in phase-1.
- Recipient delivery is best-effort over online channel; offline recipients are handled by offline queue.

Rationale:
- Separates acceptance guarantee (broker persistence) from best-effort delivery to online channels.

Alternatives considered:
- At-most-once delivery: rejected due to user-level reliability requirements.

### 4) Security model
Decision:
- Enforce TLS 1.3 for all TCP links with self-signed cert in phase-1.
- Private chat text uses E2EE (X25519 + AES-GCM, 12-byte nonce); identity public keys are registered at user bootstrap and are immutable.
- Private chat transport uses protobuf `bytes` for cryptographic fields (public keys, nonce, ciphertext); server stores ciphertext + nonce only.
- Group messages are plaintext at server side by requirement.

Rationale:
- Meets transport and private-message confidentiality requirements while keeping group flow simpler.

Alternatives considered:
- Server-managed key escrow for private chat: rejected because it weakens E2EE guarantees.

### 5) Data and messaging pipeline
Decision:
- Sender path: validate -> idempotency check -> publish to RocketMQ (sync flush) -> ACK sender.
- Persistence path: batch consume from RocketMQ -> transactional write to PostgreSQL -> queue confirm.
- Message persistence stores protobuf body bytes as base64 text for HTTP history retrieval.
- Group cache update occurs only after PostgreSQL transaction commit success.

Rationale:
- Prevents cache from diverging from durable state and aligns with eventual consistency expectations.

Alternatives considered:
- Write DB first then publish MQ: rejected because it complicates async fanout and retry semantics for chat throughput.

### 6) Session, cache, and offline storage strategy
Decision:
- Login and history APIs are HTTP in Micronaut; session IDs persisted in Redis and mirrored in Caffeine as L2 cache.
- Group history cache: Redis + Caffeine capped at 500 items, tail eviction.
- Offline queue: Redis list capped at 50 messages per recipient.
- Idempotency key (`senderUid`, `clientMsgId`) stored in Redis with 5-minute TTL.

Rationale:
- Keeps hot-path latency low while bounding memory and stale data growth.

Alternatives considered:
- Cache-only in-memory: rejected due to process restart data loss.

### 7) History query model
Decision:
- Use cursor pagination by `msgId + limit`, default 50 records.

Rationale:
- Stable for large message volumes and avoids deep offset scans.

Alternatives considered:
- Offset pagination: rejected due to poor scalability.

### 8) Native runtime readiness
Decision:
- Keep reflection-sensitive APIs isolated and register required classes for GraalVM native-image compatibility.

Rationale:
- Reduces migration risk when packaging as cloud-native pod later.

Alternatives considered:
- Ignore native compatibility now: rejected due to explicit requirement.

## Risks / Trade-offs

- [RocketMQ + DB transactional boundary complexity] -> Mitigation: define explicit state machine (accepted, persisted, delivered) and idempotent consumers.
- [E2EE has no forward secrecy with static identity keys] -> Mitigation: accept phase-1 trade-off; keep algorithm suite and key distribution replaceable behind interfaces.
- [io_uring environment differences] -> Mitigation: provide startup capability check and NIO fallback profile.
- [No receiver ACK means no end-to-end delivery confirmation] -> Mitigation: accept phase-1 trade-off; rely on persistence + client-side history sync for eventual consistency.
- [Native-image reflection gaps] -> Mitigation: maintain native test profile and reflection config checklist in CI.

## Migration Plan

1. Bootstrap module structure and dependency management (Micronaut, Netty, protobuf, Redis/PostgreSQL/RocketMQ clients).
2. Introduce protobuf contracts and transport handlers with TLS + heartbeat.
3. Implement HTTP login/session/history APIs and session cache.
4. Implement sender flow (validation, idempotency, MQ publish, sender ACK).
5. Implement receiver flow (online push without receiver ACK, offline queue, and login-triggered replay).
6. Implement persistence worker (batch consume, PostgreSQL transaction, queue confirm) and post-persist cache update.
7. Implement user bootstrap key registration validation and private-message E2EE payload handling.
8. Run integrated verification (protocol, reliability, pagination, idempotency, cache eviction, heartbeat, native image build).
9. Prepare deployment profile and rollback checklist (disable io_uring, fall back to NIO profile if needed).

## Open Questions

- None.
