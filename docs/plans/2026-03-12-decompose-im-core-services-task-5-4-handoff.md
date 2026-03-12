# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 5.4 待执行）

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
- 当前下一个未完成任务：
  - `5.4`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮已经完成的内容

### `5.3` 已完成
- `message-service` 的 `GrpcMessageRecipientDispatcher` 已正式接入 Redis 在线路由解析与 `access-gateway` 定点投递
- 当前 dispatch 链路已经做实：
  - 读取 Redis `online:user:{uid}`
  - 解析 `gatewayPod`、`connectionId`、`sessionId`、`sessionVersion`、`routeEpoch`
  - 通过 `gatewayPod -> targetAddress` 调 `AccessGatewayDispatchApi.DeliverToConnection`
  - 把结果统一映射为：
    - `DELIVERED`
    - `USER_OFFLINE`
    - `ROUTE_STALE`
    - `WRITE_FAILED`
  - gateway gRPC 运行时异常现在也会归一化为 `WRITE_FAILED`
- `MessageServiceRuntimeFactory` 已修正 dedicated runtime 下 `MessageRecipientDispatcher` 的候选装配问题：
  - 真实 `GrpcMessageRecipientDispatcher` 现在会在 Redis + gateway dispatch bean 存在时稳定被选中
  - no-op dispatcher 现为 `@Secondary`
- `GrpcMessageRecipientDispatcherTest` 已覆盖：
  - private happy path
  - group dispatch path
  - Redis route 缺失 -> `USER_OFFLINE`
  - gateway 返回 `USER_OFFLINE`
  - gateway 返回 `ROUTE_STALE`
  - gateway 返回 `WRITE_FAILED`
  - `gatewayPod` 缺失 `targetAddress` 映射 -> `WRITE_FAILED`
  - gateway RPC failure -> `WRITE_FAILED`
- `MessageServiceGrpcWiringTest` 已覆盖：
  - dedicated runtime 默认使用 gRPC policy gateway
  - `api-grpc-enabled=false` 时不回退到 repository-backed policy gateway
  - Redis + gateway dispatch bean 可用时确实装配 `GrpcMessageRecipientDispatcher`
- `tasks.md` 已把 `5.3` 勾选并写入完成说明

## `5.4` 的真实目标
- 只完成：
  - “重查路由一次，仍失败则写离线队列”的降级策略
  - 也就是：
    - 首次 targeted delivery 返回 `USER_OFFLINE` / `ROUTE_STALE` / `WRITE_FAILED` 时
    - 最多再做一次 fresh route lookup + targeted delivery
    - 若仍未成功，则 enqueue offline queue
- 不要推进：
  - `5.5` 的 sender ACK / offline replay 语义收尾
  - `6.x+`
  - `7.x+`

## 必须保持的已完成边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `sessionVersion` / `routeEpoch` fence 语义不能回退
- `4.3` 已把消息发送前置校验收敛到 `api-service` 内部业务接口；不要让 `message-service` 再次直接摸仓储
- `4.4` 已确认 history read-side 继续留在 `api-service`；不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`
- `5.3` 已完成的“四态映射 + 首次 route 解析 + 定点投递”不要推翻重做
- `5.4` 只做 refresh once + offline queue enqueue；不要顺手完成 replay side

## 当前代码现实：`5.4` 的关键缺口在哪里

### 1. 首次 dispatch 已完成，但失败结果还没有被上层消费
- 关键文件：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- 当前现状：
  - `GrpcMessageRecipientDispatcher` 已经每次调用都会 fresh 读取 Redis route 并返回四态
  - `MessageIngestService` 当前只是调用：
    - `dispatchPrivate(...)`
    - `dispatchGroup(...)`
  - 但完全没有消费返回的 `MessageDeliveryStatus`
- 这意味着：
  - `5.4` 的核心工作不在 `access-gateway`
  - 而在 `message-service` 的编排层：需要根据失败状态决定“再试一次”还是“enqueue offline queue”

### 2. Offline queue 抽象和 Redis 实现都已经存在，但 `message-service` 还没接入
- 关键文件：
  - `common/src/main/java/com/github/lystran/mochat/common/offline/OfflineQueue.java`
  - `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
  - `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/runtime/ApiServiceRuntimeFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
- 当前现状：
  - `OfflineQueue` 接口已经定义为：
    - `enqueue(long userId, String payload, int maxQueueSize)`
    - `drain(long userId, int maxItems)`
  - `RedisOfflineQueue` 已可直接复用
  - `api-service` 已经装配了 `RedisOfflineQueue`
  - `message-service` runtime 目前还没有自己的 `OfflineQueue` bean
- 这意味着：
  - `5.4` 很可能需要先把 `RedisOfflineQueue` 接到 dedicated `message-service` runtime
  - 但不要因此推进 replay side；当前只需要 enqueue side

### 3. legacy 已有“离线入队 payload”测试资产可参考，但不要被 legacy event 绑定死
- 关键文件：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/OfflineReplayServiceTest.java`
  - `protocol/src/main/proto/mochat/internal/common/v1/common.proto`
- 当前现状：
  - legacy inbound path 已经测试过 offline queue enqueue
  - 现有 `OfflineQueue` payload 本质上只是 `String`
  - legacy 测试里排队的是外部事件格式：`MSG_TYPE|SERIALIZER|BASE64(payload)`
  - 但 OpenSpec design 对 phase 1 的要求是：offline queue entry 应携带足够 delivery envelope 数据，以便未来 replay 时不必立即查 DB
- 对下一会话的建议：
  - 优先遵守 OpenSpec 的“delivery envelope”方向
  - 但不要在 `5.4` 把 replay side 一起做完
  - 如果需要临时字符串封装，也应让它明显面向后续 replay，而不是直接回退到单体 legacy outbound event 假设

### 4. `ReplayOfflineMessages` 仍是 skeleton，这一轮不要把它做实
- 关键文件：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
  - `protocol/src/main/proto/mochat/internal/message/v1/message_service.proto`
- 当前现状：
  - `ReplayOfflineMessages` 仍然返回 skeleton ack
- 这意味着：
  - `5.4` 只需要把 offline queue enqueue side 做实
  - replay / ack 终态 / “SEND_ACK 不代表 DB 已提交”仍留给 `5.5`

## 下一会话最建议优先做的事情

### 先做 TDD
- 优先复用：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceRelationshipAndGroupTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/OfflineReplayServiceTest.java`
- 最值得先补的红测：
  - private path：首次 `ROUTE_STALE` / `USER_OFFLINE` / `WRITE_FAILED`，第二次成功，不应 enqueue offline queue
  - private path：首次失败，第二次仍失败，应 enqueue offline queue 一次
  - private path：首次失败，第二次抛 gateway RPC 异常，也应 enqueue offline queue
  - group path：只对首次失败且二次仍失败的 recipient enqueue，不能把已成功 recipient 也排队
  - dedicated runtime：Redis 可用时 `message-service` 能装配真实 `OfflineQueue`

### 再做最小生产代码修正
- 优先考虑最小实现路径，不要扩张到 `5.5`
- 可以接受的方向：
  - 在 `message-service` 编排层基于现有 dispatcher 结果做 retry once + enqueue
  - 或者在 dedicated runtime 包一层只属于 `message-service` 的 dispatcher decorator
- 不要做的事情：
  - 把 refresh once 逻辑塞进 `access-gateway`
  - 把 `message-service` 再次耦合回 repository
  - 顺手把 replay side、receipt side 或 ACK 终态一起做掉

## 建议优先看的文件
- OpenSpec 与边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-3-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-4-handoff.md`
- 当前 `5.4` 最相关实现：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
  - `common/src/main/java/com/github/lystran/mochat/common/offline/OfflineQueue.java`
  - `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
- 可直接复用的测试 / 真值表：
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/OfflineReplayServiceTest.java`

## 下一会话建议验证
- 至少先跑 `5.4` focused suite：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest \
  --rerun-tasks
```
- 若 `5.4` 准备勾选，再补一组更稳妥的：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  --tests com.github.lystran.mochat.logic.chat.OfflineReplayServiceTest \
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
- `5.4` 的最小目标是：
  - 失败后 refresh route 一次
  - 仍失败则 enqueue offline queue
- 当前 `GrpcMessageRecipientDispatcher` 每次调用本身就会 fresh 读 Redis，所以 refresh once 未必需要新增 route reader 接口
- 当前最容易踩坑的是：
  - 只补 enqueue，不补 retry once
  - 用了 offline queue，但把 payload 设计成只适配 legacy 单体 replay
  - 提前把 `ReplayOfflineMessages` 或 sender ACK 终态一起做掉
  - 让 `message-service` 再次直接摸仓储
