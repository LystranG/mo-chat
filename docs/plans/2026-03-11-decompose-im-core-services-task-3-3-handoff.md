# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 3.3 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`
- `3.3` 已完成 reviewer loop：
  - spec review：PASS
  - code quality review：PASS
- `3.3` 已 fresh 验证通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`

## `3.3` 已落实的边界
- 新 bind 现在会覆盖 Redis 在线 route，并返回被替换旧 route 的 `gatewayPod`、`connectionId`、`sessionId`、`sessionVersion`、`routeEpoch` 元数据。
- `SessionBindingHandler` 在新 route 持久化成功后触发 best-effort replacement flow。
- 同 pod 旧连接通过本地连接目录按旧 `routeEpoch` 执行 kick；跨 pod 则通过 `KickConnection` gRPC 触发远端 gateway 踢旧连接。
- 对“写入结果不确定但随后确认新 route 已持久化成功”的 confirm-success 路径：
  - 若补读 replacement 元数据成功，则继续携带 `replacedRoute`
  - 若补读失败，则降级为 `replacedRoute=null`
  - 不回滚已经确认成功的新 bind
- `kick` 失败不会回滚新 bind；旧连接的最终清理由后续 stale-route fencing / 心跳自杀路径兜底。

## 下一步 OpenSpec 任务
- 只从 `3.4` 开始，不要跳到 `3.5+` / `4.x+`
- `3.4`：实现心跳续租与“发现 route 已失效则自杀”的逻辑，防止旧连接继续占有在线状态

## `3.4` 必须遵守的 OpenSpec 语义
- 以这些文件为准，不要跳过 OpenSpec：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-10-decompose-im-core-services-task-3-1-handoff.md`
  - 本文档
- 当前直接相关的 spec 约束：
  - heartbeat 只对“仍持有当前 route 的已绑定连接”续租
  - heartbeat ACK 超时后，若该连接仍持有当前 route，则 gateway 需要释放 route、标记离线并关闭 channel
  - 被新连接替换的旧连接在后续 heartbeat 检查或显式 replacement signal 里一旦发现自己已不是当前 owner，必须自杀关闭

## 建议优先查看的实现入口
- `connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/InMemoryUserChannelDirectory.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayRuntimeFactory.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistryTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayOnlineRouteBindingTest.java`
- `service-runtime/src/main/java/com/github/lystran/mochat/runtime/config/AccessGatewayServiceConfiguration.java`

## 当前 worktree 约束
- 当前 worktree 是脏的，包含完整 `3.1`、`3.2`、`3.3` 未提交改动；不要回滚。
- 不要用破坏性 git 命令。
- 当前阶段不需要把本地 K8s 作为前置条件。
- 用户要求严格 TDD，且每个 task 完成后都要先过 spec review，再过 code quality review，再做 fresh 验证。

## 下个会话建议执行顺序
1. 先跑 superpowers 流程：`using-superpowers` -> `test-driven-development` -> `subagent-driven-development`
2. 读取 OpenSpec 文档和 `tasks.md`，确认本轮只做 `3.4`
3. 先写失败测试，再实现心跳续租 / stale-route 自杀逻辑，再把测试跑绿
4. `3.4` 完成后先做 spec review，再做 code quality review，发现问题必须在 `3.4` 内修完
5. 阶段收尾使用 `verification-before-completion`
6. 最终至少执行 fresh 基线：
   - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`

## 本轮已经确认通过的验证
- focused suite 已通过，覆盖 `SessionBindingHandler`、Redis route overwrite / confirm-success、replacement handler、本地 kick 和 gRPC kick 链路
- fresh 全量基线已通过：
  - `./gradlew :connection-module:test :access-gateway-app:test --rerun-tasks`

## 额外提醒
- 用户消息里写的骨架文档路径少了 `into`，当前 worktree 真实存在的文件是：
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
- 当前已知一个非阻塞改进点仍未处理，但不要在 `3.4` 之前扩 scope：
  - same-pod local kick 现在可能既从 Redis route writer 直接触发，也可能从 replacement handler 再触发一次；现状幂等、非阻塞，可后续再收敛
