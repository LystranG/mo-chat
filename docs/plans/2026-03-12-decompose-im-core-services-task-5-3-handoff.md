# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 5.3 待执行）

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
- 当前下一个未完成任务：
  - `5.3`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮已经完成的内容

### `5.1` 已完成
- `message-service` 的 `MessageCommandGrpcService` 已不再返回 skeleton ACK，而是把私聊/群聊命令落到真实 `MessageIngestService`
- `MessageIngestService` 现在已承接：
  - 幂等查重与 duplicate 复用
  - `seq` 分配
  - `msgId` 分配
  - MQ 接收成功后的 sender ACK
  - 私聊/群聊命令 payload 重封装
- `MessageServiceGrpcConnectivityTest` 已覆盖：
  - private happy path
  - private duplicate path
  - private payload 解码验证：`recipientUid` / `nonce` / `ciphertext`
  - group happy path
  - group payload 解码验证：`groupId` / `text`

### `5.2` 已完成
- dedicated `message-service` runtime 现在默认通过 `GrpcMessageSendPolicyGateway` 调 `api-service` 内部 gRPC 做发送前置校验
- 为避免 dedicated `message-service` 悄悄回退到单体内仓储型校验：
  - `RepositoryBackedMessageSendPolicyGateway` 已明确禁止在 `micronaut.application.name=message-service` 的 runtime materialize
  - `MessageServiceGrpcWiringTest` 已验证：
    - 默认 dedicated runtime 使用 gRPC policy gateway
    - `api-grpc-enabled=false` 时不会 fallback 到 repository-backed policy gateway
- `5.1 / 5.2` 的 spec review 与 code quality review 都已通过

## `5.3` 的真实目标
- 只完成：
  - 接入 Redis 在线路由解析
  - 接入 `access-gateway` 定点投递
  - 统一处理四态：
    - `DELIVERED`
    - `USER_OFFLINE`
    - `ROUTE_STALE`
    - `WRITE_FAILED`
- 不要推进：
  - `5.4` 的 refresh once + offline queue fallback
  - `5.5` 的 ACK / offline replay 语义收尾
  - `6.x+`
  - `7.x+`

## 必须保持的已完成边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `sessionVersion` / `routeEpoch` fence 语义不能回退
- `4.3` 已把消息发送前置校验收敛到 `api-service` 内部业务接口；不要让 `message-service` 再次直接摸仓储
- `4.4` 已确认 history read-side 继续留在 `api-service`；不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`

## `5.3` 当前已有的半成品

### 1. dispatcher 生产代码已经存在
- 关键文件：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/AccessGatewayDispatchClientFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/CachingAccessGatewayDispatchClientFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
- 当前实现已经做了：
  - 读取 Redis key：`online:user:{uid}`
  - 解析 route payload 中的：
    - `gatewayPod`
    - `connectionId`
    - `sessionId`
    - `sessionVersion`
    - `routeEpoch`
  - 通过 `gatewayPod -> targetAddress` 映射调用 `AccessGatewayDispatchApi.DeliverToConnection`
  - 把 gateway gRPC 结果映射到：
    - `MessageDeliveryStatus.DELIVERED`
    - `MessageDeliveryStatus.USER_OFFLINE`
    - `MessageDeliveryStatus.ROUTE_STALE`
    - `MessageDeliveryStatus.WRITE_FAILED`

### 2. dispatcher 单测也已经存在
- 关键文件：
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
- 当前已覆盖：
  - private delivery 命中 route 后调用 owning gateway stub
  - Redis route 缺失时返回 `USER_OFFLINE`
  - gateway 返回 `ROUTE_STALE` 时映射到 `MessageDeliveryStatus.ROUTE_STALE`
- 我在 2026-03-12 已亲自复跑：
```bash
./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest --rerun-tasks
```
- 结果：
  - `BUILD SUCCESSFUL`

## `5.3` 还不能宣称完成的原因
- 当前虽然已有 dispatcher 半成品，但还没有在本轮被收口为“已完成 `5.3`”
- 主要原因是还缺这几类收尾：
  - 针对 group dispatch 的 focused 测试与必要修正
  - 针对 `WRITE_FAILED` / gateway 返回 `USER_OFFLINE` 的 focused 测试与必要修正
  - dedicated `message-service` runtime 对 dispatcher 的 wiring 证明
  - `5.3` 的 spec review / code quality review / focused verification 结论
- 也就是说：
  - 这不是“从零开始的 `5.3`”
  - 但也不能直接勾选 `5.3`

## 下一会话最建议优先做的事情

### 先做 TDD
- 优先复用并扩展：
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
- 最值得先补的红测：
  - group delivery path
  - gateway 返回 `DELIVERY_STATUS_USER_OFFLINE`
  - gateway 返回 `DELIVERY_STATUS_WRITE_FAILED`
  - `gatewayPod` 没有 targetAddress 映射时的行为
  - dedicated runtime 在 Redis + gateway client 都可用时确实装配 `GrpcMessageRecipientDispatcher`

### 再做最小生产代码修正
- 如果现有 `GrpcMessageRecipientDispatcher` 和 runtime wiring 已满足测试，就尽量少改
- 若测试暴露 group / write-failed / offline / wiring 缺口，再最小修正
- 不要顺手引入：
  - refresh once
  - offline queue enqueue
  - replay / ACK 终态收口

## 建议优先看的文件
- OpenSpec 与边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-11-decompose-im-core-services-task-5-1-to-5-3-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-3-handoff.md`
- `5.3` 相关实现：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/AccessGatewayDispatchClientFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/CachingAccessGatewayDispatchClientFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
- 现成测试资产：
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`

## 下一会话建议验证
- 至少先跑 `5.3` focused suite：
```bash
./gradlew :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest \
  --rerun-tasks
```
- 若 `5.3` 准备勾选，再补更稳妥的一组：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageSendPolicyGatewayTest \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest \
  --rerun-tasks
```

## 对下一会话最重要的提醒
- `5.3` 只收敛“首次 route 解析 + 定点投递 + 四态映射”
- 任何 refresh once / offline queue fallback 都属于 `5.4`
- 不要因为当前已有半成品就直接勾 `5.3`
- 当前最容易踩坑的是：
  - 把 route 读取写死在 `access-gateway-app` 写侧内部实现上
  - 把 `5.4` 的 offline fallback 顺手做进去
  - 把 `5.3` 和 `history read-side` / `session authority` 边界搅混

