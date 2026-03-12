# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 3.4 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`
- `3.4` 已完成 reviewer loop：
  - spec review：PASS
  - code quality review：PASS
- `3.4` 已 fresh 验证通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`
- `openspec instructions apply --change "decompose-im-into-core-services" --json` 当前进度：
  - `12/31` tasks complete
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`

## `3.4` 已落实的边界
- heartbeat ACK 现在只会为“仍持有当前 route 的已绑定连接”续租 Redis 在线 route 的 TTL 与 `leaseExpiresAtEpochMilli`
- 若 heartbeat 续租发现 Redis 当前 route 的 `sessionVersion` 或 `routeEpoch` 已不匹配，旧连接会清理本地 ownership 并自杀关闭
- heartbeat timeout 到达时，gateway 现在会先同步撤销本地 owner 可见性并关闭 channel；Redis `clearRoute()` 改为异步 best-effort 收尾，不再作为“本地下线”的前置条件
- 异步 heartbeat 续租的 stale 结果回到 event loop 时，会再次按当前 binding 做 fence 校验，不会误杀已经完成的新 bind
- `3.3` 既有语义保持不变：
  - 新 bind 不因 replacement 元数据补读失败而回滚
  - kick 失败不回滚新 bind
  - 旧连接继续由 stale-route fencing / 心跳自杀路径兜底

## 下一步 OpenSpec 任务
- 只从 `3.5` 开始，不要跳到 `4.x+`
- `3.5`：实现 gateway drain 模式：拒绝新归属、保留存量连接、宽限期后主动断开

## `3.5` 必须遵守的 OpenSpec 语义
- 以这些文件为准，不要跳过 OpenSpec：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-10-decompose-im-core-services-task-3-1-handoff.md`
  - `docs/plans/2026-03-11-decompose-im-core-services-task-3-3-handoff.md`
  - 本文档
- 当前直接相关的 spec 约束：
  - draining gateway MUST reject new bind ownership
  - draining gateway MUST continue serving already bound connections during grace period
  - grace period 结束后 MUST close remaining bound connections so clients reconnect elsewhere
  - 这是 drain-and-reconnect，不是 live migration；不要把 scope 扩成连接迁移或跨 pod 接管
- `3.5` 实现时不要回退 `3.4`：
  - heartbeat 续租只针对当前 owner
  - stale-route 自杀语义保留
  - timeout 先撤销本地 owner 可见性，再异步 best-effort 清旧 route

## 建议优先查看的实现入口
- `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayRuntimeFactory.java`
- `access-gateway-app/src/main/resources/application.yml`
- `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/AccessGatewayServiceConfiguration.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`

## 当前 worktree 约束
- 当前 worktree 是脏的，包含完整 `3.1`、`3.2`、`3.3`、`3.4` 未提交改动；不要回滚
- 不要用破坏性 git 命令
- 当前阶段不需要把本地 K8s 作为前置条件
- 用户要求严格 TDD，且每个 task 完成后都要先过 spec review，再过 code quality review，再做 fresh 验证

## 本轮已经确认通过的验证
- `3.4` focused suite 已通过，覆盖：
  - route lease renewal
  - stale-route 自杀
  - timeout 先撤销本地 owner 可见性
  - 异步 stale renew 结果不会误杀新 bind
- fresh 全量基线已通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`

## 额外提醒
- 用户消息里写的骨架文档路径少了 `into`，当前 worktree 真实存在的文件是：
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
- 当前已知一个非阻塞改进点仍未处理，但不要在 `3.5` 之前扩 scope：
  - same-pod local kick 现在可能既从 Redis route writer 直接触发，也可能从 replacement handler 再触发一次；现状幂等、非阻塞，可后续再收敛
