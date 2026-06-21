# Shared Codebase Memory

## 职责

`shared` 边界承载跨 dedicated services 的稳定契约与轻量共享实现：

- 外部 TCP protobuf 协议与固定帧头常量：`protocol/src/main/proto/mochat/v1/chat.proto`、`protocol/src/main/java/com/github/lystran/mochat/protocol/FrameConstants.java`、`MsgType.java`
- 内部 gRPC proto 契约：`protocol/src/main/proto/mochat/internal/api/v1/api_service.proto`、`gateway/v1/access_gateway.proto`、`message/v1/message_service.proto`、`common/v1/common.proto`
- 跨服务抽象接口：`common/src/main/java/com/github/lystran/mochat/common/event/EventBus.java`、`offline/OfflineQueue.java`、`session/SessionResolver.java`、`session/SessionRouteWriter.java`、`seq/ConversationSeqGenerator.java`、`idempotency/IdempotencyStore.java`
- Redis 基础设施实现：`infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/*`
- runtime 配置模型与拓扑解析：`service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/*`、`runtime/topology/*`
- 跨服务消息域契约：`message-module/src/main/java/com/github/lystran/mochat/message/contract/*`、`message-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptConversationStateStore.java`

## 非职责

- 不拥有 HTTP controller、TCP handler、MQ consume 事务流或 DB repository 的业务实现。
- 不决定 dedicated services 的 runtime ownership，只提供配置模型、协议和可复用基础设施。
- 不应重新演化成隐式单体装配层。
- `message-module` 保留 `ReceiptConversationStateStore` 契约，但 JDBC owner 在 `persistence-module`。

## 主要代码路径

- `common/src/main/java/com/github/lystran/mochat/common/**`
- `protocol/src/main/java/com/github/lystran/mochat/protocol/**`
- `protocol/src/main/proto/mochat/**`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/**`
- `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/**`
- `service-runtime/src/main/java/com/github/lystran/mochat/runtime/topology/**`
- `message-module/src/main/java/com/github/lystran/mochat/message/contract/**`
- `message-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptConversationStateStore.java`

## 核心数据流和交互

- 外部 TCP：固定 header + protobuf body，协议消息在 `protocol/src/main/proto/mochat/v1/chat.proto`，消息类型由 `MsgType` 维护。
- 内部 gRPC：
  - `SessionAuthorityApi` 提供 session resolution、私聊策略、群发送上下文。
  - `AccessGatewayDispatchApi` 提供 targeted delivery、kick、local connection state。
  - `MessageCommandApi` 提供 private/group send、offline replay、receipt ack。
- session fencing：`protocol/src/main/proto/mochat/internal/common/v1/common.proto`、`common/session/SessionAuthority.java`、`ResolvedSession.java`、`PersistedSessionRoute.java` 表达 `sessionVersion` / `routeEpoch` 相关边界。
- Redis shared 实现：
  - `RedisEventBus`
  - `RedisOfflineQueue`
  - `RedisConversationSeqGenerator`
  - `RedisIdempotencyStore`
- Kubernetes 拓扑解析：
  - `RuntimeTopologyConfiguration`
  - `PodMetadataGatewayIdentityProvider`
  - `KubernetesDnsGatewayAddressResolver`
  - `StaticGatewayAddressResolver`

当前注意点：

- `message-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptConversationStateStore.java` 包名仍是 `logic.chat`，但模块是 `message-module`，不要仅凭包名判断 ownership。
- `service-runtime` 同时承载 runtime config 与 Kubernetes manifest 测试，不是纯配置库。
- `MessageServiceConfiguration.InboundConsumer.enabled` Java 默认值是 `true`，但 dedicated `message-service-app/src/main/resources/application.yml` 默认显式设为 `false`；以 app 配置和测试语义为准。

## 配置和运行入口

shared 本身不是可运行入口。配置模型被 dedicated apps 的 `application.yml` 消费：

- `access-gateway-app/src/main/resources/application.yml`
- `api-service-app/src/main/resources/application.yml`
- `message-service-app/src/main/resources/application.yml`
- `persistence-service-app/src/main/resources/application.yml`

Gradle 模块：

- `common/build.gradle.kts`
- `protocol/build.gradle.kts`
- `infra-redis/build.gradle.kts`
- `service-runtime/build.gradle.kts`
- `message-module/build.gradle.kts`

## 测试入口

- `./gradlew :common:test`
- `./gradlew :protocol:test`
- `./gradlew :infra-redis:test`
- `./gradlew :service-runtime:test`
- `./gradlew :message-module:test`
- `common/src/test/java/com/github/lystran/mochat/common/event/EventBusContractTest.java`
- `common/src/test/java/com/github/lystran/mochat/common/event/InProcessEventBusTest.java`
- `common/src/test/java/com/github/lystran/mochat/common/lock/JucConversationLockTest.java`
- `protocol/src/test/java/com/github/lystran/mochat/protocol/ProtoRoundTripTest.java`
- `protocol/src/test/java/com/github/lystran/mochat/protocol/InternalGrpcContractGenerationTest.java`
- `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/*`
- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/topology/RuntimeTopologyConfigurationTest.java`
- `message-module/src/test/java/com/github/lystran/mochat/message/contract/MessageContractsTest.java`

## 变更时必须同步更新

- 新增、删除或改名 proto service、RPC、message、enum 字段。
- 修改 TCP frame 常量、`MsgType`、protobuf 外部消息语义。
- 修改 `SessionResolver`、`SessionRouteWriter`、`OfflineQueue`、`EventBus` 等 shared contract。
- 修改 Redis key prefix、幂等 TTL、offline queue 行为、seq fallback 行为。
- 修改 `RuntimeTopologyConfiguration` 的 identity/discovery 模式或默认值。
- 将任何业务 owner 从 dedicated module 上移到 shared module，或把 shared contract 下沉到单个 service。

