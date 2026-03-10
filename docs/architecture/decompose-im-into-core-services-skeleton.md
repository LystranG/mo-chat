# decompose-im-into-core-services skeleton

This document records the Task 1 service skeleton required by OpenSpec change `decompose-im-into-core-services`.

## current module inventory

### app

- Current role: legacy monolith bootstrap.
- Current assembly hotspots: `Application`, `MochatRuntimeFactory`, `ConnectionRuntimeLifecycle`, `PersistenceRuntimeLifecycle`.
- Current responsibilities still concentrated here before later extraction: PostgreSQL / Redis / RocketMQ client bootstrap, Netty TLS + server creation, outbound subscriber startup, Flyway bootstrap.
- Target ownership after decomposition: stop being the source of truth for runtime assembly; keep only compatibility until later tasks remove the monolith assumption.

### connection-module

- Current role: TCP ingress and channel pipeline implementation.
- Current responsibilities: frame decode/encode, session bind interception, inbound routing to the event bus, outbound write-back, heartbeat handling, rate limit handling.
- Target service owner: `access-gateway`.

### logic-module

- Current role: mixed API/session authority plus message orchestration.
- Current responsibilities: HTTP controllers, session issuance/resolution, friend/group/history business, message ingest, receipt handling, inbound event consumption, RocketMQ producer adapter.
- Target service owners: `api-service` owns HTTP/session/social/history; `message-service` owns ingest/receipt/inbound consumer/producer-facing orchestration.

### message-module

- Current role: shared message-domain contracts.
- Current responsibilities: acceptance and persistence contract events that can cross service boundaries.
- Target service owner: logically centered on `message-service`, while contract types remain shared for service interaction.

### persistence-module

- Current role: persistence pipeline and transactional RocketMQ consume flow.
- Current responsibilities: MQ consume, message/conversation repositories, cache update helpers, Flyway SQL migrations.
- Target service owner: `persistence-service`.

## bean ownership

### access-gateway

- Owns TCP connection ingress, session bind gate, outbound write-back, heartbeat lifecycle, and online route registration orchestration.
- Current source modules: `connection-module` for transport handlers; future runtime composition will live in `access-gateway-app`.
- Owned beans or runtime responsibilities to migrate out of monolith assembly: `NettyChatServer`, `OutboundEventSubscriber`, `ConnectionRuntimeLifecycle`, `UserChannelDirectory<Channel>`, TLS context creation, bind-time session gateway adapters.

### api-service

- Owns HTTP login/session authority, social graph, groups, and history query APIs.
- Current source modules: `logic-module` HTTP controllers, `SessionService`, `FriendsService`, `GroupsService`, `HistoryService`, repository adapters.
- Owned beans or runtime responsibilities: `AuthController`, `FriendsController`, `GroupsController`, `ConversationController`, `HistoryController`, `SessionService`, user/friend/group/history repositories.

### message-service

- Owns message ingest, idempotency, ordered MQ acceptance, sender ACK semantics, and online/offline delivery orchestration.
- Current source modules: `logic-module` message ingest and receipt flow plus `message-module` contracts.
- Owned beans or runtime responsibilities: `MessageIngestService`, `ReceiptService`, `InboundMessageConsumer`, `RocketMqProducer`, idempotency store, conversation sequence generator, message command adapters.

### persistence-service

- Owns MQ consumption, transactional persistence, conversation advancement, and post-commit cache updates.
- Current source modules: `persistence-module`.
- Owned beans or runtime responsibilities: `MqConsumer`, `RocketMqPersistenceConsumer`, `PersistenceRuntimeLifecycle`, `ConversationRepository`, `MessageRepository`, `GroupMessageCache`.

## shared types

- `protocol`: external TCP protocol messages and frame-level constants that stay stable across service extraction.
- `common`: cross-service abstractions such as `SessionResolver`, `EventBus`, `OfflineQueue`, `ConversationSeqGenerator`, `ConversationLock`, and id generation contracts.
- `service-runtime`: service-scoped Micronaut configuration models and future runtime wiring shared by the new app entry modules.

## task 2 internal grpc skeleton

- Internal proto files now live under `protocol/src/main/proto/mochat/internal/**`, with shared envelope/session fence types in `common/v1/common.proto`.
- `api-service` exposes `SessionAuthorityApi` for session resolution, private messaging policy checks, and group send context queries. The session response carries `sessionVersion` in `SessionPrincipal`.
- `access-gateway` exposes `AccessGatewayDispatchApi` for targeted delivery, kicking replaced connections, and querying local connection state. Delivery results are normalized to `DELIVERED`, `ROUTE_STALE`, `USER_OFFLINE`, and `WRITE_FAILED`, and the placeholder implementation already fences on `sessionId` / `sessionVersion` / `routeEpoch`.
- `message-service` exposes `MessageCommandApi` for private send, group send, offline replay, and receipt acknowledgement commands.
- `api-service-app` wires a blocking client stub to `message-service`; `access-gateway-app` wires blocking client stubs to both `api-service` and `message-service`; `message-service-app` wires a blocking client stub to `api-service` plus an address-driven `access-gateway` dispatch client factory for per-owner routing; all three services register gRPC server placeholder implementations in their own app modules.
- Internal gRPC ports are reserved as `api-service:19091`, `message-service:19092`, and `access-gateway:19093`, with Micronaut gRPC channels configured by logical names `api-service` and `message-service`. `message-service -> access-gateway` is intentionally target-address driven instead of a static singleton channel.

## internal-only types

- `logic-module` internal HTTP/controller wiring and repository implementations that should not leak across service boundaries except through service contracts.
- `connection-module` handler implementations and Netty channel state that remain gateway-internal.
- `persistence-module` RocketMQ consume transaction flow and cache update mechanics that remain persistence-internal.
- `app` legacy monolith assembly remains only as a compatibility shell during the migration and is no longer the source of truth for new service entrypoints.
