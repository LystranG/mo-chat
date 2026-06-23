# Access Gateway Codebase Memory

## 职责

`access-gateway` 负责长连接入口和在线连接 ownership：

- TCP/TLS 聊天入口，由 `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplication.java` 启动，`connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java` 监听 TCP。
- Netty pipeline 组装，主要在 `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`，包含 TLS、frame codec、限流、心跳、session bind、inbound router。
- session bind 与权威校验消费，核心在 `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`；默认 `SessionResolver` 是 `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GrpcSessionResolver.java`，调用 `api-service`。
- Redis 在线 route ownership，实现在 `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java`，保存 `gatewayPod`、`connectionId`、`sessionId`、`sessionVersion`、`routeEpoch` 和 lease。
- duplicate login 旧连接替换，实现在 `GatewayRouteReplacementHandler.java`，本地关闭或跨 gateway 调 `KickConnection`。
- targeted delivery 的本地 channel 写回，入口是 `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/grpc/AccessGatewayInternalGrpcService.java`。
- drain / rollout 生命周期，涉及 `GatewayDrainManager.java`、`GatewayIngressLifecycle.java`、`AccessGatewayLifecycleController.java`。

## 非职责

- 不拥有 session authority；权威来源是 `api-service`，gateway 只消费 `common/src/main/java/com/github/lystran/mochat/common/session/SessionAuthority.java`。
- 不拥有消息 durable truth，不写 `messages` 或 `conversations`。
- 不拥有 history read-side。
- 不负责 sender ACK、幂等、`msgId`/`seq` 分配、RocketMQ publish 或 offline fallback 编排，这些属于 `message-service`。
- `AccessGatewayRuntimeFactory` 中的 no-op `OfflineQueue` 是 gateway-local fallback，不代表 gateway 拥有离线队列。
- `connection-module/src/main/java/com/github/lystran/mochat/connection/OutboundEventSubscriber.java` 仍支持旧式 `connection.outbound` 事件总线本地写回；当前主线 targeted delivery 是 `message-service -> access-gateway` 的 owner-addressed gRPC 调用。

## 主要代码路径

- 启动与配置：`access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplication.java`、`access-gateway-app/src/main/resources/application.yml`、`access-gateway-app/build.gradle.kts`
- runtime 装配：`access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayRuntimeFactory.java`
- runtime lifecycle：`AccessGatewayConnectionRuntimeLifecycle.java`、`GatewayDrainManager.java`、`GatewayIngressLifecycle.java`、`AccessGatewayLifecycleController.java`
- gRPC：`access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/grpc/AccessGatewayInternalGrpcService.java`、`AccessGatewayGrpcClientFactory.java`、`protocol/src/main/proto/mochat/internal/gateway/v1/access_gateway.proto`
- route / directory：`RedisOnlineRouteChannelSessionRegistry.java`、`InMemoryUserChannelDirectory.java`、`LocalGatewayConnectionDirectory.java`、`LocalConnectionStateSnapshot.java`、`GatewayRouteReplacementHandler.java`
- TCP pipeline：`connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java`、`ChatChannelInitializer.java`、`RateLimitHandler.java`、`HeartbeatHandler.java`、`SessionBindingHandler.java`、`InboundRouterHandler.java`、`OutboundEventSubscriber.java`
- shared contracts：`common/src/main/java/com/github/lystran/mochat/common/session/**`、`common/src/main/java/com/github/lystran/mochat/common/directory/UserChannelDirectory.java`

## 核心数据流和交互

1. 客户端 TCP 消息进入 `ChatChannelInitializer` 管线：TLS -> frame decoder -> protocol codec -> `RateLimitHandler` -> `HeartbeatHandler` -> `SessionBindingHandler` -> `InboundRouterHandler`。
2. `SessionBindingHandler` 从 `PRIVATE_MESSAGE`、`GROUP_MESSAGE`、`CLIENT_RECEIVE_ACK` 解析 `sessionId`，调用 `SessionResolver.resolveAuthority`。
3. `GrpcSessionResolver` 调 `api-service`，只接受 `ACTIVE` session，并保留 `sessionVersion`。
4. bind 成功后，`RedisOnlineRouteChannelSessionRegistry.writeRoute` 用 Lua 原子写 `online:user:{uid}`，递增 `online:user:{uid}:route-epoch`，并保存 replaced route metadata。
5. 新 bind 若覆盖旧 route，`GatewayRouteReplacementHandler` 本地或远程调用 `KickConnection`，用 `sessionVersion + routeEpoch` fence 精确关闭旧连接。
6. 心跳收到后，`SessionBindingHandler` 重新校验 authority，并通过 `renewRoute` 续租；如果 route 已被新 owner 替换，旧连接 self-kill。
7. 心跳超时或 drain 宽限期结束时，先清本地 ownership，再 best-effort fenced clear Redis route，然后关闭 Channel。
8. `message-service` 调 `AccessGatewayDispatchApi.DeliverToConnection` 做 targeted delivery；`AccessGatewayInternalGrpcService` 检查本地连接、`sessionId`、`sessionVersion`、`routeEpoch` 和 api-service authority，成功后写客户端 TCP 帧。

当前注意点：

- `AccessGatewayDispatchClient` 当前只封装 `KickConnection`；recipient delivery 是外部服务直接调用 gateway gRPC service。
- `access_gateway.proto` 有 `LOCAL_CONNECTION_STATE_DRAINING`，但当前本地 state 映射主要返回 `BOUND` 或 `STALE`。
- 远程 kick 是 best-effort；远程失败后仍依赖旧连接下一次 heartbeat renew 发现 stale 并自杀。

## 配置和运行入口

- 本地运行：`./gradlew :access-gateway-app:run`
- main class：`com.github.lystran.mochat.accessgateway.AccessGatewayApplication`
- 默认 HTTP lifecycle 端口：`MOCHAT_ACCESS_GATEWAY_HTTP_PORT=18080`
- 默认 TCP：`MOCHAT_ACCESS_GATEWAY_TCP_HOST=0.0.0.0`、`MOCHAT_ACCESS_GATEWAY_TCP_PORT=9000`
- 默认 gRPC：`MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19093`
- TLS 默认强制开启：`MOCHAT_ACCESS_GATEWAY_TLS_ENABLED=true`，代码会拒绝禁用 TLS。
- Redis：`MOCHAT_REDIS_URI`
- session authority 上游：`MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091`
- message-service channel 也被装配：`MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092`；当前生产主线尚未看到 gateway 实际调用 message command stub。
- Kubernetes identity 优先来自 `MOCHAT_RUNTIME_POD_NAME` / `MOCHAT_RUNTIME_POD_NAMESPACE`。
- 静态回滚 identity 使用 `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD`，peer kick target 使用 `mochat.access-gateway.route.peer-targets.*`。
- lifecycle endpoint：`/internal/lifecycle/livez`、`/internal/lifecycle/readyz`、`/internal/lifecycle/drain`。

## 测试入口

- `./gradlew :access-gateway-app:test`
- `./gradlew :connection-module:test`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcWiringTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewaySessionResolverAdapterTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayLifecycleEndpointTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayOnlineRouteBindingTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistryTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/GatewayRouteReplacementHandlerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerRouteFailureTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/OutboundEventSubscriberTest.java`

## 变更时必须同步更新

- 修改 `access-gateway-app/src/main/resources/application.yml` 中 TCP、TLS、gRPC、Redis、drain、gateway identity/discovery、peer targets 配置。
- 修改 `AccessGatewayRuntimeFactory.java` 的 bean graph、默认 dependency、TLS 强制策略、Redis/EventBus/OfflineQueue 行为。
- 修改 `SessionBindingHandler.java` 的 bind、authority revalidation、async backlog、heartbeat renew、clear route、drain 拒绝或失败回滚语义。
- 修改 `RedisOnlineRouteChannelSessionRegistry.java` 的 Redis key、payload 字段、Lua fence、`routeEpoch`、lease 或 replaced route 语义。
- 修改 `AccessGatewayInternalGrpcService.java` 或 `protocol/src/main/proto/mochat/internal/gateway/v1/access_gateway.proto` 的 gRPC 方法、状态码、fencing 字段或客户端帧编码。
- 修改 `GatewayRouteReplacementHandler.java`、gateway discovery 或 peer target 逻辑。
- 修改 drain、readiness、shutdown wait 行为。
- 修改 `connection-module` TCP pipeline 顺序、限流、心跳、inbound/outbound topic 或本地写回行为。
- 将 access-gateway 对 `message-service` stub 从“仅装配”变成实际生产调用。
- 改变默认端口或 README / runbook 中的多 gateway 本地拓扑约定。

