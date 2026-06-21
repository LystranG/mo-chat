# Api Service Codebase Memory

## 职责

`api-service` 负责账户、session authority、social graph/group 管理、history/conversation read-side，以及登录后触发离线补发：

- dedicated runtime 入口：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/ApiServiceApplication.java`
- runtime 装配：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/runtime/ApiServiceRuntimeFactory.java`
- 内部 gRPC：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java` 暴露 `SessionAuthorityApi`
- 登录：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
- 好友关系：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/FriendsController.java`
- 群管理：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/GroupsController.java`
- 历史查询：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- 会话状态 read-side：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/ConversationController.java`
- Redis-backed authoritative session：`logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`
- 消息发送前策略：`logic-module/src/main/java/com/github/lystran/mochat/logic/service/MessageSendPolicyService.java`

## 非职责

- 不负责 TCP/TLS 长连接、bind、心跳或在线 route ownership。
- 不负责消息命令入口、幂等、`msgId`/`seq` 分配、sender ACK、在线投递编排或 offline fallback。
- 不负责 RocketMQ 消费、`messages`/`conversations` 持久化写入或 post-commit conversation advancement。
- 不拥有 durable message truth；`/history` 和 `/conversations/{id}/state` 只读取已提交视图。
- 不直接执行登录后离线补发；dedicated runtime 通过 `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/GrpcLoginOfflineReplayGateway.java` 调 `message-service` 的 `ReplayOfflineMessages`。
- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/LocalLoginOfflineReplayGateway.java` 是单进程兼容 fallback，不代表 dedicated 默认路径。

## 主要代码路径

- 运行时入口和配置：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/ApiServiceApplication.java`、`api-service-app/src/main/resources/application.yml`、`api-service-app/build.gradle.kts`、`api-service-app/Dockerfile`
- runtime 装配：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/runtime/ApiServiceRuntimeFactory.java`
- gRPC：`api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java`、`ApiServiceGrpcClientFactory.java`、`GrpcLoginOfflineReplayGateway.java`、`protocol/src/main/proto/mochat/internal/api/v1/api_service.proto`
- HTTP controllers：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`、`FriendsController.java`、`GroupsController.java`、`HistoryController.java`、`ConversationController.java`
- service 层：`UserService.java`、`SessionService.java`、`FriendsService.java`、`GroupsService.java`、`HistoryService.java`、`ConversationStateService.java`、`MessageSendPolicyService.java`
- repository 层：`JdbcUserRepository.java`、`JdbcFriendshipRepository.java`、`JdbcFriendListRepository.java`、`JdbcGroupRepository.java`、`JdbcMessageRelationshipRepository.java`、`JdbcHistoryRepository.java`、`JdbcConversationStateRepository.java`
- shared session contract：`common/src/main/java/com/github/lystran/mochat/common/session/**`

## 核心数据流和交互

登录/session：

1. `AuthController.login`
2. `UserService.loginOrRegister`
3. `SessionService.issueSession`
4. 写 Redis authoritative session：
   - `mochat:session:{sessionId}` -> `v1|status|userId|sessionVersion|expiresAtEpochMilli`
   - `mochat:session-active-user:{userId}` -> `sessionId|sessionVersion`
5. 登录后调用 `LoginOfflineReplayGateway.replayOnLogin`；dedicated runtime 使用 `GrpcLoginOfflineReplayGateway` 调 `message-service`。

session authority gRPC：

1. `access-gateway` 或 `message-service` 调 `SessionAuthorityApi.ResolveSession`。
2. `ApiInternalGrpcService.resolveSession` 委托 `SessionService.resolveAuthority`。
3. 返回 `status` 和 `SessionPrincipal(sessionId, userId, sessionVersion)`。

social graph 与发送策略：

- 好友申请通过 `FriendsController -> FriendsService -> JdbcFriendshipRepository`；接受好友申请会更新申请状态、upsert `user_friendships` 并确保私聊 `conversations` 存在。
- 群管理通过 `GroupsController -> GroupsService -> JdbcGroupRepository`；建群会创建 `groups`、owner `group_memberships` 和群聊 `conversations`。
- 发送前策略由 `ApiInternalGrpcService.CheckPrivateMessagingPolicy` 和 `GetGroupSendContext` 委托 `MessageSendPolicyService`。

history/read-side：

- `/history` 通过 `HistoryController -> ConversationStateService -> HistoryService -> JdbcHistoryRepository` 读取已提交 `messages`。
- `/conversations/{id}/state` 通过 `ConversationController -> ConversationStateService -> JdbcConversationStateRepository` 读取 `conversations.latest_seq/latest_message_time`。
- `/conversations/{id}/private-peer-received-seq` 从 requester 视角读取对端 receipt seq。
- sender ACK、实时投递、离线补发成功都不代表 `/history` 可见；history/state 以 persistence commit 后的 `messages` 和 `conversations` 为准。

当前注意点：

- `SessionService` 的过期时间写在 record 的 `expiresAtEpochMilli` 中，当前代码使用 Redis `set`，不要误写成 Redis key 自动 EXPIRE。
- `api-service-app/src/main/resources/application.yml` 暴露 `mochat.api-service.dependencies.postgres-enabled`，但当前 `api-service-app` 未明显提供 dedicated DataSource / IdGenerator factory；JDBC repository 是否 materialize 取决于运行时是否有这些 bean。写代码或改文档时要核对当前装配。

## 配置和运行入口

- 本地运行：`./gradlew :api-service-app:run`
- main class：`com.github.lystran.mochat.apiservice.ApiServiceApplication`
- HTTP：`MOCHAT_API_SERVICE_HTTP_HOST=0.0.0.0`、`MOCHAT_API_SERVICE_HTTP_PORT=8080`
- gRPC：`MOCHAT_API_SERVICE_GRPC_PORT=19091`
- Redis：`MOCHAT_REDIS_URI`、`MOCHAT_API_SERVICE_REDIS_ENABLED=true`
- message-service gRPC client：`MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092`
- legacy inbound consumer 过渡开关：`MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=false`
- Dockerfile：`api-service-app/Dockerfile`，暴露 `8080` 和 `19091`

## 测试入口

- `./gradlew :api-service-app:test`
- `./gradlew :logic-module:test`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceGrpcConnectivityTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceGrpcWiringTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceSessionAuthorityIntegrationTest.java`
- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceHistoryOwnershipTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerHttpTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/SocialGraphLifecycleHttpIntegrationTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/service/SessionServiceTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/service/MessageSendPolicyServiceTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepositoryTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcConversationStateRepositoryTest.java`

## 变更时必须同步更新

- 修改 `api-service-app/src/main/resources/application.yml` 中端口、env var、gRPC channel、Redis/Postgres 开关。
- 修改 `ApiServiceRuntimeFactory` 的 Redis/EventBus/OfflineQueue 装配。
- 新增或删除 `AuthController`、`FriendsController`、`GroupsController`、`HistoryController`、`ConversationController` 的 HTTP endpoint。
- 修改 `SessionService` 的 Redis key、记录格式、状态机、TTL、`sessionVersion` 语义或 active pointer fail-closed 规则。
- 修改 `protocol/src/main/proto/mochat/internal/api/v1/api_service.proto` 的 RPC、request/response、枚举。
- 修改 `ApiInternalGrpcService` 中 session authority、private policy、group context 的映射。
- 修改登录后离线补发路径：`LoginOfflineReplayGateway`、`GrpcLoginOfflineReplayGateway`、`LocalLoginOfflineReplayGateway`。
- 修改 social graph 数据模型或 lifecycle，尤其 friendship id / group id 是否继续复用为 conversation id。
- 修改 history/read-side ownership 或 committed view vs realtime delivery visibility 语义。
- 修改 JDBC repository 对 `messages`、`conversations`、`user_friendships`、`group_memberships` 的查询规则。
- 给 `api-service-app` 增加或移除 DataSource / IdGenerator / Flyway 装配。
- 改变 `api-service` 与其他 dedicated services 的职责边界。

