# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 6.2 待执行）

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
  - `6.1`
- 当前下一个未完成任务：
  - `6.2`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮已经完成的内容

### `6.1` 已完成
- `persistence-service-app` 已不再只是 application/config 空壳
- dedicated `persistence-service` runtime 现已拥有真实 MQ consumer bean graph：
  - `MessageRepository`
  - `ConversationRepository`
  - `GroupMessageCache`
  - `MqConsumer`
  - `DefaultMQPushConsumer`
  - `RocketMqPersistenceConsumer`
- dedicated `persistence-service` runtime 现已拥有独立 lifecycle：
  - `PersistenceServiceRuntimeLifecycle`
  - 使用 `mochat.persistence-service.queue.consumer-enabled` 控制 consumer 启停
- dedicated app 的共享基础设施默认配置已补齐：
  - `mochat.flyway.locations`
  - `mochat.postgres.url / username / password`
  - `mochat.redis.uri`
  - `mochat.rocketmq.name-server / consumer-group / topic`
- `persistence-module` 既有事务与消费语义没有被重写，而是被 dedicated runtime 复用
- legacy monolith `app` 的兼容壳本轮保持不动，没有抢跑清理

### 本轮关键文件
- `persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeFactory.java`
- `persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeLifecycle.java`
- `persistence-service-app/src/main/resources/application.yml`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- `openspec/changes/decompose-im-into-core-services/tasks.md`
- `docs/architecture/decompose-im-into-core-services-skeleton.md`

## 本轮 fresh 验证
本轮末尾我亲自 fresh 复跑的验证命令是：
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
- 结果：
  - `BUILD SUCCESSFUL`

## `6.2` 的真实目标
- 只完成：
  - 让 `persistence-service` 独占 `messages`、`conversations`、receipt/state 推进和群缓存 post-commit 更新
- 不要推进：
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
- `6.1` 已确认 dedicated `persistence-service` runtime 是 MQ consumer / 持久化事务链路的新 source of truth；但这不等于：
  - history read-side 已迁移
  - history 已立即可见
  - `6.2 / 6.3 / 6.4` 已做完

## 当前代码现实：进入 `6.2` 前最需要确认的现状

### 1. dedicated runtime 已建立，但 ownership 语义未必已经“独占”
- `6.1` 解决的是：
  - dedicated `persistence-service` 能装配 consumer + transaction chain
  - dedicated `persistence-service` 能用自己的配置命名空间启停 consumer
- `6.2` 需要进一步回答的是：
  - 哪些对 `messages`、`conversations`、receipt/state、group cache 的写语义仍然停留在 legacy/共享假设里
  - 哪些代码路径虽已复用 `persistence-module`，但 ownership 还没有被明确收口到 dedicated runtime

### 2. `persistence-module` 现有实现与测试仍是最重要的真值表
- 关键实现：
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- 关键测试：
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- `6.2` 很可能仍应以“复用这些真值表 + 只迁 ownership/wiring”为主，而不是重写持久化语义

### 3. legacy monolith compatibility shell 仍然存在
- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`
- 本轮没有动它们，是刻意为之
- 下一轮要先想清楚：
  - `6.2` 是否真的需要动 legacy shell
  - 如果需要，改动应该如何被 focused test 守住
  - 不要把“兼容壳仍可存在”和“dedicated runtime 才是 source of truth”混为一谈

### 4. `history` 仍然留在 `api-service`
- `PersistenceServiceHistoryOwnershipTest` 仍只是在钉住：
  - dedicated `persistence-service` runtime 不承载 history read-side type
- 这条边界不能在 `6.2` 被顺手推翻

## 下一会话最建议先确认的问题
1. 现在有哪些对 `messages` / `conversations` / receipt/state / group cache` 的写路径已经天然在 `persistence-module` 内，哪些还只是“consumer bean graph 可装配”但 ownership 没有被真正声明清楚
2. `MqConsumer.persistMessage(...)` 当前已经覆盖到哪些 post-commit 行为，哪些属于 `6.2` 还需要补充或收口的范围
3. legacy `app` 的 bean graph 是否还会被误认为 persistence ownership 的 source of truth；如果是，怎样用 focused test 把 dedicated runtime 的优先级钉住
4. `6.2` 是否需要调整 `tasks.md` / skeleton 说明中的 ownership 表述，前提是不提前声称 `6.3 / 6.4` 已完成
5. 哪些验证属于 `6.2`，哪些已经越界到 `6.3` 的幂等重试或 `6.4` 的最终一致性窗口

## 先做 TDD 时建议优先复用的文件
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
- `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- `app/src/test/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycleTest.java`
- `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`

## `6.2` 最值得先补的红测
- dedicated `persistence-service` runtime 才是 `messages`、`conversations`、receipt/state 推进和群缓存 post-commit 更新的明确 owner，而不是 legacy monolith runtime
- 如果需要保留 legacy compatibility shell，补 focused test 守住“兼容壳仍可存在，但 ownership 文义与默认装配不再回退”
- 若 `6.2` 涉及 post-commit cache / receipt/state 的更明确归属，补测试区分：
  - 这是 `6.2` 的 ownership 收口
  - 不是 `6.3` 的重复消费幂等
  - 也不是 `6.4` 的“实时先送达、历史稍后可见”最终一致性验证

## 建议优先看的文件
- OpenSpec 与边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-1-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-2-handoff.md`
- 当前 `6.2` 最相关实现：
  - `persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeFactory.java`
  - `persistence-service-app/src/main/java/com/github/lystran/mochat/persistenceservice/runtime/PersistenceServiceRuntimeLifecycle.java`
  - `persistence-service-app/src/main/resources/application.yml`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`
  - `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
  - `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
  - `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`

## 下一会话建议验证
- 至少先跑一组 focused suite：
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
- 如果改动触及 legacy monolith compatibility shell，再补一组更稳妥的：
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
- `6.2` 的重点是 ownership 收口，不是重复完成 `6.1`
- `6.2` 的重点也不是顺手推进 `6.3 / 6.4`
- 当前最容易踩坑的是：
  - 把“dedicated runtime 已存在”误读成“ownership 已经自然独占”
  - 为了收口 ownership，顺手改坏 `5.5` 的 sender ACK / replay 边界
  - 过早把 history read-side、最终一致性、重复消费幂等一并声称完成
  - 对 dirty worktree 进行回滚或清理，破坏此前未提交任务状态
