# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 3.5 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`、`3.5`
- `3.5` 已完成 reviewer loop：
  - spec review：PASS
  - code quality review：PASS
- `3.5` 已 fresh 验证通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`
- `openspec instructions apply --change "decompose-im-into-core-services" --json` 当前进度：
  - `13/31` tasks complete
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`

## `3.5` 已落实的边界
- `access-gateway` 现在支持：
  - `mochat.access-gateway.drain.enabled`
  - `mochat.access-gateway.drain.grace-period`
- gateway 进入 drain 后会拒绝新的 bind ownership，包括：
  - 新连接首次 bind
  - 已连接 channel 的 rebind
  - 异步 session resolve / route write 途中才进入 drain 的竞态场景
- 若 drain 发生在异步 bind 进行中，`SessionBindingHandler` 会在多个 fence 点阻止新 ownership 完成；若 route 已部分写入，会沿用现有清理路径回滚，不回退 `3.4` 语义。
- 已绑定连接在 grace 期间继续服务，不会被立即踢下线。
- grace 结束后，`GatewayDrainManager` 会遍历本地 bound channel，并通过 `DRAIN_GRACE_EXPIRED_EVENT` 复用 `3.4` 的 timeout 清理路径：
  - 先撤销本地 owner 可见性
  - 再关闭 channel
  - 再异步 best-effort 清理 Redis route
- `3.5` 没有扩 scope 到：
  - live migration
  - 跨 pod 接管
  - rollout / reconnect 集成验证（那是后续 `7.3`）

## 下一步 OpenSpec 任务
- 若继续按 `tasks.md` 顺序推进，只从 `4.1` 开始，不要回到 `3.5`，也不要跳到 `4.2+` / `5.x+`
- `4.1`：把登录、session 签发与 session authority 职责集中到 `api-service`
- 虽然 `7.3` 也和 drain 相关，但它属于后续集成验证，不是当前默认下一步

## `4.1` 建议优先读取的文档
- `openspec/changes/decompose-im-into-core-services/proposal.md`
- `openspec/changes/decompose-im-into-core-services/design.md`
- `openspec/changes/decompose-im-into-core-services/tasks.md`
- `openspec/changes/decompose-im-into-core-services/specs/login-session-and-user-bootstrap/spec.md`
- `docs/architecture/decompose-im-into-core-services-skeleton.md`
- `docs/plans/2026-03-10-decompose-im-core-services-task-3-1-handoff.md`
- `docs/plans/2026-03-11-decompose-im-core-services-task-3-3-handoff.md`
- `docs/plans/2026-03-11-decompose-im-core-services-task-3-4-handoff.md`
- 本文档

## `4.1` 建议优先查看的实现入口
- `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/ApiServiceApplication.java`
- `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GrpcSessionResolver.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/service/SessionServiceTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceGrpcWiringTest.java`

## 当前 worktree 约束
- 当前 worktree 是脏的，包含完整 `3.1`、`3.2`、`3.3`、`3.4`、`3.5` 未提交改动；不要回滚
- 不要用破坏性 git 命令
- 当前阶段不需要把本地 K8s 当作前置条件
- 若继续实现后续 task，仍需遵守：
  - `using-superpowers`
  - `openspec-apply-change`
  - `test-driven-development`
  - `subagent-driven-development`
  - 阶段收尾用 `verification-before-completion`

## 本轮已经确认通过的验证
- focused 验证已覆盖：
  - drain 入口拒绝新 bind ownership
  - grace 期间保留存量连接
  - grace 结束后主动断开并清理 route
  - 异步 bind 途中进入 drain 时不会让新 ownership 落地
- fresh 全量基线已通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`

## 额外提醒
- 当前已知一个非阻塞测试缺口，但 reviewer 未将其判为 finding：
  - 还没有单独把 `GatewayDrainManager.startDrain()` 的真实 scheduler 到期路径做成专门单测
  - 当前只通过手动 `closeBoundConnectionsNow()` 验证 grace 收尾语义
  - 不要在 `4.1` 之前因为这个缺口回头扩 scope，除非用户明确要求
