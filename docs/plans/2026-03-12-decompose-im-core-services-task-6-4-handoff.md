# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 6.4 待执行）

## 当前状态
- 已完成并勾选：
  - `3.1` ~ `6.3`
- 当前下一个未完成任务：
  - `6.4`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮刚完成的内容

### `6.3` 已完成
- `persistence-service` 现在会把重复 MQ 消费拆成两类处理：
  - `MessageRepository` 在 `messages` 唯一键冲突后按 `msg_id` 回读现有 durable fact
  - 只有字段完全一致时才判定为 `DURABLE_DUPLICATE`
  - `MqConsumer` 对这类重复消费按成功 no-op 处理，不再二次推进 `conversations.latest_seq/latest_message_time`
  - group cache 也不会因 duplicate replay 重复触发
- 对于“同 `msg_id` 但 durable fact 不一致”的真实冲突：
  - 现在会显式抛出 `DurableMessageConflictException`
  - 但 `RocketMqPersistenceConsumer` 仍把它保留在现有 retry 语义里
  - 本轮没有把真实冲突伪装成幂等成功
- 为避免新测试在无 Docker 环境下直接失败：
  - `MqConsumerTransactionDockerTest`
  - `RocketMqPersistenceConsumerDockerTest`
  已拆成独立 `@Testcontainers(disabledWithoutDocker = true)` 测试类
- 本轮已同步到：
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`

## 本轮关键文件
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/DurableMessageConflictException.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionDockerTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerDockerTest.java`

## 本轮 fresh 验证
本轮末尾我亲自 fresh 复跑的验证命令是：
```bash
./gradlew :persistence-module:test \
  --tests com.github.lystran.mochat.persistence.TransactionalPersistenceTest \
  --tests com.github.lystran.mochat.persistence.MqConsumerTransactionTest \
  --tests com.github.lystran.mochat.persistence.MqConsumerTransactionDockerTest \
  --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest \
  --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerDockerTest \
  :persistence-service-app:test \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest \
  --rerun-tasks
```
- 结果：
  - `BUILD SUCCESSFUL`

## `6.4` 的真实目标
- 只完成：
  - 补充“实时投递先完成、历史稍后可见”的最终一致性验证用例
- 不要推进：
  - `7.x`

## 必须保持的边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `4.3` 的发送前置校验仍留在 `api-service` 内部接口
- `4.4` history read-side 仍归 `api-service`
  - 不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`
- `5.3` / `5.4` 的 route 解析、定点投递、refresh-once、offline queue 边界不要回退
- `5.5` 保持：
  - `SEND_ACK` 只表示 MQ 接收成功
  - `SEND_ACK` 不表示 DB 已提交
  - offline replay payload 继续是 replayable `ChatMessageDelivery` envelope
- `6.3` 已完成的是 MQ retry / DB durable idempotency
  - 不要把 `6.4` 做成继续扩写 duplicate / poison-message 分类
- `6.4` 的重点是“语义窗口验证”
  - 不是重新调整 ownership
  - 不是提前推进跨 pod / drain / rollout / route stale 的 `7.x`

## 进入 `6.4` 前最重要的代码现实

### 1. history read-side 的 runtime ownership 已经钉住
- `api-service` dedicated runtime 仍承载 `/history` 和 `/conversations/{id}/state`
- `message-service` / `persistence-service` dedicated runtime 仍不承载这些 read-side bean / type
- 见：
  - `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceHistoryOwnershipTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`

### 2. 写侧 success 语义已经和 DB commit 脱钩
- `SEND_ACK` 只表示 MQ publish success，不等于 DB commit
- 登录后的 offline replay 触发失败也不会影响登录本身成功返回
- 见：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`

### 3. `6.3` 已证明 durable fact 幂等，但没有证明“可见性窗口”
- 当前已证明：
  - duplicate replay 不会生成第二条 durable message fact
  - duplicate replay 不会错误推进 conversation state
  - genuine durable conflict 仍保留现有 retry 语义
- 当前还没有 focused 证明：
  - 实时投递/写侧接受已经发生时，history 仍可能暂时读不到该消息
  - 持久化提交完成后，history 才能看到该消息与对应 conversation state

### 4. `6.4` 更像验证补齐，而不是新的服务拆分
- OpenSpec 已明确：
  - `persistence-service` 的 durable write 才是 persistence truth
  - online delivery 与 sender success 不应被解释为 history 已可见
- 这轮更应该优先补 focused tests / integration-style tests
- 不要顺手去改写生产职责边界

## 下一会话最建议先补的红测
- 一个 focused 用例证明：
  - 写侧接受/实时投递已经完成
  - 但 `api-service` 的 history query 在持久化提交前仍读不到该消息
- 一个 focused 用例证明：
  - 在模拟 persistence commit / history repository 刷新后
  - 同一条消息随后能被 `api-service` history query 读到
- 一个 focused 用例证明：
  - conversation latest state 的可见性也遵循同样窗口
  - 不是只有 message list 延迟、而 state 已提前“穿透”

## 先做 TDD 时建议优先复用的文件
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceHistoryOwnershipTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerTest.java`

## 建议优先看的文件
- OpenSpec / 文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-3-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-4-handoff.md`
- 当前 `6.4` 最相关实现 / 测试：
  - `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceHistoryOwnershipTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerTest.java`

## 下一会话建议验证
- 至少先跑一组 focused suite：
```bash
./gradlew :api-service-app:test \
  --tests com.github.lystran.mochat.apiservice.ApiServiceHistoryOwnershipTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceHistoryOwnershipTest \
  :persistence-service-app:test \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest \
  --rerun-tasks
```
- 如果 `6.4` 改动碰到登录后 replay / read-side 可见性桥接，再补：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.http.AuthControllerTest \
  :api-service-app:test \
  --tests com.github.lystran.mochat.apiservice.ApiServiceHistoryOwnershipTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceHistoryOwnershipTest \
  :persistence-service-app:test \
  --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest \
  --rerun-tasks
```

## 对下一会话最重要的提醒
- `6.4` 的重点是“验证最终一致性窗口”，不是补一个新的运行时 owner
- 不要把 `SEND_ACK` / online delivery / login replay success 重新解释成“历史已可见”
- 不要顺手去处理 `DurableMessageConflictException` 的 poison-message 策略；那不是本轮 OpenSpec 目标
- 不要提前推进 `7.x`
- 不要回滚当前 dirty worktree
