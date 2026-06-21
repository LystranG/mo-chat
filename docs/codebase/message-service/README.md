# Message Service Codebase Memory

## 职责

`message-service` 负责同步消息接受路径、sender ACK 语义、在线投递编排和 offline fallback：

- gRPC 命令入口：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
- 内部 proto：`protocol/src/main/proto/mochat/internal/message/v1/message_service.proto`
- 消息接受核心：`logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- Redis 幂等窗口：`infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`
- conversation seq 分配：`infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`
- `msgId` 分配：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceSnowflakeIdGenerator.java`
- RocketMQ 同步 publish：`logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`
- 在线投递：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
- offline queue 与 replay：`infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`、`logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageServiceOfflineReplayService.java`
- 发送策略校验：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageSendPolicyGateway.java` 调 `api-service`

## 非职责

- 不拥有 durable message truth；它只 publish `MessageAcceptedEvent` 到 MQ，最终事实由 `persistence-service` 消费后写入 PostgreSQL。
- 不负责 history query；dedicated runtime 不 materialize `HistoryController` / `ConversationController`。
- 不负责持久化提交和 conversation advancement 的最终事实。
- dedicated runtime 默认不拥有 receipt-state fallback；`MessageCommandGrpcService#acknowledgeReceipt` 当前是 skeleton，临时返回 accepted。
- 默认不启用 legacy event-bus inbound consumer；`mochat.message-service.inbound-consumer.enabled` 在 dedicated app 配置里默认是 `false`。
- 不拥有 session authority；发送策略校验走 `api-service` 的 social/group policy，不等同于 session authority 校验。

## 主要代码路径

- 运行入口：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/MessageServiceApplication.java`、`message-service-app/src/main/resources/application.yml`、`message-service-app/build.gradle.kts`
- runtime 装配：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`、`MessageServiceSnowflakeIdGenerator.java`
- gRPC：`MessageCommandGrpcService.java`、`MessageServiceGrpcClientFactory.java`、`GrpcMessageSendPolicyGateway.java`、`GrpcMessageRecipientDispatcher.java`、`CachingAccessGatewayDispatchClientFactory.java`、`AccessGatewayDispatchClientFactory.java`
- 消息接受核心：`logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`、`MessageIngestRequest.java`、`MessageIngestResult.java`、`MessageRejectException.java`
- 投递和离线：`MessageRecipientDispatcher.java`、`PrivateMessageDelivery.java`、`GroupMessageDelivery.java`、`MessageDeliveryStatus.java`、`MessageServiceOfflineReplayService.java`、`ReplayableDeliveryPayloadCodec.java`
- MQ 与共享契约：`logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`、`message-module/src/main/java/com/github/lystran/mochat/message/contract/MessageAcceptedEvent.java`
- Redis adapters：`infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`、`RedisOfflineQueue.java`、`RedisConversationSeqGenerator.java`
- legacy compatibility：`logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`、`EventBusSenderAckPublisher.java`、`EventBusMessageRecipientDispatcher.java`

## 核心数据流和交互

1. `api-service` 或 `access-gateway` 调 `MessageCommandApi.SendPrivateMessage` / `SendGroupMessage`。
2. `MessageCommandGrpcService` 转成 `MessageIngestRequest`。
3. `MessageIngestService.ingest` 按 `conversationId` 加锁。
4. 查 `IdempotencyStore.find(senderUid, clientMsgId)`；幂等命中时复用旧 `msgId/seq`，重新发 sender ACK，不重新 publish MQ。
5. 幂等未命中时校验 payload 和参与人，通过 `GrpcMessageSendPolicyGateway` 调 `api-service` 校验私聊关系或群成员上下文。
6. `ConversationSeqGenerator.next(conversationId)` 分配 seq，`IdGenerator.nextId()` 分配 msgId。
7. 转成 `MessageAcceptedEvent`，由 `RocketMqProducer.publishOrdered` 同步写 RocketMQ。
8. MQ 成功后才写入 Redis 幂等窗口并发布 sender ACK。
9. 私聊或群聊尝试在线投递；在线投递失败时最多刷新一次 Redis route。
10. 仍失败则写 Redis offline queue。
11. 登录后 `ReplayOfflineMessages` 调 `MessageServiceOfflineReplayService.replay` drain offline queue，并逐条重新走 online dispatcher；失败时把当前和后续 payload 重新 enqueue。

当前注意点：

- `MessageCommandGrpcService#acknowledgeReceipt` 当前是 skeleton，不要写成 receipt 已由 dedicated message-service 完整推进。
- send command proto 带 `session_id/session_version`，但当前 gRPC service 构造 `MessageIngestRequest` 时没有用它们做 session authority 校验。
- 幂等记录在 MQ publish 成功后才写入 Redis；文档不要扩大成 outbox 或 exactly-once 保证。
- `GrpcMessageRecipientDispatcher` 只映射单次投递状态；refresh once + offline fallback 在 `MessageIngestService` 中实现。
- dedicated runtime 默认 `SenderAckPublisher` 是 no-op fallback，ACK 如何回到客户端需结合具体调用链和测试核对。

## 配置和运行入口

- 本地运行：`./gradlew :message-service-app:run`
- main class：`com.github.lystran.mochat.messageservice.MessageServiceApplication`
- gRPC：`MOCHAT_MESSAGE_SERVICE_GRPC_PORT=19092`
- api-service client：`MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091`
- Redis：`MOCHAT_REDIS_URI`
- RocketMQ：`MOCHAT_ROCKETMQ_NAME_SERVER`、`MOCHAT_ROCKETMQ_PRODUCER_GROUP`、`MOCHAT_ROCKETMQ_TOPIC`
- id worker：`MOCHAT_MESSAGE_SERVICE_ID_WORKER_ID`
- legacy inbound consumer：`MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=false`
- dependency toggles：`MOCHAT_MESSAGE_SERVICE_API_GRPC_ENABLED`、`MOCHAT_MESSAGE_SERVICE_GATEWAY_GRPC_ENABLED`、`MOCHAT_MESSAGE_SERVICE_MQ_ENABLED`、`MOCHAT_MESSAGE_SERVICE_REDIS_ENABLED`
- gateway target resolution 在 `MessageServiceRuntimeFactory#gatewayAddressResolver`：`AUTO` 模式下有 Pod metadata 则用 Kubernetes DNS，否则回退 static targets `mochat.message-service.route.gateway-targets.*`。

## 测试入口

- `./gradlew :message-service-app:test`
- `./gradlew :logic-module:test`
- `./gradlew :infra-redis:test`
- `./gradlew :message-module:test`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceApplicationContextTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceHistoryOwnershipTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceCrossGatewayRoutingIntegrationTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageSendPolicyGatewayTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceRelationshipAndGroupTest.java`
- `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStoreTest.java`
- `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueueTest.java`
- `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/RedisSeqGeneratorTest.java`
- `message-module/src/test/java/com/github/lystran/mochat/message/contract/MessageContractsTest.java`

## 变更时必须同步更新

- 修改 `protocol/src/main/proto/mochat/internal/message/v1/message_service.proto` 中任何 RPC、字段或 ACK 语义。
- 修改 `MessageCommandGrpcService` 的命令映射、错误返回、`AcknowledgeReceipt` 实现状态。
- 修改 `MessageIngestService` 的接受顺序，尤其幂等、seq/msgId、MQ publish、ACK、online dispatch、offline fallback 的先后关系。
- 修改 `RedisIdempotencyStore` 的 key、value 格式、TTL 或幂等语义。
- 修改 `RedisConversationSeqGenerator` 的 key、seed 策略或锁策略。
- 修改 `RocketMqProducer` 的 topic、序列化格式、sharding key、同步 publish 语义。
- 修改 `GrpcMessageRecipientDispatcher` 读取 Redis route 的 key/字段格式，或 `sessionVersion/routeEpoch` fence 传递方式。
- 修改 gateway discovery/static target/Kubernetes DNS resolver 规则。
- 修改 `RedisOfflineQueue` 的 key、队列方向、裁剪策略、drain 语义。
- 修改 `ReplayableDeliveryPayloadCodec` 的 payload 格式。
- 修改 dedicated runtime 是否默认启用 `InboundMessageConsumer`。
- 修改 dedicated runtime 是否 materialize history controller、receipt-state owner、repository policy fallback。
- 修改 sender ACK 含义，尤其是否仍只代表 MQ accepted。
- 修改与 `api-service` 的发送策略调用边界，或与 `persistence-service` 的 ownership 分界。
