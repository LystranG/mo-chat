# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 6.1 待执行）

## 当前状态
- 已完成并勾选：
  - `3.1`
  - `3.2`
  - `3.3`
  - `3.4`
  - `3.5`
  - `4.1`
  - `4.2`
  - `4.3`
  - `4.4`
  - `5.1`
  - `5.2`
  - `5.3`
  - `5.4`
  - `5.5`
- 当前下一个未完成任务：
  - `6.1`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮已经完成的内容

### `5.5` 已完成
- `SEND_ACK` 语义已保持在 `message-service` 的 MQ publish success 之后，不等待 DB commit
- `message-service` 的 `ReplayOfflineMessages` 已经不再是 skeleton：
  - 会真实 `drain OfflineQueue`
  - 会消费 `5.4` 已落地的 replayable `ChatMessageDelivery` envelope
  - replay 失败时会把当前及剩余 payload 重新入队，而不是丢失
- 登录后的 replay 触发已经通过 `LoginOfflineReplayGateway` 收口：
  - dedicated `api-service` runtime 优先走 `GrpcLoginOfflineReplayGateway`
  - 现有 local/logic 测试图保留 `LocalLoginOfflineReplayGateway` fallback
- 本轮还补了 `GROUP_MESSAGE` replay 的 focused coverage
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageServiceOfflineReplayService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/LoginOfflineReplayGateway.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/LocalLoginOfflineReplayGateway.java`
  - `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/GrpcLoginOfflineReplayGateway.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
- 本轮末尾我亲自 fresh 复跑的验证命令是：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  --tests com.github.lystran.mochat.logic.chat.OfflineReplayServiceTest \
  --tests com.github.lystran.mochat.logic.http.AuthControllerTest \
  --tests com.github.lystran.mochat.logic.http.AuthControllerOfflineReplayHttpTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageSendPolicyGatewayTest \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  :api-service-app:test \
  --tests com.github.lystran.mochat.apiservice.ApiServiceGrpcWiringTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest \
  --rerun-tasks
```
- 结果：
  - `BUILD SUCCESSFUL`

## `6.1` 的真实目标
- 只完成：
  - 把 MQ consumer 和持久化事务链路迁移到 `persistence-service`
- 不要推进：
  - `6.2`
  - `6.3`
  - `6.4`
  - `7.x`

## 必须保持的已完成边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `4.3` 已把消息发送前置校验收敛到 `api-service` 内部接口；不要让 `message-service` 重新直接摸仓储
- `4.4` 已确认 history read-side 继续归属 `api-service`；不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`
- `5.3` 的“首次 route 解析 + 定点投递 + 四态映射”不要推翻
- `5.4` 的“失败后 refresh route 一次，仍失败则 enqueue offline queue”不要回退
- `5.5` 已确认：
  - `SEND_ACK` 只表示 MQ 接收成功
  - `SEND_ACK` 不表示 DB 已提交
  - offline replay payload 必须继续是 replayable delivery envelope
- 后续 `6.x` 开始做持久化拆分时，也不要把 sender success、online delivery 或 replay 成功解释成 history 已可见；`/history` 仍然应该读取持久化后真相

## 当前代码现实：`6.1` 的关键现状在哪里

### 1. MQ consumer 和持久化事务链路仍主要挂在 legacy monolith 装配里
- 关键文件：
  - `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
  - `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`
- 当前现状：
  - `MochatRuntimeFactory` 仍然直接装配：
    - `MessageRepository`
    - `ConversationRepository`
    - `GroupMessageCache`
    - `MqConsumer`
    - `DefaultMQPushConsumer`
    - `RocketMqPersistenceConsumer`
  - `PersistenceRuntimeLifecycle` 仍然通过 monolith 配置 `mochat.rocketmq.consumer.enabled` 启动/关闭 MQ consumer
- 这意味着：
  - `6.1` 的主战场不是 `message-service`
  - 而是要把现有 persistence bean graph 从 legacy runtime 抽到 dedicated `persistence-service` runtime

### 2. `persistence-module` 里的核心持久化逻辑其实已经可以复用
- 关键文件：
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- 当前现状：
  - MQ consume + parse + retry 语义已经在 `RocketMqPersistenceConsumer` 里
  - JDBC 事务链路和 post-commit cache 更新已经在 `MqConsumer` 及其测试里
- 这意味着：
  - `6.1` 更像“runtime ownership 迁移 + dedicated wiring”
  - 而不是重写一套全新的持久化实现

### 3. dedicated `persistence-service-app` 现在还基本只是骨架
- 关键文件：
  - `persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplication.java`
  - `persistence-service-app/src/main/resources/application.yml`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
  - `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/PersistenceServiceConfiguration.java`
- 当前现状：
  - 已有 dedicated app 名称与配置命名空间
  - 已有 `queue.consumer-enabled` / `dependencies.*` 配置模型
  - 但还没有 dedicated runtime factory 去 materialize：
    - `DataSource`
    - `Redis`
    - `GroupMessageCache`
    - `MqConsumer`
    - `DefaultMQPushConsumer`
    - `RocketMqPersistenceConsumer`
    - dedicated lifecycle
- 这意味着：
  - `6.1` 的第一步应该是先把 red test 写在 `persistence-service-app` 上，而不是先大范围拆 legacy app

### 4. `5.5` 已经把 MQ 作为 acceptance 与 durable persistence 的异步边界钉住了
- 关键文件：
  - `openspec/changes/decompose-im-into-core-services/specs/mq-persistence-pipeline-and-idempotency/spec.md`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
- 当前现状：
  - sender success ACK 已经严格只跟 MQ publish success 绑定
  - DB commit 和 history 可见性仍留在后续持久化边界
- 这意味着：
  - `6.1` 不需要去改写 `5.5` 的 sender ACK 定义
  - 也不应该把“consumer 已迁移到 persistence-service”扩写成“history 立即可见”

## 下一会话最建议先确认的现状
1. `persistence-service-app` 当前是否还没有 dedicated runtime factory / lifecycle
2. `MqConsumer` / `RocketMqPersistenceConsumer` 现有测试是否已经足够复用，还是需要先补 runtime-level 红测
3. `app` 里的 `MochatRuntimeFactory` / `PersistenceRuntimeLifecycle` 这轮要迁出到什么程度
4. `persistence-service` dedicated runtime 当前需要哪些基础设施 bean 才能把 consumer 跑起来
5. 当前有哪些测试已经在钉“事务提交成功才确认消费，事务失败则 retryable”，哪些还只是模块级测试

## 先做 TDD 时建议优先复用的文件
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- `app/src/test/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycleTest.java`
- `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`

## `6.1` 最值得先补的红测
- dedicated `persistence-service` runtime 能装配真实 MQ consumer bean graph，而不是只有空壳 application/config
- dedicated `persistence-service` runtime 能用自己的配置命名空间控制 consumer enable/disable
- `RocketMqPersistenceConsumer` + `MqConsumer` 在 dedicated runtime 下仍保持：
  - 事务提交成功才确认消费成功
  - 事务失败或异常时消息仍可重试
- 如果 `6.1` 需要调整 legacy app 装配，补 focused test 守住“兼容壳仍可存在，但 dedicated runtime 才是新的 source of truth”

## 建议优先看的文件
- OpenSpec 与边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-1-handoff.md`
- 当前 `6.1` 最相关实现：
  - `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
  - `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`
  - `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/PersistenceServiceConfiguration.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
  - `persistence-service-app/src/main/resources/application.yml`
- 可直接复用的测试 / 真值表：
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
  - `app/src/test/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycleTest.java`
  - `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`

## 下一会话建议验证
- 至少先跑一组 `6.1` focused suite：
```bash
./gradlew :persistence-module:test \
  --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest \
  --tests com.github.lystran.mochat.persistence.MqConsumerTransactionTest \
  --tests com.github.lystran.mochat.persistence.TransactionalPersistenceTest \
  :persistence-service-app:test \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest \
  --rerun-tasks
```
- 如果改动触及 legacy monolith 兼容壳，再补一组更稳妥的：
```bash
./gradlew :persistence-module:test \
  --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest \
  --tests com.github.lystran.mochat.persistence.MqConsumerTransactionTest \
  --tests com.github.lystran.mochat.persistence.TransactionalPersistenceTest \
  :persistence-service-app:test \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest \
  :app:test \
  --tests com.github.lystran.mochat.runtime.PersistenceRuntimeLifecycleTest \
  --tests com.github.lystran.mochat.AppRuntimeAssemblyTest \
  --rerun-tasks
```

## 对下一会话最重要的提醒
- `6.1` 的重点是把 MQ consumer 和持久化事务链路迁到 dedicated `persistence-service` runtime
- `6.1` 的重点不是顺手完成 `6.2 / 6.3 / 6.4`
- 当前最容易踩坑的是：
  - 直接在 `message-service` 或 `api-service` 里继续补持久化逻辑，而不是把 ownership 收口到 `persistence-service`
  - 为了让 `persistence-service` 启动，顺手改坏 `5.5` 的 sender ACK / replay 边界
  - 过早把 history read-side、receipt/state 全量归属、group cache post-commit 语义都一并声称完成
  - 把 legacy monolith compatibility shell 和 dedicated runtime ownership 混成一团，导致谁才是 source of truth 不清楚
