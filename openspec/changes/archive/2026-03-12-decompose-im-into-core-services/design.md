## Context

The current codebase is already a modular monolith with meaningful boundaries across `connection-module`, `logic-module`, `message-module`, `persistence-module`, `infra-redis`, `common`, and `protocol`, but those boundaries are assembled into a single Micronaut runtime. That keeps the project easy to run, but it hides the distributed business problems this learning project now wants to surface: connection ownership, cross-pod routing, sender ACK semantics, offline fallback, and eventual persistence.

This change keeps the external TCP protocol, HTTP APIs, PostgreSQL, Redis, and MQ stack recognizable while changing the internal runtime topology into a small set of independently deployable services. Phase 1 intentionally stays conservative: shared infrastructure remains shared, deployment stays within one cluster, and the design optimizes for understandable distributed semantics rather than maximum elasticity or perfect isolation.

Primary stakeholders:
- Developers learning how IM transport, routing, and persistence change once the runtime is no longer single-process.
- Future implementation work that needs a stable contract for service ownership before code is split.

Key constraints:
- Keep the current IM feature scope largely intact while changing runtime topology.
- Use internal gRPC for service-to-service calls.
- Use Redis as the online routing registry and short-lived coordination store.
- Use MQ as the async boundary between message acceptance and durable persistence.
- Do not introduce KEDA, service mesh, multi-device presence, connection hot migration, or database-per-service in this phase.

## Goals / Non-Goals

**Goals:**
- Split the runtime into four services with clear ownership: `access-gateway`, `api-service`, `message-service`, and `persistence-service`.
- Make connection ownership and cross-pod targeted delivery explicit through a Redis-backed route registry.
- Preserve per-conversation message ordering, idempotent acceptance, sender ACK, and offline fallback across service boundaries.
- Separate synchronous message acceptance from asynchronous durable persistence with a dedicated persistence service.
- Keep deployment and debugging tractable by continuing to share PostgreSQL, Redis, and MQ in phase 1.
- Define a rollout model for gateway expansion and shrinkage that avoids live connection migration.

**Non-Goals:**
- Splitting PostgreSQL into per-service databases or adding distributed transactions.
- Supporting multiple concurrent devices or active channels per user.
- Introducing WebSocket alongside the current TCP protocol in this change.
- Adding KEDA, service mesh, or cluster-level auto-scaling policy work.
- Building strong consistency across gateway routing, Redis, MQ, and database state.
- Implementing member-level delivered/read state for group chat.

## Decisions

### 1. Use four deployable services with shared infrastructure in phase 1

Decision:
- `access-gateway` owns TCP connection lifecycle, session binding, online route registration, and downstream delivery to live channels.
- `api-service` owns login/session authority, social graph, group management, and history query APIs.
- `message-service` owns message ingest, idempotency, per-conversation ordering, sender ACK, online delivery orchestration, and offline fallback.
- `persistence-service` owns MQ consumption, transactional persistence, conversation state advancement, and post-commit cache updates.

Rationale:
- This is the smallest service split that makes distributed IM behavior explicit without prematurely fragmenting ordinary CRUD-style business logic.
- It preserves the existing module boundaries instead of inventing a new domain model.

Alternatives considered:
- Three services (`gateway`, combined `biz`, `persistence`): rejected because message orchestration would stay entangled with regular HTTP business flows.
- More than four services: rejected because it would increase operational and coordination cost before the core routing model is stable.

### 2. Keep PostgreSQL, Redis, and MQ shared, but assign service ownership for data and coordination

Decision:
- PostgreSQL, Redis, and MQ stay shared physical infrastructure in phase 1.
- Ownership becomes logical rather than physical: each service owns specific tables, keys, and queue responsibilities.
- Cross-service access is allowed only through service contracts or clearly designated shared coordination keys, not arbitrary direct reach-through.

Rationale:
- This keeps the project focused on distributed business semantics instead of early database partitioning, cross-database workflows, or duplicated read models.
- It reduces migration cost while still forcing explicit service boundaries.

Alternatives considered:
- Database-per-service: rejected because it would shift the learning focus to data synchronization and cross-database consistency too early.
- Keep the single-process runtime while only renaming modules: rejected because it would not surface routing, ownership, and eventual consistency as first-class concerns.

### 3. Route users by bind-time ownership, not static shard assignment

Decision:
- Clients connect to any available `access-gateway` pod.
- After successful bind, that pod becomes the user's current owner by writing a Redis route record such as `online:user:{uid}`.
- The route record includes `gatewayPod`, `podIp`, `grpcPort`, `connectionId`, `sessionId`, `sessionVersion`, `routeEpoch`, and lease expiry metadata.

Rationale:
- This avoids premature complexity around consistent hashing, rebalancing, and shard migration.
- It matches long-connection realities better: where a user is connected is more important than where a user “should” hash.

Alternatives considered:
- Static shard or consistent-hash gateway assignment: rejected because scale-out and scale-in would force rebalancing work and still would not remove the need for cross-pod delivery.
- Broadcast delivery to all gateways: rejected because it wastes resources and hides ownership bugs instead of modeling them clearly.

### 4. Enforce single active connection per user with fencing tokens

Decision:
- Phase 1 allows only one active bound connection per user.
- `sessionVersion` fences stale sessions.
- `routeEpoch` fences stale routes and replaced connections.
- A newer bind overwrites the Redis route record and asynchronously kicks the old gateway connection if it still holds the previous `routeEpoch`.

Rationale:
- Single active connection drastically reduces routing ambiguity, duplicate delivery, and offline replay complexity.
- Fencing provides a clear answer to split-brain scenarios caused by reconnect races or partial failures.

Alternatives considered:
- Multi-device presence from the start: rejected because fanout, ACK tracking, and route storage complexity would rise sharply.
- Rely on TTL alone to retire stale routes: rejected because it leaves too large a window where old connections may still appear valid.

### 5. Deliver cross-pod messages through targeted gateway gRPC, with offline fallback as the universal escape hatch

Decision:
- `message-service` reads the recipient's route from Redis.
- It calls the specific owning `access-gateway` pod through internal gRPC to deliver the message to the active channel.
- Delivery responses are normalized to a small set of outcomes such as `DELIVERED`, `USER_OFFLINE`, `ROUTE_STALE`, and `WRITE_FAILED`.
- On failure, `message-service` retries route lookup at most once and then falls back to the offline queue.

Rationale:
- This keeps the message delivery path explicit and debuggable.
- It avoids gateway-to-gateway forwarding chains and limits synchronous retry complexity.

Alternatives considered:
- Generic ClusterIP routing to any gateway instance: rejected because delivery must target the actual owning pod, not a random gateway replica.
- Gateway-side peer forwarding: rejected because it would spread routing intelligence across all gateways and make failure handling harder to reason about.

### 6. Separate sender acceptance from durable persistence using MQ as the async boundary

Decision:
- `message-service` performs validation, idempotency, `seq`/`msgId` allocation, and MQ publish.
- Sender success ACK is emitted only after MQ confirms acceptance.
- `persistence-service` consumes MQ messages, writes them transactionally to PostgreSQL, advances conversation state, and updates caches only after commit.

Rationale:
- This preserves the current high-throughput async write model while making the persistence boundary explicit.
- It gives a clean explanation for temporary divergence between real-time delivery and history query results.

Alternatives considered:
- Synchronous database writes in `message-service`: rejected because it would blur service ownership and remove the intended eventual consistency boundary.
- Treat sender ACK as “database commit complete”: rejected because it would unnecessarily slow the hot path and complicate retry semantics.

### 7. Define ACK semantics explicitly by layer

Decision:
- `SEND_ACK` means the message was accepted by `message-service`, assigned `msgId` and `seq`, and published successfully to MQ.
- Online delivery is a gateway/channel write outcome, not a durable business guarantee.
- Client-delivered ACK remains a separate business-level signal for private chat and does not imply that group fanout has symmetric per-recipient state.

Rationale:
- Layered ACK semantics prevent overloaded interpretations of “success”.
- The system remains explainable when delivery and persistence complete at different times.

Alternatives considered:
- One all-purpose success signal: rejected because it would collapse acceptance, delivery, and persistence into a misleading single state.
- Group member-level delivered semantics in phase 1: rejected because it adds a large combinatorial state space without helping the initial service split.

### 8. Store offline queue entries as delivery envelopes in phase 1

Decision:
- Offline queue entries carry enough delivery data to replay without immediately fetching from the database.
- Redis remains a short-term replay store rather than a durable source of truth.

Rationale:
- This avoids a failure window where MQ accept succeeded but database commit has not completed yet, making reference-only replay unsafe.
- It keeps offline replay semantics simpler while the system is still learning-oriented.

Alternatives considered:
- Store only `(conversationId, seq, msgId)` references: rejected for phase 1 because it introduces DB visibility race windows during replay.

### 9. Gateway expansion is passive; gateway shrink uses drain and reconnect, not live migration

Decision:
- Scale-out only affects new connections.
- Existing connections stay on their current gateway until disconnect or reconnect.
- Scale-in or rollout marks a gateway as draining, removes it from new traffic, allows a grace period, and then closes remaining connections so clients reconnect elsewhere.

Rationale:
- This is the most stable operational model for long-lived connections.
- It makes routing correctness more important than connection migration mechanics, which fits the project's learning goal.

Alternatives considered:
- Rebalancing active connections during scale-out: rejected because the complexity outweighs its value in phase 1.
- Live channel migration between gateways: rejected because it requires a much richer session transfer protocol and stronger coordination guarantees.

## Risks / Trade-offs

- [Redis route record becomes stale after crash] → Use lease expiry, heartbeat renewals, `routeEpoch` fencing, and offline fallback when targeted delivery fails.
- [Temporary divergence between real-time delivery and history queries] → Make MQ the explicit async boundary, keep UI/client logic tolerant of eventual history visibility, and persist final truth in PostgreSQL.
- [Single active connection per user limits realism] → Accept this as a deliberate phase-1 simplification and defer multi-device presence to a later change.
- [Shared infrastructure can invite boundary leakage] → Enforce service ownership in design, specs, and implementation reviews; treat direct cross-service data access as an exception, not the default.
- [Gateway drain can interrupt active users] → Use graceful drain windows and client reconnect behavior instead of immediate termination or hidden migration.
- [Envelope-based offline queue duplicates payload temporarily] → Accept short-lived Redis duplication to keep replay semantics reliable while persistence remains eventually consistent.

## Migration Plan

1. Split the architecture contract first: finalize specs for gateway routing, updated transport/session rules, message ownership, and persistence ownership before code changes.
2. Extract `access-gateway` as the first deployable service while preserving the current TCP protocol and Redis-backed session validation flow.
3. Introduce Redis route records, `sessionVersion`, `routeEpoch`, duplicate login replacement, and targeted gateway delivery APIs.
4. Extract `message-service` and move message acceptance, idempotency, `seq` allocation, MQ publish, and offline fallback out of the current combined runtime.
5. Extract `persistence-service` and move MQ consume, transactional database writes, conversation state updates, and post-commit cache work behind that service boundary.
6. Leave `api-service` owning login, session resolution, social graph, groups, and history query APIs, with internal gRPC contracts for other services.
7. Deploy incrementally with rollback at service boundaries: if a newly split service is unstable, traffic can be redirected back to the previous in-process path while preserving shared infrastructure and protocol compatibility.

Rollback strategy:
- Roll back one split boundary at a time rather than the entire stack.
- Preserve Redis/MQ/PostgreSQL schemas and keys so services can be downgraded without data migration.
- Keep external TCP and HTTP contracts stable during this change to allow topology rollback without client changes.

## Open Questions

- None block this change. The previously identified phase-1 ambiguities are intentionally resolved by design choices in this document: no KEDA, single active connection per user, envelope-based offline replay, history query remaining in `api-service`, and no multi-device presence.
- Future follow-up changes may revisit multi-device routing, offline queue compaction, read-side service split, or stronger infrastructure isolation, but those are explicitly deferred.
