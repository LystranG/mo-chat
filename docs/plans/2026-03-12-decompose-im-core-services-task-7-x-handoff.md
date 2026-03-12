# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 7.x 待执行）

## 当前状态
- 已完成并勾选：
  - `3.1` ~ `6.4`
- 当前下一个未完成任务：
  - `7.1`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 很脏，包含此前任务未提交改动；不要回滚

## 本轮刚完成的内容
- `6.4` 已完成并同步到：
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
- focused 验证补齐了“实时投递先完成、历史稍后可见”的最终一致性窗口：
  - `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
    - `historyAndConversationStateRemainOnCommittedViewUntilPersistenceCommits()`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
    - `privateSendReturnsAcceptedMetadataAfterRealtimeDispatchAttempt()`
- 这两条测试只补 `6.4` 的 focused 证据：
  - `message-service` 返回 accepted `msgId/seq` 时已经做过 realtime dispatch attempt
  - `api-service` 的 `/history` 与 `/conversations/{id}/state` 仍以后续 persistence commit 为准
- 没有推进：
  - `7.x`
- 没有回退：
  - `4.1 / 4.2 / 4.3 / 4.4 / 5.5 / 6.3`

## 本轮 fresh 验证
已由主会话亲自 fresh 复跑通过：

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

- 结果：
  - `BUILD SUCCESSFUL`

## 下一会话第一件事
- 用户明确要求：
  - **先回答“是否需要人工介入配置环境”**
  - 再决定是否开始 `7.x`
- 不要一进入新会话就直接编码
- 应先给出基于仓库现状的环境判断，至少区分：
  - `7.1 / 7.2` 是否可以先用本地多进程 + shared infra 完成
  - `7.3` 是否更适合或需要 `kind` / 真正的 pod 级环境

## 当前对环境的已知事实
- 仓库当前没有现成的 Kubernetes manifests / Helm chart / `k8s/` 目录
- 当前可直接复用的本地 shared infra 启动方式在：
  - `docs/runbook.md`
- `docs/runbook.md` 当前要求的基础依赖是：
  - PostgreSQL
  - Redis
  - RocketMQ
  - 启动方式：`podman compose up -d`
- 当前四个 dedicated service 的默认配置文件在：
  - `access-gateway-app/src/main/resources/application.yml`
  - `api-service-app/src/main/resources/application.yml`
  - `message-service-app/src/main/resources/application.yml`
  - `persistence-service-app/src/main/resources/application.yml`

## 对 7.x 的环境判断线索
- `7.1` 跨 pod 私聊投递：
  - 逻辑上可先用两套本地 `access-gateway` 进程验证
  - 若要验证真实 pod / service 路径，再考虑 `kind`
- `7.2` 重复登录替换旧连接、route stale、自杀关闭、离线补推：
  - 更像本地多进程 / 多 gateway 实例就能先覆盖的场景
- `7.3` gateway drain / rollout：
  - 更接近真实 pod rollout 语义
  - 若要验证“rollout”而不是单机模拟，`kind` 更合理
- `7.4` 文档：
  - 不需要 `kind`
- `7.5` 独立启动与基线验证：
  - 不强制 `kind`

## 如果下一会话先走本地多进程，至少要准备
- shared infra：
  - PostgreSQL `5432`
  - Redis `6379`
  - RocketMQ `9876 / 10909 / 10911 / 10912`
- 服务实例：
  - `api-service`
  - `message-service`
  - `persistence-service`
  - `access-gateway-a`
  - `access-gateway-b`
- 两个 gateway 需要不同的：
  - `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD`
  - `MOCHAT_ACCESS_GATEWAY_GRPC_PORT`
  - `MOCHAT_ACCESS_GATEWAY_TCP_PORT`
- `message-service` 需要配置：
  - `mochat.message-service.route.gateway-targets`
- `access-gateway` 之间需要配置：
  - `mochat.access-gateway.route.peer-targets`

## 下一会话建议先读
- OpenSpec / 边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
- 最新 handoff：
  - `docs/plans/2026-03-12-decompose-im-core-services-task-6-4-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-7-x-handoff.md`
- 环境与运行：
  - `docs/runbook.md`
  - `access-gateway-app/src/main/resources/application.yml`
  - `api-service-app/src/main/resources/application.yml`
  - `message-service-app/src/main/resources/application.yml`
  - `persistence-service-app/src/main/resources/application.yml`

## 对下一会话最重要的提醒
- 先回答用户：
  - 是否需要人工介入配置环境
  - 是否需要启动 `kind`
- 回答时要区分 `7.1 / 7.2 / 7.3`，不要笼统一句“需要”或“不需要”
- 不要把 `6.4` 的 focused 证据误说成 `7.x` 已有集成验证
- 不要提前声称 `7.x` 已完成
- 不要回滚当前 dirty worktree
