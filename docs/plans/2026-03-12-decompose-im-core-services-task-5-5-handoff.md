# OpenSpec `decompose-im-into-core-services` 交接（2026-03-12，Task 5.5 待执行）

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
- 当前下一个未完成任务：
  - `5.5`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 本轮已经完成的内容

### `5.4` 已完成
- `MessageIngestService` 现在会在 `message-service` 编排层消费 `MessageRecipientDispatcher` 的四态结果
- 当前 `5.4` 已做实：
  - private path：首次 `USER_OFFLINE` / `ROUTE_STALE` / `WRITE_FAILED` 或 runtime 异常后，再做一次 fresh dispatch
  - private path：第二次仍失败时才 enqueue offline queue
  - group path：只对首次失败的 recipient 做第二次 fresh dispatch
  - group path：只把二次仍失败的 recipient enqueue
  - offline queue payload 统一编码成可 replay 的 `ChatMessageDelivery` envelope，而不是 DB 引用
  - dedicated `message-service` runtime 已装配真实 `RedisOfflineQueue`
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
- `5.4` 的 focused / robust 验证已 fresh 通过；本轮末尾我亲自复跑的命令是：
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
- 结果：
  - `BUILD SUCCESSFUL`

## `5.5` 的真实目标
- 只完成：
  - 调整 sender ACK 语义
  - 调整 offline replay 逻辑
  - 明确并守住：
    - `SEND_ACK` 只表示 `message-service` 已接受消息、已分配 `msgId` / `seq`、并已成功 publish 到 MQ
    - `SEND_ACK` 不表示 DB 已提交
- 不要推进：
  - `6.x`
  - `7.x`
  - `persistence-service` 的真实持久化迁移
  - `/history` 或 `/conversations/**` 的 read-side 迁移

## 必须保持的已完成边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `4.3` 已把消息发送前置校验收敛到 `api-service` 内部接口；不要让 `message-service` 重新直接摸仓储
- `4.4` 已确认 history read-side 继续归属 `api-service`；不要把 `/history` 或 `/conversations/**` 搬到 `message-service` / `persistence-service`
- `5.3` 的“首次 route 解析 + 定点投递 + 四态映射”不要推翻
- `5.4` 的“失败后 refresh route 一次，仍失败则 enqueue offline queue”不要回退
- OpenSpec design 已明确：
  - `SEND_ACK` 不等于 DB commit complete
  - offline queue entry 应是 delivery envelope，而不是 DB reference

## 当前代码现实：`5.5` 的关键缺口在哪里

### 1. `ReplayOfflineMessages` 仍然是 skeleton
- 关键文件：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
  - `protocol/src/main/proto/mochat/internal/message/v1/message_service.proto`
- 当前现状：
  - `ReplayOfflineMessages` 现在固定返回：
    - `accepted=true`
    - `replayed_count=0`
    - `detail=\"skeleton\"`
- 这意味着：
  - `message-service` 的内部 replay 命令契约虽然已经存在
  - 但 replay 实现还没真正接到 `OfflineQueue`

### 2. 登录后的离线重放仍然走 `api-service`/legacy 本地服务，不是 `message-service` 内部命令
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/OfflineReplayService.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`
- 当前现状：
  - `AuthController.login()` 在 session 签发成功后，仍直接调用本地 `OfflineReplayService.replayOnLogin(userId)`
  - `OfflineReplayService` 仍然是：
    - `offlineQueue.drain(userId, maxItems)`
    - 把 `userId|payload` publish 到 `connection.outbound`
    - publish 失败时 re-enqueue 剩余 payload
- 这意味着：
  - 当前 replay 责任仍带着明显的 legacy/local EventBus 假设
  - `5.5` 很可能需要重新审视“谁发起 replay、谁执行 replay、怎么保持 ACK 语义边界”

### 3. sender ACK 语义在 dedicated `message-service` runtime 和 legacy path 之间还没有完全收口
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/EventBusSenderAckPublisher.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
- 当前现状：
  - `MessageIngestService` 仍保留 `SenderAckPublisher` 抽象，语义上是在 MQ publish success 之后发布 sender ACK
  - legacy / monolith 风格路径下仍能通过 `EventBusSenderAckPublisher` 发 `SEND_ACK`
  - dedicated `message-service` runtime 里默认注入的是 no-op `SenderAckPublisher`
  - dedicated runtime 当前对调用方的成功反馈主要体现在 gRPC `SendPrivateMessageAck` / `SendGroupMessageAck`
- 这意味着：
  - 当前代码已经没有把 sender success 建模成“等待 DB commit 完成”
  - 但 `5.5` 仍需把 ACK 语义和 replay side 的职责边界收口清楚，而不是让不同 runtime 各讲一套故事

### 4. `5.4` 产出的 offline payload 现在已经是 replayable envelope，不要回退
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceRelationshipAndGroupTest.java`
- 当前现状：
  - private / group offline queue payload 都已经编码成：
    - `MSG_TYPE|PROTOBUF|BASE64(ChatMessageDelivery)`
- 这意味着：
  - `5.5` 应该复用这套 envelope 继续做 replay
  - 不要回退到只存 `(conversationId, seq, msgId)` 或依赖 DB 再查 payload

## 下一会话最建议先确认的现状
1. `MessageCommandGrpcService.replayOfflineMessages(...)` 现在是否仍完全 skeleton
2. `AuthController.login()` 当前是否仍直接调用本地 `OfflineReplayService`
3. dedicated `message-service` runtime 当前是否真的没有可用的 replay service bean / wiring
4. `SenderAckPublisher` 在 dedicated `message-service` runtime 与 legacy path 之间，现在分别是什么语义
5. 当前哪些测试已经把“MQ 成功才 ACK、publish 失败不 ACK、offline replay queue payload 是 envelope”钉住了，哪些还没覆盖

## 先做 TDD 时建议优先复用的文件
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceRelationshipAndGroupTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/OfflineReplayServiceTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`

## `5.5` 最值得先补的红测
- `message-service` gRPC `ReplayOfflineMessages` 不再返回 skeleton，而是实际 drain offline queue 并返回 `replayed_count`
- replay 过程中 publish / dispatch 失败时，会把当前及剩余 envelope 重新入队，而不是丢失
- login 或调用 replay 命令的链路不会把“已 replay / 已 delivery / 已 ACK”解释成“DB 已提交”
- sender success ACK 仍然只发生在 MQ publish success 之后；publish 失败时不应产生 success ACK
- 若 `5.5` 需要从 `api-service` 调 `message-service` 内部 replay 命令，则补 dedicated runtime / wiring / focused integration 测试，而不是只改本地逻辑

## 建议优先看的文件
- OpenSpec 与边界文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-4-handoff.md`
  - `docs/plans/2026-03-12-decompose-im-core-services-task-5-5-handoff.md`
- 当前 `5.5` 最相关实现：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/runtime/MessageServiceRuntimeFactory.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/OfflineReplayService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/EventBusSenderAckPublisher.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java`
- 可直接复用的测试 / 真值表：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/OfflineReplayServiceTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`

## 下一会话建议验证
- 至少先跑一组 `5.5` focused suite：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  --tests com.github.lystran.mochat.logic.chat.OfflineReplayServiceTest \
  --tests com.github.lystran.mochat.logic.http.AuthControllerOfflineReplayHttpTest \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest \
  --rerun-tasks
```
- 如果 `5.5` 改动触及 replay / command wiring，再补一组更稳妥的：
```bash
./gradlew :logic-module:test \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest \
  --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest \
  --tests com.github.lystran.mochat.logic.chat.OfflineReplayServiceTest \
  --tests com.github.lystran.mochat.logic.http.AuthControllerOfflineReplayHttpTest \
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
- `5.5` 的重点不是把 sender success 变成“等 DB commit 才 ACK”
- `5.5` 的重点也不是推进 `6.x` 的真实持久化拆分
- 当前最容易踩坑的是：
  - 直接把 replay payload 回退成 DB reference
  - 为了“证明 DB 已提交”而提前碰 `persistence-service`
  - 继续把 replay 责任留在 `api-service` / local EventBus，却不收口 `message-service` 的内部命令契约
  - 顺手去做 `6.x` 或 `/history` read-side 的迁移
