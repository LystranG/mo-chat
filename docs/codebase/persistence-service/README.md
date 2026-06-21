# Persistence Service Codebase Memory

## 职责

`persistence-service` 是 dedicated topology 中的 durable truth owner：

- 消费 RocketMQ。
- 在 JDBC transaction 内写入 PostgreSQL `messages`。
- 推进 `conversations.latest_seq/latest_message_time` 和相关 receipt/state。
- commit 后更新 group message cache。
- 处理重复 MQ consume 的 durable idempotency。

核心实现：

- 入口：`persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplication.java`
- runtime 装配：`persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeFactory.java`
- lifecycle：`persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeLifecycle.java`
- MQ listener：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
- 事务写路径：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
- durable duplicate：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
- conversation advancement：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`

## 非职责

- 不拥有 history read-side，不暴露 `/history` 或 `/conversations/{id}/state`。
- 不暴露公开 HTTP/TCP listener；当前 Kubernetes manifest 未为 `persistence-service` 创建 Service，源码未暴露业务 HTTP/gRPC/TCP 入站 API。
- 不负责 sender ACK、在线投递或 offline fallback。
- 不负责通话离线通知消费或 `call_offline_notifications` 写入；该路径属于 `call-service`。
- 不负责 session authority、好友/群业务 HTTP 或 history query。
- 不负责 TCP bind、心跳或在线 route ownership。
- 不做 database-per-service，不做 distributed transaction。
- `app` 中的 persistence 装配只是 legacy compatibility shell，不是默认 ownership source。

## 主要代码路径

- App 入口：`persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplication.java`
- Dedicated runtime：`persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeFactory.java`、`PersistenceServiceRuntimeLifecycle.java`
- 配置：`persistence-service-app/src/main/resources/application.yml`、`service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/PersistenceServiceConfiguration.java`
- 持久化核心：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`、`MqConsumer.java`、`MessageRepository.java`、`ConversationRepository.java`、`DurableMessageConflictException.java`
- 缓存：`persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- receipt/state：`message-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptConversationStateStore.java`、`persistence-module/src/main/java/com/github/lystran/mochat/logic/chat/JdbcReceiptConversationStateStore.java`
- persistence contract：`message-module/src/main/java/com/github/lystran/mochat/message/contract/MessageAcceptedEvent.java`、`MessagePersistencePort.java`、`PersistenceAckEvent.java`
- DB migration：`persistence-module/src/main/resources/db/migration/V1__phase1.sql`、`V2__group_join_requests_pending_pair_uniq.sql`
- Packaging：`persistence-service-app/Dockerfile`、`persistence-service-app/src/main/resources/META-INF/native-image/com.github.lystran/mochat/reachability-metadata.json`

## 核心数据流和交互

1. `message-service` 接受消息后 publish RocketMQ，contract 类型是 `message-module/src/main/java/com/github/lystran/mochat/message/contract/MessageAcceptedEvent.java`。
2. `MessageAcceptedEvent.shardingKey()` 使用 `conversationId`，让同会话消息稳定进入同一 MQ 分片。
3. `persistence-service` 的 `DefaultMQPushConsumer` 订阅 `mochat.rocketmq.topic`，listener 是 `RocketMqPersistenceConsumer`。
4. RocketMQ envelope 当前是 `|` 分隔的 11 字段文本格式。
5. `RocketMqPersistenceConsumer.consumeMessage(...)` 逐条 parse 后调用 `MessagePersistencePort.persist(...)`。
6. `MqConsumer.persist(...)` 在 JDBC transaction 内插入 `messages`，推进 `conversations.latest_seq/latest_message_time`，然后 commit。
7. `MessageRepository` 遇到唯一键冲突后按 `msg_id` 回读：字段完全一致则 durable duplicate no-op；字段不一致则抛 `DurableMessageConflictException`，保留 MQ retry/suspend 语义。
8. group message 在 DB commit 后更新 `GroupMessageCache`；缓存失败不回滚已提交 durable truth。
9. `/history` 与 `/conversations/{id}/state` 的可见性以后续 persistence commit 为准；sender ACK 和 realtime delivery 都不等于 history 可见。

当前注意点：

- `JdbcReceiptConversationStateStore` 实现位于 `persistence-module`，但包路径仍是 `com.github.lystran.mochat.logic.chat`。
- `V1__phase1.sql` 包含 `users`、`user_friendships`、`groups` 等 Phase 1 schema；`call-module/src/main/resources/db/migration/V4__call_offline_notifications.sql` 新增通话离线通知表。表的业务 lifecycle 仍按服务 ownership 拆分，不等于全部归 persistence-service 管理。
- durable conflict 当前保持 suspend/retry 语义，不要写成已有 poison-message、DLQ 或人工隔离策略。
- `persistence-service-app/Dockerfile` 使用 `:installDist` + JRE，不是 native image；`access-gateway`、`api-service`、`message-service` Dockerfile 使用 native image；`call-service` 当前没有 Dockerfile。

## 配置和运行入口

- 本地运行：`./gradlew :persistence-service-app:run`
- main class：`com.github.lystran.mochat.persistenceservice.PersistenceServiceApplication`
- 配置文件：`persistence-service-app/src/main/resources/application.yml`
- Flyway：`mochat.flyway.locations=classpath:db/migration`、`MOCHAT_PERSISTENCE_SERVICE_FLYWAY_MIGRATE_ON_START`
- Redis：`MOCHAT_REDIS_URI`、`MOCHAT_PERSISTENCE_SERVICE_REDIS_ENABLED`
- PostgreSQL：`MOCHAT_POSTGRES_URL`、`MOCHAT_POSTGRES_USERNAME`、`MOCHAT_POSTGRES_PASSWORD`、`MOCHAT_PERSISTENCE_SERVICE_POSTGRES_ENABLED`
- RocketMQ：`MOCHAT_ROCKETMQ_NAME_SERVER`、`MOCHAT_PERSISTENCE_SERVICE_ROCKETMQ_CONSUMER_GROUP`、`MOCHAT_ROCKETMQ_TOPIC`、`MOCHAT_PERSISTENCE_SERVICE_MQ_ENABLED`
- Consumer 开关：`MOCHAT_PERSISTENCE_SERVICE_QUEUE_CONSUMER_ENABLED`

## 测试入口

- `./gradlew :persistence-service-app:test`
- `./gradlew :persistence-module:test`
- `./gradlew :message-module:test`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceDockerPackagingContractTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceNativeMetadataContractTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MigrationSmokeTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/cache/GroupMessageCacheTest.java`
- `message-module/src/test/java/com/github/lystran/mochat/message/contract/MessageContractsTest.java`

## 变更时必须同步更新

- 修改 `persistence-service-app/src/main/resources/application.yml` 中 `mochat.persistence-service.*`、`mochat.postgres.*`、`mochat.redis.*`、`mochat.rocketmq.*` 配置名、默认值或环境变量。
- 修改 `PersistenceServiceRuntimeFactory` / `PersistenceServiceRuntimeLifecycle` 的 bean graph、启动/关闭行为、Flyway 启动策略、consumer 开关语义。
- 修改 `RocketMqPersistenceConsumer` 的 MQ envelope 格式、字段数量、parse 规则、订阅 topic/filter、失败返回策略或 suspend 时间。
- 修改 `MessageAcceptedEvent`、`MessagePersistencePort`、`PersistenceAckEvent` 等 persistence contract。
- 修改 `MqConsumer` 的事务边界、commit 顺序、rollback 策略、group cache post-commit 行为。
- 修改 `MessageRepository` 的 durable duplicate / conflict 判断逻辑，或 `messages` 唯一约束依赖。
- 修改 `ConversationRepository` 对 `conversations.latest_seq/latest_message_time/uid_*_seq` 的推进规则。
- 修改 `JdbcReceiptConversationStateStore` 或 `ReceiptConversationStateStore` ownership 条件。
- 修改 `persistence-module/src/main/resources/db/migration/**` 中表、索引、约束、字段。
- 让 `persistence-service` 暴露 HTTP/gRPC/TCP 入站 API，或新增 Kubernetes Service。
- 改变 sender ACK / realtime delivery / durable commit 三者关系。
- 移除或改变 legacy `app` compatibility shell 中 `mochat.legacy.persistence.enabled` 相关行为。
- 修改 Dockerfile/native metadata 影响部署或原生镜像运行边界。
