# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 4.1 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`、`3.5`、`4.1`
- `4.1` reviewer loop 已完成：
  - spec review：PASS
  - code quality review：PASS
- `4.1` fresh 验证已通过：
  - `./gradlew :logic-module:test :api-service-app:test :connection-module:test :access-gateway-app:test --rerun-tasks`
- `openspec instructions apply --change "decompose-im-into-core-services" --json` 当前进度：
  - `14/31` tasks complete
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`

## `4.1` 最终落实的语义边界
- `api-service` MUST issue sessions and MUST persist authoritative session records in Redis。
- `api-service` 或 `access-gateway` 的本地 cache 只能是非权威加速层；session authority 必须落到 `api-service` 暴露的 Redis-backed state。
- gateway bind 必须通过 `api-service` 做 authority 校验。
- unbound / invalid / expired / replaced session 的 chat 请求必须被拒绝。
- `sessionVersion` 必须能 fence stale connection / stale delivery。
- 最终 reviewer loop 把 authority 模型收紧为 fail-closed：
  - active-session pointer 缺失或与当前 record 不匹配时，不再把 session 当成可继续使用的本地接受态，而是按非激活态处理。

## `4.1` 关键落地

### `SessionService`
- 文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/service/SessionServiceTest.java`
- 关键点：
  - Redis authoritative session record 成为单一真相源。
  - 并发签发下只有 active pointer 与 `sessionId + sessionVersion` 全匹配的 record 才会被判为 `ACTIVE`。
  - winner revoke 后 loser 不会因为 pointer 丢失重新变成 `ACTIVE`。

### `SessionBindingHandler`
- 文件：
  - `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
  - `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- 关键点：
  - 已绑定连接上的后续 chat 不再只信本地绑定状态，而是重新回查 authority。
  - heartbeat renew 也不再只信本地 owner；authority 不再承认该 session 时会清绑定并关闭旧连接。
  - async pending-resolution 队列不再把某次 trusted binding 误复用于另一个 `sessionId` 的排队消息。

### `AccessGatewayInternalGrpcService`
- 文件：
  - `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/grpc/AccessGatewayInternalGrpcService.java`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
- 关键点：
  - targeted delivery 除本地 `sessionId` / `sessionVersion` / `routeEpoch` fence 外，还会再次做 authority revalidation。
  - authority 不再承认该 session 时返回 `ROUTE_STALE`，不再误报 `DELIVERED`。
  - 为打断测试专用 bean 构造环，`SessionResolver` 注入改成惰性 provider 获取。

## 已通过的验证
- focused suite：
  - `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.SessionServiceTest.concurrentIssuanceDoesNotAllowReplacedSessionToBecomeActiveAfterCurrentSessionRevocation :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest --tests com.github.lystran.mochat.apiservice.ApiServiceGrpcConnectivityTest --tests com.github.lystran.mochat.apiservice.ApiServiceSessionAuthorityIntegrationTest :connection-module:test --tests com.github.lystran.mochat.connection.SessionBindingHandlerTest :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest`
- fresh：
  - `./gradlew :logic-module:test :api-service-app:test :connection-module:test :access-gateway-app:test --rerun-tasks`
- 两轮验证最终结果均为 `BUILD SUCCESSFUL`。

## 下一步 OpenSpec 任务
- 若继续按 `tasks.md` 顺序推进，只从 `4.2` 开始。
- 不要回头重做 `4.1` reviewer/fresh，也不要跳到 `4.3+`、`5.x+` 或 `7.3`。
- `4.2` 的范围是：
  - 为 session 引入 `sessionVersion` 语义，并让内部 session 解析接口返回足够的栅栏信息。
- 这里的 `4.2` 不应回退 `4.1` 已经落地的 authority 语义；若发现文档与代码命名有重叠，应按 OpenSpec 语义做精读后再收敛实现范围。

## `4.2` 建议优先读取的文档
- `openspec/changes/decompose-im-into-core-services/proposal.md`
- `openspec/changes/decompose-im-into-core-services/design.md`
- `openspec/changes/decompose-im-into-core-services/tasks.md`
- `openspec/changes/decompose-im-into-core-services/specs/login-session-and-user-bootstrap/spec.md`
- `openspec/changes/decompose-im-into-core-services/specs/transport-and-connection-lifecycle/spec.md`
- `docs/architecture/decompose-im-into-core-services-skeleton.md`
- `docs/plans/2026-03-10-decompose-im-core-services-task-3-1-handoff.md`
- `docs/plans/2026-03-11-decompose-im-core-services-task-3-3-handoff.md`
- `docs/plans/2026-03-11-decompose-im-core-services-task-3-4-handoff.md`
- `docs/plans/2026-03-11-decompose-im-core-services-task-3-5-handoff.md`
- 本文档

## 当前 worktree 约束
- 当前 worktree 很脏，包含 `3.1 / 3.2 / 3.3 / 3.4 / 3.5 / 4.1` 的未提交改动；不要回滚。
- 不要使用破坏性 git 命令。
- 当前阶段不需要本地 K8s。
- 若继续推进后续 task，仍需遵守：
  - `using-superpowers`
  - `test-driven-development`
  - `subagent-driven-development`
  - 收尾用 `verification-before-completion`
