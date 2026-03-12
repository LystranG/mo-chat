# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 5.1~5.3 待执行）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`、`3.5`、`4.1`、`4.2`、`4.3`、`4.4`
- 当前下一个未完成任务：
  - `5.1`
  - `5.2`
  - `5.3`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含此前任务的未提交改动；不要回滚
- 当前阶段不需要本地 K8s

## 下一会话目标
- 提速模式：一个新会话内顺序完成 `5.1`、`5.2`、`5.3`
- 不要推进：
  - `5.4` 的“重查路由一次，仍失败则写离线队列”
  - `5.5` 的 ACK / offline replay 语义收尾
  - `6.x+`
  - `7.x+`

## 必须保持的已完成边界
- `api-service` 仍然是 session authority；不要回退 `4.1 / 4.2`
- `sessionVersion` / `routeEpoch` fence 语义不能回退
- `4.3` 已把消息发送前置校验收敛到 `api-service` 内部业务接口；`5.2` 应接入这个 gRPC 契约，而不是回头让 `message-service` 直接摸仓储
- `4.4` 已确认 history read-side 继续留在 `api-service`；不要把 `/history` 或 `/conversations/**` 往 `message-service` / `persistence-service` 搬

## `5.1~5.3` 的真实范围

### `5.1`
- 把消息摄入、幂等、`seq` 分配、`msgId` 分配和 sender ACK 逻辑迁移到 `message-service`
- 这意味着：
  - `message-service` 的 `MessageCommandGrpcService` 不能再返回 skeleton 假数据
  - 需要由 `message-service` dedicated runtime 拥有真正的 ingest orchestration bean graph
  - 私聊 / 群聊 gRPC command 需要走真实 ingest path，而不是继续依赖 legacy 单体入口

### `5.2`
- 接入 `api-service` 的内部校验接口
- 这意味着：
  - 私聊发送前不再直接使用 `MessageRelationshipRepository`
  - 群聊发送前不再直接使用群成员仓储
  - `message-service` 应通过 `SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub` 调 `CheckPrivateMessagingPolicy` / `GetGroupSendContext`

### `5.3`
- 接入 Redis 在线路由解析与 `access-gateway` 定点投递
- 这意味着：
  - `message-service` 需要读取 `online:user:{uid}` 路由记录
  - 调 `AccessGatewayDispatchApi.DeliverToConnection`
  - 统一处理：
    - `DELIVERED`
    - `USER_OFFLINE`
    - `ROUTE_STALE`
    - `WRITE_FAILED`
- 当前这一步只需要把状态映射与同步投递链做实；不要提前实现 `5.4` 的 refresh-once + offline queue fallback

## 当前代码现实：消息链仍在哪里

### legacy 消息摄入仍在 `logic-module`
- 关键文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`
- 当前行为：
  - `InboundMessageConsumer` 解析 TCP inbound event
  - `MessageIngestService` 负责：
    - 幂等查重
    - 私聊/群聊校验
    - `seq` 分配
    - `msgId` 分配
    - RocketMQ ordered publish
    - sender `SEND_ACK`
    - 通过 `EventBus` 直接做 recipient delivery fanout
- 这些职责正是 `5.1~5.3` 要从 legacy 单体路径迁到 `message-service` 的内容

### `message-service-app` 目前只有 gRPC 骨架
- 关键文件：
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageServiceGrpcClientFactory.java`
  - `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/CachingAccessGatewayDispatchClientFactory.java`
- 当前现状：
  - `MessageCommandGrpcService` 仍返回固定 skeleton ack
  - 到 `api-service` 的 blocking stub wiring 已经存在
  - 到 `access-gateway` 的按地址创建 blocking stub 工厂已存在
- 当前缺口：
  - `message-service-app` 还没有自己的 Redis / EventBus / OfflineQueue / producer / idempotency / seq / lock / id 运行时装配
  - `message-service-app/build.gradle.kts` 目前也没有 `infra-redis` / `lettuce` 依赖

### 现成可复用的共享实现
- 关键文件：
  - `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`
  - `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`
  - `common/src/main/java/com/github/lystran/mochat/common/lock/JucConversationLock.java`
  - `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- 含义：
  - 单体 runtime 里已经有 `ConversationLock` / `ConversationSeqGenerator` / `IdempotencyStore` / `RocketMqProducer` 的 bean wiring
  - 下一会话大概率需要把其中与消息编排相关的运行时装配抽到 `message-service-app`
  - 但不要为了这一步回滚或大面积重构 `app` 单体 runtime；只做支持 `5.1~5.3` 的最小迁移

## 当前代码现实：`5.2` / `5.3` 可直接接入的边界

### `api-service` 内部发送前置校验接口已就绪
- 关键文件：
  - `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/service/MessageSendPolicyService.java`
- 当前语义：
  - 私聊：
    - `ALLOWED`
    - `NOT_FRIEND`
    - `BLOCKED`
  - 群聊：
    - `ALLOWED`
    - `NOT_MEMBER`
    - `GROUP_NOT_FOUND`
    - `member_uids`
- `5.2` 应直接消费这套 gRPC 结果，不要让 `message-service` 继续摸 `MessageRelationshipRepository`

### `access-gateway` 定点投递语义已就绪
- 关键文件：
  - `protocol/src/main/proto/mochat/internal/gateway/v1/access_gateway.proto`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
- 当前语义：
  - `DELIVERY_STATUS_DELIVERED`
  - `DELIVERY_STATUS_ROUTE_STALE`
  - `DELIVERY_STATUS_USER_OFFLINE`
  - `DELIVERY_STATUS_WRITE_FAILED`
- `5.3` 应复用这套状态集合，不要发明新的结果枚举

### Redis 在线路由写入格式已经存在，但读侧还没抽成共享 reader
- 关键文件：
  - `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java`
- 关键事实：
  - 路由 key 是 `online:user:{uid}`
  - payload 包含：
    - `gatewayPod`
    - `connectionId`
    - `sessionId`
    - `sessionVersion`
    - `routeEpoch`
    - lease 元数据
- 当前缺口：
  - `message-service` 没有现成可用的 route reader 抽象
  - 下一会话大概率需要在 `common` / `message-service-app` / `service-runtime` 中补一个最小共享读模型或解析器
  - 不要为此把 `access-gateway-app` 的写侧实现硬耦合进 `message-service`

## 建议优先看的文件
- OpenSpec 与文档：
  - `openspec/changes/decompose-im-into-core-services/proposal.md`
  - `openspec/changes/decompose-im-into-core-services/design.md`
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
  - `openspec/changes/decompose-im-into-core-services/specs/`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `docs/plans/2026-03-11-decompose-im-core-services-task-4-4-handoff.md`
  - `docs/plans/2026-03-11-decompose-im-core-services-task-5-1-to-5-3-handoff.md`
- 当前消息编排实现：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`
- 现成测试资产：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceRelationshipAndGroupTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcConnectivityTest.java`
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceGrpcWiringTest.java`
  - `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`

## 下一会话建议执行方式
- 先做 TDD，不要直接改生产代码
- 建议在一个会话内按顺序推进：
  1. `5.1`：让 `MessageCommandGrpcService` 走真实 ingest service，而不是 skeleton
  2. `5.2`：把私聊/群聊前置校验改成走 `api-service` gRPC
  3. `5.3`：补 route 读取和 `access-gateway` 定点投递状态处理
- 如果中途发现 `5.3` 需要顺手做“refresh once + offline queue fallback”，停住；那是 `5.4`

## 验证建议
- focused red/green 预计至少会涉及：
  - `:message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest`
  - `:message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcWiringTest`
  - 以及下一会话新补的 `message-service-app` orchestration / route-delivery 测试
- 若 `5.1~5.3` 完成，收尾 fresh 仍建议使用：
  - `./gradlew :logic-module:test :api-service-app:test :message-service-app:test :persistence-service-app:test :connection-module:test :access-gateway-app:test --rerun-tasks`

## 对下一会话最重要的提醒
- 要快，但不要为了快把 `5.4+ / 6.x+ / 7.x+` 混进来
- 当前最值得复用的是：
  - legacy `MessageIngestService` 的业务行为与测试断言
  - `api-service` 已完成的校验 gRPC
  - `access-gateway` 已完成的定点投递状态枚举和连接 fence
- 当前最容易踩坑的是：
  - 把 `message-service` 再次直接耦合到 repository
  - 把 route 读取写死在 `access-gateway-app` 内部实现上
  - 提前把 offline fallback / ACK 终态语义一起做掉
