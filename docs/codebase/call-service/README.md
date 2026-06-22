# Call Service Codebase Memory

## 职责

`call-service` 负责音视频通话的控制面和信令面：

- HTTP 通话命令入口：私聊邀请、群通话开始、群通话加入/离开。
- WebSocket 通话信令：绑定 session、推送 invite/accept/reject/cancel/hangup/group event。
- LiveKit token 签发：为有效通话房间签发 `RoomJoin`、`RoomName`、`CanPublish`、`CanSubscribe` grant 的 JWT。
- 进程内活跃房间状态：私聊和群聊通话房间创建、加入、离开、结束。
- 通话权限校验：读取好友关系和群成员关系，复用 PostgreSQL 中的 `user_friendships`、`group_memberships`。
- 通话离线通知：RocketMQ 批量转储到 PostgreSQL `call_offline_notifications`，WebSocket 上线后补推。

核心实现：

- 入口：`call-service-app/src/main/java/com/github/lystran/mochat/callservice/CallServiceApplication.java`
- runtime 装配：`call-service-app/src/main/java/com/github/lystran/mochat/callservice/runtime/CallServiceRuntimeFactory.java`
- lifecycle：`call-service-app/src/main/java/com/github/lystran/mochat/callservice/runtime/CallServiceRuntimeLifecycle.java`
- HTTP controller：`call-module/src/main/java/com/github/lystran/mochat/call/controller/CallController.java`
- WebSocket：`call-module/src/main/java/com/github/lystran/mochat/call/websocket/CallWebSocket.java`
- 通话核心：`call-module/src/main/java/com/github/lystran/mochat/call/service/CallService.java`
- LiveKit token：`call-module/src/main/java/com/github/lystran/mochat/call/service/CallTokenService.java`
- 离线通知：`call-module/src/main/java/com/github/lystran/mochat/call/service/CallOfflineNotificationService.java`

## 非职责

- 不承载消息文本收发、`MessageAcceptedEvent`、sender ACK 或 message offline queue。
- 不拥有用户、好友、群组生命周期；只读取 `user_friendships` 和 `group_memberships` 做通话权限判断。
- 不拥有 durable message truth、history read-side 或 conversation advancement。
- 不提供 TCP 长连接协议入口；通话信令使用 Micronaut WebSocket `/calls/ws/{sessionId}`。
- 不提供内部 gRPC contract；当前通话接口是 HTTP + WebSocket。
- 活跃房间状态只在当前 JVM 内存中，不是跨实例 durable call-state。
- 不管理 LiveKit 房间生命周期 API；当前只生成房间名和入房 token。

## 主要代码路径

- App 入口和配置：`call-service-app/src/main/java/com/github/lystran/mochat/callservice/CallServiceApplication.java`、`call-service-app/src/main/resources/application.yml`、`call-service-app/build.gradle.kts`
- Runtime：`call-service-app/src/main/java/com/github/lystran/mochat/callservice/runtime/CallServiceRuntimeFactory.java`、`CallServiceRuntimeLifecycle.java`
- HTTP/WebSocket：`call-module/src/main/java/com/github/lystran/mochat/call/controller/CallController.java`、`call-module/src/main/java/com/github/lystran/mochat/call/websocket/CallWebSocket.java`、`CallSignalGateway.java`
- 业务服务：`CallService.java`、`CallTokenService.java`、`CallSessionResolver.java`、`CallRelationshipService.java`、`CallOfflineNotificationService.java`
- 房间和 DTO：`CallRoomManager.java`、`CallRoomName.java`、`CallRoomState.java`、`CallSignalMessage.java`
- MQ：`CallOfflineNotificationMqProducer.java`、`CallOfflineNotificationMqConsumer.java`
- MyBatis-Plus mapper/entity：`CallOfflineNotificationMapper.java`、`FriendshipMapper.java`、`GroupMemberMapper.java`、`CallOfflineNotification.java`、`Friendship.java`、`GroupMember.java`
- DB migration：`call-module/src/main/resources/db/migration/V4__call_offline_notifications.sql`

## 核心数据流和交互

私聊通话：

1. 客户端调用 `POST /calls/private/invite`，body 带 `sessionId` 和 `toUserId`。
2. `CallSessionResolver` 读取 Redis `mochat:session:{sessionId}`，兼容旧纯数字格式和 `v1|STATUS|userId|sessionVersion|expiresAt` 格式。
3. `CallRelationshipService.privateRelationshipState` 读取 `user_friendships`，要求双方是 `ok` 好友且未 blocked。
4. `CallRoomManager.createPrivateRoom` 在当前进程创建 `call-private-{firstUserId}-{secondUserId}-{callId}`。
5. `CallSignalGateway.sendToUser` 尝试向被叫 WebSocket 推送 `call_invite`。
6. 被叫在线时，邀请方拿到 LiveKit token 和 URL；被叫离线时，写通话离线通知并立即结束内存房间。
7. 被叫通过 WebSocket 发送 `call_accept`、`call_reject`、`call_cancel`、`call_hangup`；`call_accept` 会给发起该信令的一方返回 LiveKit token。

群通话：

1. 客户端调用 `POST /calls/group/start`。
2. `CallRelationshipService.listActiveGroupMemberIds` 读取 `group_memberships.status=active`。
3. `CallRoomManager.createGroupRoom` 创建 `call-group-{groupId}-{callId}`，发起人立即加入。
4. 在线成员收到 `call_group_started`；离线成员消息批量进入 RocketMQ，失败时同步落库。
5. 成员调用 `POST /calls/group/join` 后校验群成员身份和内存房间状态，签发 LiveKit token，并向房间内其他参与者推 `call_group_member_joined`。
6. 成员调用 `POST /calls/group/leave` 后从内存 participants 移除，必要时结束房间，并推 `call_group_member_left`。

离线通知：

- `CallOfflineNotificationService.enqueueBatch` 先调用 `CallOfflineNotificationMqProducer.sendBatch`。
- RocketMQ topic 默认 `mochat.call.offline-notifications`，consumer group 默认 `mochat-call-offline-consumer`。
- `CallOfflineNotificationMqConsumer` 批量解析 `List<CallSignalMessage>`，写入 `call_offline_notifications`。
- WebSocket `onOpen` 后调用 `pushPendingNotifications(userId)`；只补推仍有活跃内存房间且用户仍是活跃群成员的通知，成功后写 `delivered_at`。

当前注意点：

- `call-service-app/src/main/resources/application.yml` 不再提供 LiveKit URL/API key/API secret 默认值；通话 token 签发必须通过 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET` 外部注入。
- `CallOfflineNotificationMqProducer` 和 consumer 读取的 property key 是 `mochat.rocketmq.offline-notification.*`；配置文件里的 env var 是 `MOCHAT_CALL_OFFLINE_TOPIC` / `MOCHAT_CALL_OFFLINE_CONSUMER_GROUP`。
- 活跃房间是 `ConcurrentHashMap` 进程内状态；多副本部署时同一通话必须考虑粘性路由或外部化房间状态，否则不同实例不可见。
- `pushPendingNotifications` 当前按内存房间是否仍 active 判断可投递性；服务重启后旧离线通知会因房间状态丢失而被标记 delivered 跳过。
- 私聊离线邀请会落库时使用 `groupId=-1`，但 `V4__call_offline_notifications.sql` 的 `group_id` 是 `NOT NULL`。
- `call-service-app/Dockerfile` 优先使用 native image 构建，执行 `:call-service-app:nativeCompile`，distroless 运行镜像默认暴露 `8090`。
- `call-service` 已纳入 Helm 部署，默认 `replicaCount: 1`。
- Kubernetes Service 端口为 HTTP/WebSocket `8090`。
- LiveKit 配置通过 `mochat-livekit` Secret 注入 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。
- 当前活跃房间状态仍在 JVM 内存中，不能直接多副本无状态扩容。
- 旧 `deploy/kubernetes/overlays/kind` 脚本仍未覆盖 call-service。
- 当前没有专门测试目录；不要把 call-service 写成已纳入旧 kind 验证。

## 配置和运行入口

- 本地运行：`./gradlew :call-service-app:run`
- main class：`com.github.lystran.mochat.callservice.CallServiceApplication`
- HTTP/WebSocket：`MOCHAT_CALL_SERVICE_HTTP_HOST=0.0.0.0`、`MOCHAT_CALL_SERVICE_HTTP_PORT=8090`
- WebSocket：`/calls/ws/{sessionId}`
- Redis session：`MOCHAT_REDIS_URI`、`MOCHAT_CALL_SERVICE_REDIS_ENABLED`
- PostgreSQL：`MOCHAT_POSTGRES_URL`、`MOCHAT_POSTGRES_USERNAME`、`MOCHAT_POSTGRES_PASSWORD`、`MOCHAT_CALL_SERVICE_POSTGRES_ENABLED`
- Flyway：`mochat.flyway.locations=classpath:db/migration`、`MOCHAT_CALL_SERVICE_FLYWAY_MIGRATE_ON_START`
- RocketMQ：`MOCHAT_ROCKETMQ_NAME_SERVER`、`MOCHAT_CALL_SERVICE_ROCKETMQ_PRODUCER_GROUP`、`MOCHAT_CALL_OFFLINE_TOPIC`、`MOCHAT_CALL_OFFLINE_CONSUMER_GROUP`、`MOCHAT_CALL_SERVICE_MQ_ENABLED`
- Consumer 开关：`MOCHAT_CALL_SERVICE_QUEUE_CONSUMER_ENABLED`
- LiveKit：`MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`
- id worker：`MOCHAT_CALL_SERVICE_ID_WORKER_ID`

HTTP endpoints：

- `POST /calls/private/invite`
- `POST /calls/group/start`
- `POST /calls/group/join`
- `POST /calls/group/leave`

## 测试入口

- `./gradlew :call-service-app:test`
- `./gradlew :call-module:test`

当前注意点：

- 这两个模块目前没有 `src/test` 目录；上述命令只能证明 Gradle test task 当前为空或可执行，不能证明通话业务行为被覆盖。
- 若新增 call-service 测试，优先覆盖 `CallRoomName`、`CallService` 私聊/群聊状态机、`CallSessionResolver` Redis record parse、`CallOfflineNotificationService` MQ fallback 和补推语义。

## 变更时必须同步更新

- 修改 `/calls/**` HTTP endpoint、request/response record、状态码或 session 解析语义。
- 修改 `/calls/ws/{sessionId}` WebSocket 路径、信令 type、payload 字段或错误返回。
- 修改 `CallRoomName` 命名规则、房间 active 判断、participants 维护或进程内状态假设。
- 修改 LiveKit token grant、identity/name、过期策略、URL/API key/API secret 配置名。
- 修改通话权限读取的 friendship/group membership 表、状态值或 ownership。
- 修改 `call_offline_notifications` 表、索引、字段、Flyway 位置或迁移时机。
- 修改通话离线通知 MQ topic、consumer group、payload JSON 格式、失败兜底策略或补推过滤条件。
- 给 `call-service` 增加 Dockerfile、Kubernetes workload、Service、探针或纳入 kind 验证。
- 把活跃房间状态从进程内迁移到 Redis/DB/LiveKit room API，或支持多副本共享状态。
- 改变 call-service 与 api/message/persistence/access-gateway 的职责边界。
