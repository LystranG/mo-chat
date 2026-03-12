# OpenSpec `decompose-im-into-core-services` 交接（2026-03-10）

## 当前状态
- 已完成并勾选：`3.1`
- `3.1` 已完成 reviewer loop：
  - spec review：PASS
  - code quality review：PASS
- `3.1` 已 fresh 验证通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`

## `3.1` 已落实的边界
- `access-gateway-app` 已成为 TCP 接入运行时 owner，装配了 `NettyChatServer`、`OutboundEventSubscriber`、`UserChannelDirectory`、TLS、Redis `EventBus` 适配与 gateway 本地 drop/no-op `OfflineQueue`。
- bind-time session 解析已通过 `api-service` gRPC stub 接入，不再依赖单体本地组合。
- 连接层对同一 channel 的同 `sessionId` 只在首次 bind 或 sessionId 变化时做远程解析。
- bind-time session 解析已移出 Netty I/O 线程，改为专用 executor 执行。
- 异步解析已补齐两层可靠性边界：
  - executor 使用有界队列 + `AbortPolicy`
  - 单连接 pending backlog 有上限，超限 fail-fast
- `3.1` 没有越界到 `3.2+` / `5.3+` / `5.4`：
  - 还没有写 Redis 在线 route
  - 还没有踢旧连接
  - 还没有 route stale 自杀
  - 还没有 drain
  - 还没有 message-service 侧 offline fallback

## 下一步 OpenSpec 任务
- 只从 `3.2` 开始，不要跳到 `4.x+`
- `3.2`：实现 bind 成功后写入 Redis 在线路由记录，记录 `gatewayPod`、`connectionId`、`sessionId`、`sessionVersion`、`routeEpoch` 和租约信息

## 重要约束
- 当前 worktree 是脏的，包含完整 `3.1` 未提交改动；不要回滚。
- 尤其保留这些已完成文件的当前状态：
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `access-gateway-app/**`
  - `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`
  - `connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java`
  - `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
  - `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/AccessGatewayServiceConfiguration.java`
  - `common/src/main/java/com/github/lystran/mochat/common/session/SessionResolutionException.java`
- 下个会话继续沿用流程：
  - `using-superpowers`
  - `test-driven-development`
  - `subagent-driven-development`
  - 阶段收尾用 `verification-before-completion`
- 现阶段不需要把本地 K8s 当作前置环境；跨 pod / rollout 级验证在 `7.1`、`7.3`。

## 建议执行顺序
1. 先读取 OpenSpec 文档与当前 `tasks.md`，确认只做 `3.2`。
2. 检查当前未提交 diff，明确这是 `3.1` 完成态，不要重做。
3. 严格 TDD 从 `3.2` 开始：先失败测试，再实现，再跑绿。
4. `3.2` 完成后先过 spec review，再过 code quality review，再做 fresh 验证。

## 新会话建议技能
- `using-superpowers`
- `openspec-apply-change`
- `test-driven-development`
- `subagent-driven-development`
- `verification-before-completion`
- 如收到 reviewer 反馈，再用 `receiving-code-review`
