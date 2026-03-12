# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 6.3 待执行）

## 当前状态
- 已完成并勾选：
  - `3.1` ~ `6.2`
- 当前下一个未完成任务：
  - `6.3`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮刚完成的内容
- `6.2` 已完成并同步到：
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
- dedicated `persistence-service` 现在是默认 persistence owner：
  - `ReceiptConversationStateStore` 契约已迁到 `message-module`
  - `JdbcReceiptConversationStateStore` 已迁到 `persistence-module`
  - dedicated `persistence-service` 会和 `MessageRepository`、`ConversationRepository`、`GroupMessageCache`、`MqConsumer`、`RocketMqPersistenceConsumer` 一起装配
- dedicated `api-service` / `message-service` 不再 materialize receipt/state fallback owner
- legacy monolith `app` 改为显式 opt-in compatibility shell：
  - `mochat.legacy.persistence.enabled=false`（默认）
  - `mochat.message-service.inbound-consumer.enabled=false`（默认）
  - 只有显式打开时才恢复 `InboundMessageConsumer -> ReceiptService -> JdbcReceiptConversationStateStore`

## 本轮 fresh 验证
- 已通过：
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
- 也已通过：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.JdbcReceiptConversationStateStoreTest \
  --tests com.github.lystran.mochat.logic.chat.ReceiptServiceTest \
  :api-service-app:test \
  --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  --rerun-tasks
```

## `6.3` 的真实目标
- 只完成：
  - 校验 MQ 重试与数据库幂等行为，确保重复消费不会生成重复消息事实
- 不要推进：
  - `6.4`
  - `7.x`

## 必须保持的边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `4.3` 的发送前置校验仍留在 `api-service` 内部接口
- `4.4` history read-side 仍归 `api-service`；不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`
- `5.3` / `5.4` 的 route 解析、定点投递、refresh-once、offline queue 边界不要回退
- `5.5` 保持：
  - `SEND_ACK` 只表示 MQ 接收成功
  - `SEND_ACK` 不表示 DB 已提交
  - offline replay payload 继续是 replayable `ChatMessageDelivery` envelope
- `6.2` 已完成的是 ownership 收口，不要把 `6.3` 写成重新调整 ownership
- 不要提前声称“实时先送达、历史稍后可见”已经验证完成；那是 `6.4`

## 进入 `6.3` 前最重要的代码现实

### 1. 现有数据库已经具备可用于幂等判断的唯一约束
- `messages.msg_id` 是主键
- `messages` 还有：
  - `UNIQUE (conversation_id, seq)`
  - `UNIQUE INDEX messages_sender_client_msg_uniq (sender_uid, client_msg_id)`
- 见：
  - `persistence-module/src/main/resources/db/migration/V1__phase1.sql`

### 2. 当前 `MqConsumer` 还没有把“重复消费”解释成幂等成功
- `MqConsumer.persistMessage(...)` 现在是：
  - 事务内直接 `messageRepository.insert(...)`
  - 然后 `conversationRepository.updateLatestState(...)`
  - 任何 `SQLException` / `RuntimeException` 都回滚并向上抛
- 见：
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`

### 3. 当前 RocketMQ consumer 对持久化异常一律返回 retry
- `RocketMqPersistenceConsumer.consumeMessage(...)` 当前在 `messagePersistencePort.persist(...)` 抛错时返回：
  - `SUSPEND_CURRENT_QUEUE_A_MOMENT`
- 这意味着如果“重复消费命中唯一键冲突”仍被当成普通 `SQLException`，当前语义很可能是：
  - 已持久化事实存在
  - 但 MQ 仍持续重试
- 见：
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`

### 4. 当前测试更多覆盖事务顺序和 post-commit cache，不等于 `6.3` 已完成
- 已有测试主要证明：
  - 事务提交前后顺序正确
  - 失败会 rollback
  - group cache 只在 commit 后 best-effort 更新
  - malformed payload / persistence failure 会触发 MQ retry
- 但还没有 focused 证明：
  - 重复消费时不会产生第二条消息事实
  - 重复消费时 conversation state 不会被错误推进
  - 重复消费时 MQ 最终会被解释为幂等成功而不是无休止 retry

## 下一会话最建议先补的红测
- `TransactionalPersistenceTest`
  - 同一条 `MessageAcceptedEvent` / `PersistedMessage` 重放两次时，`messages` 表仍只有一条事实
  - `conversations.latest_seq/latest_message_time` 不会因为重复消费被错误推进
  - 如涉及 group message，post-commit cache 不应因 duplicate 造成“第二次新增事实”
- `MqConsumerTransactionTest`
  - duplicate 命中唯一键约束时，应区分“幂等已存在”与“真正失败”
  - 幂等分支不应 rollback 成系统性失败，也不应制造第二次 state 推进
- `RocketMqPersistenceConsumerTest`
  - 当 persistence 层识别为“已持久化的重复消息”时，consumer 应返回 success，而不是 `SUSPEND_CURRENT_QUEUE_A_MOMENT`

## 建议优先看的文件
- OpenSpec / 文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-1-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-2-handoff.md`
- 当前 `6.3` 最相关实现：
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`
  - `persistence-module/src/main/resources/db/migration/V1__phase1.sql`
- 当前 `6.3` 最相关测试：
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`

## 下一会话建议验证
- 至少先跑：
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
- 如果改动重新触及 legacy shell，再补：
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
- `6.3` 的重点是“重复消费下的 durable fact 幂等”，不是再做一次 `6.2` ownership 收口
- 不要把 `message-service` 的 5 分钟 Redis acceptance idempotency，和 `persistence-service` 的 MQ replay / DB durable idempotency 混成一件事
- 不要为了让 MQ retry 停止而顺手改坏 `5.5` 的 sender ACK / replay 边界
- 不要把 `6.4` 的最终一致性窗口提前声称完成
- 不要回滚当前 dirty worktree
