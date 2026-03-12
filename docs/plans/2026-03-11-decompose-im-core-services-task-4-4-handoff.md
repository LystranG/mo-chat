# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 4.4 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`、`3.5`、`4.1`、`4.2`、`4.3`、`4.4`
- 当前下一个未完成任务：`5.1`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含其他任务的未提交改动；不要回滚

## `4.4` 最终落实的语义边界
- `api-service` 继续拥有 history read-side：
  - `/history`
  - `/conversations/{id}/state`
- `4.4` 没有回退 `4.1 / 4.2 / 4.3` 已完成语义：
  - `api-service` 仍然是 session authority
  - `sessionVersion` fence 与 `resolveAuthority(...)` 抽象保持不变
  - 消息发送前置校验仍然只在 `api-service` 内部接口侧收敛，没有提前推进 `5.2`
- `4.4` 明确补充了 OpenSpec / 架构要求中的写读边界：
  - 后续 `5.1 / 5.5` 完成后，`message-service` 的 sender ACK / online delivery 语义不能被解释为历史已经可见
  - history query 在目标架构里仍应读取持久化后的消息事实与 conversation state
  - 因而“实时先送达、历史稍后可见”是后续 `5.x / 6.x` 迁移后也必须保留的语义窗口，而不是 `4.4` 已经完成的端到端实现事实
- 本轮没有扩到：
  - `5.1 / 5.2+` 的消息摄入迁移或跨服务接入
  - `6.x` 的持久化服务拆分
  - `7.3`

## `4.4` 关键落地

### `api-service` dedicated runtime ownership 测试
- 文件：
  - `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
- 关键点：
  - 通过 EmbeddedServer 启动 `api-service` dedicated runtime。
  - 使用 test-only `RedisCommands` stub 保留真实 `SessionService` 行为，不接外部 Redis。
  - 使用 persisted-history / conversation-state stub 证明 `HistoryController` 与 `ConversationController` 会在 `api-service` runtime 里 materialize。
  - 直接通过 HTTP 调用 `/history` 与 `/conversations/{id}/state`，证明入口确实留在 `api-service`。

### `message-service` / `persistence-service` 不承载 history read-side
- 文件：
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceHistoryOwnershipTest.java`
  - `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceHistoryOwnershipTest.java`
- 关键点：
  - `message-service` 测试证明当前 dedicated runtime 仍会装配命令侧 `MessageCommandGrpcService`，但不会 materialize `HistoryController` / `ConversationController`。
  - `persistence-service` 测试进一步证明当前 runtime classpath 本身不携带这些 history read-side type，不存在“顺手承载查询入口”的问题。

### 文档边界同步
- 文件：
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
- 关键点：
  - skeleton 现在明确写出 history query 仍归属 `api-service`。
  - skeleton 同步补了 ACK / online delivery 与 durable visibility 的目标架构差异，但不再把它表述成当前 `5.x / 6.x` 已落地实现。
  - `tasks.md` 已勾选 `4.4` 并记录 focused 验证命令。

## 已通过的验证
- TDD red：
  - `./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceHistoryOwnershipTest`
  - 初始失败原因：`ApiServiceHistoryOwnershipTest` 断言 `/history` 应返回 `200`，实际返回 `404`，说明在只补 session stub 时，`api-service` runtime 还没有把 history query 入口装配出来。
- focused 绿测：
  - `./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceHistoryOwnershipTest :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceHistoryOwnershipTest :persistence-service-app:test --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceHistoryOwnershipTest`
  - 结果：`BUILD SUCCESSFUL in 5s`

## 自审结论
- 本轮改动只落在允许写入的测试目录与文档文件，没有回滚或整理 worktree 里的其他脏改动。
- 没有引入 `message ingest / MQ / persistence` 拆分实现，也没有触碰 `7.3`。
- 当前剩余风险：
  - `4.4` 证明的是 dedicated runtime ownership 与边界文档，不是 `6.x` 完成后的最终一致性集成验证；“实时先送达、历史稍后可见”的端到端验证仍留给后续 `5.x / 6.x` 任务。

## 下一步 OpenSpec 任务
- 若继续顺序推进，从 `5.1` 开始。
- 不要回头改写 `4.4` 去提前接入 `message-service` 写路径或 `persistence-service` durable pipeline。
