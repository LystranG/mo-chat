# decompose-im-into-core-services skeleton

This document records the Task 1 service skeleton required by OpenSpec change `decompose-im-into-core-services`.

## current module inventory

### app

- Current role: legacy monolith bootstrap.
- Current assembly hotspots: `Application`, `MochatRuntimeFactory`, `ConnectionRuntimeLifecycle`, `PersistenceRuntimeLifecycle`.
- Current responsibilities still concentrated here before later extraction: PostgreSQL / Redis / RocketMQ client bootstrap, Netty TLS + server creation, outbound subscriber startup, Flyway bootstrap.
- Target ownership after decomposition: stop being the source of truth for runtime assembly; keep only compatibility until later tasks remove the monolith assumption. After Task 6.2, the shell defaults `mochat.legacy.persistence.enabled=false` and `mochat.message-service.inbound-consumer.enabled=false`, so persistence ownership no longer comes from `app` unless compatibility mode is explicitly re-enabled.

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
- Current source modules: `connection-module` for transport handlers; runtime composition for the TCP access path now lives in `access-gateway-app`.
- Owned beans or runtime responsibilities to migrate out of monolith assembly: `NettyChatServer`, `OutboundEventSubscriber`, `ConnectionRuntimeLifecycle`, `UserChannelDirectory<Channel>`, TLS context creation, bind-time session gateway adapters.
- Task 3.1 status: `access-gateway-app` now assembles the connection runtime skeleton directly, including Redis-backed `EventBus`, a gateway-local drop/no-op `OfflineQueue`, in-memory channel ownership, and a gRPC-backed `SessionResolver` that bridges bind-time session validation to `api-service`.
- Task 3.2 status: successful bind now persists a single Redis online route record carrying `gatewayPod`, `connectionId`, `sessionId`, `sessionVersion`, `routeEpoch`, and lease metadata; bind-time route persistence runs on the async bind worker instead of the Netty event loop, and indeterminate persistence outcomes self-clear local ownership before the channel is closed.
- Task 3.3 status: duplicate login now overwrites the previous Redis route and exposes the replaced route metadata to the bind pipeline; after the new route is durable, `SessionBindingHandler` triggers a best-effort replacement flow that closes matching same-pod connections locally or dispatches `KickConnection` to a configured peer target when the replaced owner is remote, always fenced by the previous `routeEpoch`. On the confirm-success fallback path, replacement metadata read failures degrade to `replacedRoute=null` instead of rolling back the already-confirmed new owner.
- Task 3.4 status: heartbeat ACK handling now renews Redis lease metadata only for channels that still own the current route, and stale owners self-clear local binding and close themselves when renewal detects a mismatched `sessionVersion` or `routeEpoch`. Heartbeat timeout cleanup now revokes local owner visibility and closes the channel immediately, then runs Redis `clearRoute` as a fenced best-effort async cleanup so idle disconnects do not keep the user locally online while route release is still in flight.
- Task 3.5 status: gateway drain mode now exposes `mochat.access-gateway.drain.enabled` and `mochat.access-gateway.drain.grace-period`, rejects new ownership both at bind entry and across async bind fence points once draining begins, and keeps already bound channels serving until the grace window expires. When grace ends, `GatewayDrainManager` walks the local bound connections and triggers a drain event that reuses the `3.4` timeout cleanup path to revoke local owner visibility, close the channel, and best-effort clear the Redis route without introducing live migration or cross-pod takeover semantics.
- Task 3.1 boundary: gateway outbound handling is now scoped to local channel write-back only; cross-pod route retry and offline fallback remain deferred to the later `message-service` tasks.

### api-service

- Owns HTTP login/session authority, social graph, groups, and history query APIs.
- Current source modules: `logic-module` HTTP controllers, `SessionService`, `FriendsService`, `GroupsService`, `HistoryService`, repository adapters.
- Owned beans or runtime responsibilities: `AuthController`, `FriendsController`, `GroupsController`, `ConversationController`, `HistoryController`, `SessionService`, user/friend/group/history repositories.
- Task 4.1 status: `api-service-app` now owns Redis-backed session issuance and authority resolution directly. `SessionService` persists authoritative session records carrying `status`, `userId`, `sessionVersion`, and expiry metadata, newer logins mark the previous session as `REPLACED`, and `ApiInternalGrpcService` resolves gateway bind authority from that Redis-backed state instead of the earlier placeholder session-id parser. The final reviewer loop tightened this into a fail-closed authority model: when the active-session pointer is missing or mismatched, the record no longer degrades to locally accepted state. Gateway-side enforcement was also extended so `SessionBindingHandler` revalidates subsequent chat traffic and heartbeat renewals against authority, while `AccessGatewayInternalGrpcService` revalidates targeted delivery and returns `ROUTE_STALE` when the presented `sessionVersion` is no longer authoritative. The `api-service` runtime also now assembles its own Redis client, `EventBus`, and `OfflineQueue` beans so login/session flows no longer depend on the legacy monolith bootstrap.
- Task 4.2 status: Java 内部 session 解析抽象现在以 `SessionAuthority(status, sessionId, userId, sessionVersion)` 和 `SessionResolver.resolveAuthority(...)` 作为统一 fence surface，对内显式返回 `status + sessionVersion`，而不再依赖旧的 `resolveUserId -> ResolvedSession(..., 0L)` 临时拼装。`GrpcSessionResolver` 直接把 gRPC `ResolveSessionResponse(status + principal)` 映射回这套 authority 模型而无需改 proto；`SessionBindingHandler` 与 `AccessGatewayInternalGrpcService` 也改为直接消费 authority 结果做 bind/revalidation/targeted delivery 的 stale 判定，保持 `4.1` 已完成的 Redis-backed authority、`sessionVersion` 和 `routeEpoch` fence 语义不回退。
- Task 4.3 status: `api-service` 现在通过 `MessageSendPolicyService` 把消息发送前置业务校验收敛成内部可调用接口：私聊会把好友关系映射成 `ALLOWED / NOT_FRIEND / BLOCKED`，群聊会显式区分 `GROUP_NOT_FOUND`、`NOT_MEMBER` 与 `ALLOWED + member_uids`。`ApiInternalGrpcService` 的 `CheckPrivateMessagingPolicy` 与 `GetGroupSendContext` 已改为直接委托这套业务服务，而不再依赖“正数 ID 即放行”的 placeholder 判断；底层 JDBC 关系仓储也补了 `groupExists(...)` 以支撑 `GROUP_NOT_FOUND` 语义。该任务只把 `api-service` 内部接口与测试做实，没有提前推进 `5.2` 的跨服务接入，也没有改动 `4.1 / 4.2` 的 session authority 与 `sessionVersion` fence 边界。
- Task 4.4 status: history read-side 继续留在 `api-service` dedicated HTTP runtime。`ApiServiceHistoryOwnershipTest` 现在通过 EmbeddedServer + persisted-history / conversation-state stub 直接证明 `/history` 与 `/conversations/{id}/state` 入口会在 `api-service` 里 materialize；对应地，`MessageServiceHistoryOwnershipTest` 证明当前 `message-service` dedicated runtime 仍只装配命令侧 gRPC bean 而不承载 `HistoryController` / `ConversationController`，`PersistenceServiceHistoryOwnershipTest` 则证明当前 `persistence-service` runtime classpath 本身不携带这些 read-side type。文档同时补充了后续 `5.x / 6.x` 迁移必须保持的边界定义：当 sender ACK / online delivery 语义迁入 `message-service` 后，它们不得被解释为历史已经可见；history query 应继续读取持久化提交后的消息事实与 conversation state，因此“实时先送达、历史稍后可见”是后续实现需要保留的语义窗口。
- Task 6.4 status: `ApiServiceHistoryOwnershipTest` 现通过 commit-aware read model focused 用例把这条窗口真正验证出来：即使某条消息已经发生 realtime delivery，`api-service` 的 `/history` 与 `/conversations/{id}/state` 也会继续停留在 committed view，直到模拟 persistence commit 后才一起暴露新的 `msgId/seq/latestMessageTime`。这次只补语义窗口验证，没有改变 history read-side ownership。

### message-service

- Owns message ingest, idempotency, ordered MQ acceptance, sender ACK semantics, and online/offline delivery orchestration.
- Current source modules: `logic-module` message ingest and receipt flow plus `message-module` contracts.
- Owned beans or runtime responsibilities: `MessageIngestService`, `RocketMqProducer`, idempotency store, conversation sequence generator, message command adapters, route/offline delivery orchestration.
- Task 4.4 boundary: 按 OpenSpec 设计，后续 `5.1 / 5.5` 完成后，sender ACK / online delivery 只说明 `message-service` 写路径已经接受请求并尝试完成实时投递，不说明 `api-service` 的 `/history` 或 `/conversations/**` 读侧已经能看见该消息；当前 `4.4` 测试只证明这条 runtime 目前不 materialize history query controller bean。
- Task 6.4 status: `MessageServiceGrpcConnectivityTest` 现补齐 private send focused 断言，证明 dedicated `message-service` runtime 在返回 accepted `msgId/seq` 后已经对 recipient 做过 realtime private dispatch 尝试；与 `MessageServiceHistoryOwnershipTest` 一起看，写侧 realtime delivery 已发生并不意味着该 runtime 会暴露 history query。
- Task 6.2 boundary: dedicated `message-service` runtime 已不再 materialize `ReceiptConversationStateStore` / `InMemoryReceiptConversationStateStore` 这类 receipt-state fallback owner；若需要保留旧单体 inbound pipeline，只能在 legacy `app` compatibility shell 里显式打开相关开关，不再代表 dedicated `message-service` 的默认 ownership。

### persistence-service

- Owns MQ consumption, transactional persistence, conversation advancement, and post-commit cache updates.
- Current source modules: `persistence-module`.
- Owned beans or runtime responsibilities: `MqConsumer`, `RocketMqPersistenceConsumer`, `PersistenceRuntimeLifecycle`, `ConversationRepository`, `MessageRepository`, `GroupMessageCache`, `ReceiptConversationStateStore`.
- Task 6.1 status: `persistence-service-app` 现在已拥有 dedicated `PersistenceServiceRuntimeFactory` / `PersistenceServiceRuntimeLifecycle`，会在自己的 runtime 内装配 `MessageRepository`、`ConversationRepository`、`GroupMessageCache`、`MqConsumer`、`DefaultMQPushConsumer` 与 `RocketMqPersistenceConsumer`，并通过 `mochat.persistence-service.queue.consumer-enabled` 控制 MQ consumer 启停。dedicated app 的 `application.yml` 也补齐了 `mochat.flyway.locations`、`mochat.postgres.*`、`mochat.redis.uri`、`mochat.rocketmq.*` 这些共享基础设施默认配置；`PersistenceServiceApplicationContextTest` 进一步证明 dedicated 默认配置路径能 materialize `DataSource` / `Flyway` / `DefaultMQPushConsumer`，并在只给 Redis 最小 test bridge 时装出完整的 persistence consumer bean graph。当前 legacy monolith `app` 里的持久化装配仍作为兼容壳保留，待后续任务再清理。
- Task 6.2 status: `ReceiptConversationStateStore` 契约已迁到 `message-module` 作为共享写侧契约，而默认 JDBC 实现 `JdbcReceiptConversationStateStore` 已迁入 `persistence-module`，因此 dedicated `persistence-service` runtime 现在会和 `MessageRepository`、`ConversationRepository`、`GroupMessageCache`、MQ consumer / lifecycle 一起 materialize receipt/state 推进 owner。`PersistenceServiceApplicationContextTest` 钉住了 dedicated runtime 的完整 bean graph 与默认配置路径；`ApiServiceApplicationContextTest` 与 `MessageServiceGrpcWiringTest` 则证明 dedicated `api-service` / `message-service` 不再回退装配 receipt-state fallback owner。legacy `app` compatibility shell 仍可在显式打开 `mochat.legacy.persistence.enabled=true` 且 `mochat.message-service.inbound-consumer.enabled=true` 时恢复旧 inbound 依赖链，但这只是过渡兼容，不再是 persistence ownership 的默认 source of truth。
- Task 6.3 status: `persistence-service` 现在会把重复 MQ 消费拆成两类处理：`MessageRepository` 在唯一键冲突后按 `msg_id` 回读现有 durable fact，字段完全一致时返回 `DURABLE_DUPLICATE`，由 `MqConsumer` 按成功 no-op 处理，因此不会再生成第二条 `messages` 事实、也不会重复推进 `conversations` 或 group cache；字段不一致时则抛出 `DurableMessageConflictException` 并保留现有 retry 语义，不把真实冲突伪装成幂等成功。对应 focused 验证已覆盖真实 PostgreSQL 约束下的 duplicate replay 与 dedicated `persistence-service-app` runtime 基线，但没有提前声称 `6.4` 的“实时先送达、历史稍后可见”窗口已经完成验证。
- Task 4.4 boundary: 按 OpenSpec 设计，后续 `6.x` 完成后，`persistence-service` 将负责 durable message truth 与 conversation advancement，`api-service` history query 只读取这类 post-commit state。只要持久化事务还没提交，历史查询暂时看不到新消息应当仍是允许的；这不会改变后续 `5.x` 定义的实时投递或 sender ACK 语义边界。
- Task 6.4 status: focused 验证现在把这条 commit boundary 变成了显式测试结论：history/state 的可见性仍以后续 persistence commit 为准，而不是以 realtime delivery、sender ACK 或 replay success 为准。本轮没有扩写 `6.3` 的 durable conflict / poison-message 处理策略。

## shared types

- `protocol`: external TCP protocol messages and frame-level constants that stay stable across service extraction.
- `common`: cross-service abstractions such as `SessionResolver`, `EventBus`, `OfflineQueue`, `ConversationSeqGenerator`, `ConversationLock`, and id generation contracts.
- `service-runtime`: service-scoped Micronaut configuration models and future runtime wiring shared by the new app entry modules.

## task 7 integration and rollback baseline

- Task 7.1 status: `MessageServiceCrossGatewayRoutingIntegrationTest` now proves a sender on one gateway can target a recipient owned by another gateway, and that only the owning gateway writes the `ChatMessageDelivery` frame to the bound recipient connection.
- Task 7.2 status: cross-gateway duplicate-login fencing is now covered by `AccessGatewayOnlineRouteBindingTest.newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills()`, while `MessageServiceCrossGatewayRoutingIntegrationTest.staleRouteAfterSingleRefreshFallsBackToOfflineQueue()` proves `message-service` refreshes route resolution once and then writes a replayable offline envelope when delivery still cannot complete.
- Task 7.3 status: drain / rollout semantics are now covered end-to-end by `AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace()`, together with the pre-existing focused tests for rejecting new bind ownership and closing bound connections when grace expires.
- Dedicated local topology now treats `api-service`, `message-service`, `access-gateway`, and `persistence-service` as the default runtime shape; the legacy `app` module remains only as an opt-in compatibility shell.
- Internal port baseline: `api-service` gRPC `19091`, `message-service` gRPC `19092`, `access-gateway` gRPC `19093`, `access-gateway` TCP `9000`, and `api-service` HTTP `8080`. When running more than one gateway locally, the current convention is `gateway-a: tcp 9000 / grpc 19093` and `gateway-b: tcp 9001 / grpc 19094`. `message-service` must therefore provide `gateway-targets.gateway-a` / `gateway-targets.gateway-b`, and each gateway instance must provide the opposite `peer-targets.*` entry so duplicate-login kick traffic can cross gateway boundaries. `persistence-service` owns DB / MQ consume flow without exposing an internal gRPC API in this change.
- Rollback baseline: if dedicated service rollout needs to be reverted, stop the dedicated processes and re-enable the compatibility shell through `MOCHAT_LEGACY_PERSISTENCE_ENABLED=true` and `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=true` before running `:app:run`. This restores the legacy bootstrap path without changing the dedicated-service defaults.

## task 2 internal grpc skeleton

- Internal proto files now live under `protocol/src/main/proto/mochat/internal/**`, with shared envelope/session fence types in `common/v1/common.proto`.
- `api-service` exposes `SessionAuthorityApi` for session resolution, private messaging policy checks, and group send context queries. The session response carries `sessionVersion` in `SessionPrincipal`.
- `access-gateway` exposes `AccessGatewayDispatchApi` for targeted delivery, kicking replaced connections, and querying local connection state. Delivery results are normalized to `DELIVERED`, `ROUTE_STALE`, `USER_OFFLINE`, and `WRITE_FAILED`, and the placeholder implementation already fences on `sessionId` / `sessionVersion` / `routeEpoch`.
- `message-service` exposes `MessageCommandApi` for private send, group send, offline replay, and receipt acknowledgement commands.
- `api-service-app` wires a blocking client stub to `message-service`; `access-gateway-app` wires blocking client stubs to both `api-service` and `message-service`, and Task 3.1 now consumes the `api-service` stub through a `SessionResolver` adapter for bind-time handshake; `message-service-app` wires a blocking client stub to `api-service` plus an address-driven `access-gateway` dispatch client factory for per-owner routing; all three services register gRPC server placeholder implementations in their own app modules.
- Internal gRPC ports are reserved as `api-service:19091`, `message-service:19092`, and `access-gateway:19093`, with Micronaut gRPC channels configured by logical names `api-service` and `message-service`. `message-service -> access-gateway` is intentionally target-address driven instead of a static singleton channel.
- As long as Task 3 has not migrated the old connection inbound pipeline, both `api-service-app` and `message-service-app` default `mochat.message-service.inbound-consumer.enabled` to `false` so they can start independently without the legacy monolith `EventBus` bean graph. The flag is only preserved as a transition hook; future re-enablement of the legacy inbound consumer would still require restoring the legacy `EventBus` bean graph and related wiring, not just flipping this property to `true`.

## internal-only types

- `logic-module` internal HTTP/controller wiring and repository implementations that should not leak across service boundaries except through service contracts.
- `connection-module` handler implementations and Netty channel state that remain gateway-internal.
- `persistence-module` RocketMQ consume transaction flow and cache update mechanics that remain persistence-internal.
- `app` legacy monolith assembly remains only as a compatibility shell during the migration and is no longer the source of truth for new service entrypoints.
