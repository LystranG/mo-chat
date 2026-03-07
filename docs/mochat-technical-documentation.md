# MoChat 项目技术文档

本文档基于当前工作分支 `runtime-integration-user-persistence` 的实际代码状态整理，目标是从工程实现视角说明：MoChat 目前已经实现了什么、真实运行链路如何装配、哪些能力已经验证、以及还剩下哪些明显缺口。

这不是需求文档，也不是远期规划文档；它描述的是当前分支中“已经存在且已验证到何种程度”的技术事实。

---

## 1. 项目定位与当前状态

MoChat 的目标是实现一个面向单机高吞吐场景的 IM 后端，同时在模块边界上为后续向云原生 / 多进程拆分演进预留空间。当前分支已经不再只是“模块化骨架”，而是形成了一个可启动、可跑测试、可连接本地依赖的 Phase 1 运行时装配版本。

当前已经完成的关键事项包括：

- 建立并稳定了 Gradle 多模块结构：`app`、`common`、`protocol`、`connection-module`、`logic-module`、`infra-redis`、`persistence-module`。
- `app` 已经把 HTTP、Netty TCP、Redis、PostgreSQL、RocketMQ、Flyway 相关 Bean 装配到同一个 Micronaut 运行时中。
- 登录 / 注册、公钥校验、Redis Session、私聊消息摄入核心处理、幂等、`seq` 分配、RocketMQ 有序发送、ACK / 回执事件发射、历史查询、会话状态查询等能力已经落地。
- `JdbcUserRepository` 已补齐，登录/注册不再只能依赖内存仓储；有 `DataSource` 时会走 PostgreSQL 持久化实现。
- `RocketMqPersistenceConsumer` 已补齐，并通过 `PersistenceRuntimeLifecycle` 接入应用启动链路。
- 真实 `:app:run` 已结合本地 `podman compose` 依赖验证过：HTTP `8080` 和 Netty TCP `9000` 可以监听，`GET /friends` 可返回响应。
- `./gradlew test --rerun-tasks` 当前可通过；此前被 skip 的 4 个 Testcontainers 测试在 rootless Podman 环境下也已经恢复为实际执行。

但它仍然不是“功能完备的一期 IM 成品”。当前仍保留的主要现实限制包括：

- 好友关系、群组管理等社交图谱 HTTP 接口仍然是占位实现。
- EventBus 边界仍采用字符串协议，不是强类型事件契约。
- 群聊业务校验、好友拉黑规则、私聊 `nonce` / `ciphertext` 语义等仍不完整。
- 私聊发送依赖数据库中预先存在的好友关系 / 会话记录；而创建这些社交关系的 HTTP 入口当前仍未完成。
- 群缓存只有写入和 L1 读取路径，没有完整 L2 回源读取路径。
- 用户与 Netty Channel 的绑定流程还没有在当前运行时代码中真正接上，因此在线写回与登录后离线重放仍停留在“事件与基础设施已实现、端到端闭环待补”的状态。
- 全链路端到端自动化测试仍不足，当前更接近“模块级 + 半集成 + 真实本地启动验证”的组合。

一句话概括当前阶段：

> 这已经是一套能够完成真实运行时装配、主链路核心处理较完整、测试可通过的一期 IM 后端实现，但社交图谱、在线投递闭环与部分细节能力仍未补齐。

---

## 2. 模块架构总览

| 模块 | 主要职责 | 当前完成度 | 当前说明 |
|---|---|---|---|
| `app` | 应用入口、Micronaut 装配、运行时生命周期、Native Image 打包 | 高 | 已装配其他模块并验证真实启动 |
| `common` | 事件总线、会话锁、幂等、离线队列、用户连接目录等抽象 | 高 | 抽象稳定，支撑各模块组合 |
| `protocol` | 固定头协议、消息类型、错误码、protobuf schema | 高 | 线协议稳定，可支撑 TCP 入出站 |
| `connection-module` | Netty TCP、TLS、拆帧、限流、心跳、出入站桥接 | 中高 | 已接入 `app`，但错误帧和完整心跳闭环仍偏原型 |
| `logic-module` | 登录/注册、Session、消息摄入、ACK、回执、历史/状态查询 | 中高 | 私聊主链路较完整，社交图谱和群管理仍不完整 |
| `infra-redis` | Redis 事件总线、`seq`、幂等、离线队列 | 高 | 已接入运行时；Testcontainers 验证恢复为实际执行 |
| `persistence-module` | Flyway migration、JDBC 落库、RocketMQ 持久化消费、群缓存 | 中高 | 持久化事务和 MQ 消费器都已实现并接入启动链路 |

### 2.1 模块边界设计

项目整体采用“模块化单体 + 显式抽象接口”的结构：

- `common` 定义跨模块抽象，避免业务逻辑直接耦合到底层中间件。
- `connection-module` 只负责 TCP 接入、协议处理、Netty Channel 写出和桥接。
- `logic-module` 负责业务规则、鉴权、顺序、ACK、查询、回执。
- `infra-redis` 提供 Redis 驱动的热点路径状态实现。
- `persistence-module` 负责 DB 真相、事务更新、RocketMQ 消费和群缓存写入。
- `app` 负责把这些模块装配成一个真正能启动的运行时。

### 2.2 当前真实装配情况

与此前“`app` 只是启动壳”的状态不同，当前 `app` 已经依赖并装配以下能力：

- `DataSource`、`Flyway`、Redis 客户端与 Pub/Sub 连接。
- `EventBus`、`OfflineQueue`、`ConversationSeqGenerator`、`IdempotencyStore`。
- `NettyChatServer`、`OutboundEventSubscriber`、`ConnectionRuntimeLifecycle`。
- `DefaultMQProducer`、`DefaultMQPushConsumer`、`RocketMqProducer`、`RocketMqPersistenceConsumer`、`PersistenceRuntimeLifecycle`。
- `MessageRepository`、`ConversationRepository`、`MqConsumer`、`GroupMessageCache`。

对应关键文件：

- `app/build.gradle.kts`
- `app/src/main/resources/application.yml`
- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/ConnectionRuntimeLifecycle.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`

---

## 3. 核心技术栈

### 3.1 语言、构建与框架

- Java 25
- Gradle Kotlin DSL 多模块构建
- Micronaut 4.9.0

### 3.2 网络与协议

- Netty 4.1.x / 4.2.x 组合依赖
- Linux 下优先 `io_uring`，回退 `epoll`，再回退 `NIO`
- Protobuf 4.30.2
- 自定义固定头协议（11 字节 header + protobuf body）

### 3.3 中间件与存储

- Redis / Lettuce 6.7.1.RELEASE
- RocketMQ Client 5.3.2
- PostgreSQL JDBC 42.7.5
- Flyway 10.20.1
- Caffeine 3.2.1

### 3.4 测试与辅助工具

- JUnit 5、Mockito、Micronaut Test、Testcontainers
- Apache Commons Codec（严格 Base64 校验）
- GraalVM Native Build Tools
- 本地依赖编排使用 `podman compose`

---

## 4. 关键能力实现说明

### 4.1 应用启动、配置与运行时生命周期

#### 解决的问题

- 让 `app` 真正成为统一启动入口，而不是空壳。
- 在启动时装配 HTTP、Netty TCP、Redis、PostgreSQL、RocketMQ、Flyway。
- 提供本地稳定默认值，降低真实联调成本。

#### 当前实现

- `Application` 仍然只负责 `Micronaut.run(...)`，但核心装配逻辑已经落在 `MochatRuntimeFactory` 与各类 lifecycle Bean 中。
- `application.yml` 已配置本地稳定默认值：
  - PostgreSQL：`jdbc:postgresql://localhost:5432/mochat`
  - 用户名 / 密码：`mochat` / `mochat`
  - Redis：`redis://localhost:6379`
  - RocketMQ NameServer：`localhost:9876`
  - HTTP / TCP 监听：`8080` / `9000`
- `ConnectionRuntimeLifecycle` 启动 `OutboundEventSubscriber`，并按配置决定是否启动 `NettyChatServer`。
- `PersistenceRuntimeLifecycle` 按配置决定是否启动 `RocketMqPersistenceConsumer`。
- `FlywayMigrationBootstrap` 默认在启动时执行 migration。
- TLS 为可选项；若开启 TLS，则要求同时提供证书链和私钥路径。

#### 已验证情况

- `podman compose up -d` 后执行 `./gradlew :app:run`，已实际观察到 `8080` 与 `9000` 监听。
- `curl -fsS http://127.0.0.1:8080/friends` 可成功返回响应。

#### 当前缺口

- `:app:nativeCompile` 在这轮没有重新完整验证，只保留了可跳过缺失 `native-image` 的构建逻辑。
- 启动链路已经打通，但并没有覆盖完整消息收发的自动化端到端测试。

### 4.2 协议、Netty 接入与连接层基础能力

#### 当前实现

- `protocol` 模块定义固定头、`MsgType`、`SerializerType`、`ErrorCode` 以及 protobuf schema。
- `NettyChatServer` 启动时优先尝试 `io_uring`，失败则回退 `epoll` 或 `NIO`。
- `ChatChannelInitializer` 组织 TLS、拆帧、限流、心跳和入站路由处理器。
- `OutboundEventSubscriber` 负责把 `connection.outbound` 事件重新编码为二进制帧，并在目录中找到目标用户 Channel 时写回在线连接。

#### 已落地能力

- Netty TCP 服务已经接入 `app` 生命周期。
- 出站写回、离线入队、离线回放这三段基础设施代码都已存在。
- `DELIVERED_ACK` 被显式排除在离线队列之外，避免回执堆积。

#### 当前缺口

- 连接层对异常消息仍以丢弃 / 关闭连接为主，没有完整返回标准错误帧。
- 心跳目前主要是“读空闲关闭”语义，尚未形成完整的服务端主动心跳闭环。
- EventBus 出入站边界仍是字符串编码，不是结构化事件对象。
- 当前运行时代码中尚未看到用户 ID 与 Netty Channel 的绑定入口，因此在线写回链路还不能视为真正闭环。

### 4.3 登录 / 注册、公钥校验与 Session

#### 当前实现

- `AuthController` 提供 `POST /auth/login`。
- `UserService.loginOrRegister(...)` 支持：
  - 首次登录必须提供 `publicKey`
  - `publicKey` 必须是严格 Base64，解码后长度必须为 32 字节
  - 已存在用户再次登录时，若带 `publicKey`，必须与已存值完全一致
- `JdbcUserRepository` 已实现；当运行时存在 `DataSource` 时优先使用 JDBC 版仓储。
- `InMemoryUserRepository` 仍保留为 fallback，用于无 DB 环境或部分测试场景。
- `SessionService` 使用 Redis 保存 `sessionId -> userId`，并维护 Caffeine 本地缓存。
- 登录成功后会触发 `OfflineReplayService.replayOnLogin(...)` 做离线补推。

#### 当前缺口

- Session 仍无 TTL，`SESSION_EXPIRED` 语义尚未真正落地。
- `resolveUserId(...)` 仍是先读 Redis、再刷新本地缓存，本地缓存不是严格意义上的优先热读缓存。
- 用户公钥虽然已经可持久化，但当前没有额外的 Redis 公钥缓存层。

### 4.4 入站消息摄入、顺序、幂等与 MQ 发送

#### 当前实现

- `InboundMessageConsumer` 订阅 `connection.inbound`，解析 `PRIVATE_MESSAGE`、`GROUP_MESSAGE`、`CLIENT_RECEIVE_ACK`。
- 私聊 / 群聊请求在进入下游前会清除 `sessionId` 后再进行 base64 编码，避免会话令牌被落库。
- `MessageIngestService` 在 `ConversationLock` 内完成：
  - 幂等查询
  - `seq` 分配
  - `msgId` 分配
  - 按 `conversationId` 做 RocketMQ 有序发送
  - 成功后写幂等映射
  - 向发送方发 `SEND_ACK` 事件
  - 私聊场景下向接收方发在线投递事件
- 私聊链路要求 `ReceiptConversationStateStore` 中已经存在该 `conversationId` 对应的私聊参与者信息；JDBC 实现会从 `conversations` 与 `user_friendships` 读取既有记录。

#### 当前缺口

- MQ envelope 仍然是 `|` 分隔字符串，不够强类型。
- 群聊与私聊共用主链，但更完整的群成员校验、权限规则仍未补齐。
- 私聊链路仍缺少对 `nonce` 长度、`ciphertext` 必填等更细的业务校验。
- `SEND_ACK` / 私聊投递事件虽然会被发出，但要真正写到在线连接上仍依赖尚未接通的用户-Channel 绑定流程。
- 由于好友关系 / 私聊会话创建入口仍未完成，当前分支并不能从空数据库状态直接跑通完整私聊发送业务。

### 4.5 私聊二阶段送达回执

#### 当前实现

- `ReceiptService` 处理 `CLIENT_RECEIVE_ACK`。
- 它会检查：
  - 会话存在
  - 回执上报者是该私聊参与方
  - `latestReceivedSeq` 不超过服务端已知 `latestSeq`
- `JdbcReceiptConversationStateStore` 使用数据库 + Caffeine 维护私聊会话状态，并通过 `GREATEST` 持久化 `uid_1_seq` / `uid_2_seq`。
- 更新成功后向对端发出 `DELIVERED_ACK` 事件。

#### 当前缺口

- `DELIVERED_ACK` 不会进入离线队列，因此发送方离线时不会收到补推回执。
- 回执相关设计已实现，但与部分旧文档表述曾有漂移，维护时应以当前代码与 migration 为准。
- `DELIVERED_ACK` 事件要真正到达在线发送方，同样依赖用户-Channel 绑定流程补齐。

### 4.6 历史查询与会话状态查询

#### 当前实现

- `HistoryController` 提供 `/history` 查询接口。
- `ConversationController` 提供：
  - `/conversations/{conversationId}/private-peer-received-seq`
  - `/conversations/{conversationId}/state`
- 查询前都会通过 Session 做鉴权，并借助 `ConversationStateService` / `JdbcConversationStateRepository` 做访问控制。
- 当前历史分页的真实语义是基于 `conversation_id + seq`：
  - 无游标：按 `seq DESC LIMIT ?`
  - 有游标：按 `seq < cursorSeq` 分页

#### 当前缺口

- 历史分页模型已经明确是 `seq` 游标，不再是早期文档中的 `msgId` 游标；维护文档时必须统一这一事实。
- 当前只做“是否属于该会话”的访问控制，未覆盖更细的业务规则，例如拉黑后的历史可见性。
- `limit` 只做了默认值，没有统一的强上限保护。

### 4.7 RocketMQ 持久化消费与数据库事务

#### 当前实现

- `RocketMqPersistenceConsumer` 已经实现 `MessageListenerOrderly`，可解析逻辑层产出的 `|` 分隔消息 envelope。
- 消费异常时会返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT`，并设置默认 3 秒挂起时间。
- `MqConsumer.persistMessage(...)` 在一个 JDBC 事务内完成：
  - `insert messages`
  - `update conversations.latest_seq/latest_message_time`
  - `commit`
- 群消息在事务提交后才写入 `GroupMessageCache`。
- 这些组件已经通过 `app` 运行时装配，并可由 `PersistenceRuntimeLifecycle` 在启动时拉起。

#### 当前缺口

- 消费端与生产端之间仍是字符串 envelope 协议，契约表达较弱。
- 当前真实启动已验证 consumer Bean 可被创建并进入生命周期，但缺少自动化的“RocketMQ 实发 -> DB 可查”的整链路用例。

### 4.8 离线队列与登录重放

#### 当前实现

- `RedisOfflineQueue` 基于 Redis List，`RPUSH + LTRIM + LPOP count` 控制每用户最多保留 50 条。
- `OutboundEventSubscriber` 负责在能找到在线 Channel 时写出，并在写出失败时把失败 payload 回落到离线队列。
- `OfflineReplayService` 在登录成功后进行最多 50 条的离线重放；若 `eventBus.publish(...)` 中途抛异常，会把当前消息和剩余尚未重放的 payload 重新入队。

#### 当前缺口

- 离线队列存储的是完整 payload，不是轻量引用，Redis 占用偏高。
- Channel 写出失败时只能逐条回退失败 payload，不能把“同批剩余消息”整体回队。
- 当前运行时代码中还没有用户-Channel 绑定流程，因此登录后离线重放尚未形成真正的在线送达闭环。

### 4.9 群消息缓存

#### 当前实现

- `GroupMessageCache` 同时维护：
  - L1：Caffeine
  - L2：Redis ZSET
- 每个群最多缓存 500 条消息。
- 只在持久化事务提交成功后更新缓存。

#### 当前缺口

- 当前读取路径只读 L1，本质上还不是完整的 L1/L2 双层回源缓存。
- 群业务本身尚未完整，所以该缓存目前更多是“基础设施先行”。

### 4.10 社交图谱与群管理

#### 当前实现

- `FriendsController` 与 `GroupsController` 仍然是 scaffold 接口。
- 它们已经被 `app` 真正装配并可通过 HTTP 暴露，但返回的仍是占位响应，而不是完整业务数据。

#### 当前缺口

- 这一块仍是当前分支距离一期需求差距最大的部分。
- 好友申请、好友接受、拉黑、建群、入群申请、群成员管理等功能尚未完成真实业务实现。

---

## 5. 数据模型与事实来源

当前与数据库结构相关的事实来源主要有两个：

- 设计基线：`docs/ddl/phase1.sql`
- 真实 migration：`persistence-module/src/main/resources/db/migration/V1__phase1.sql`

在当前分支里，应优先把 **Flyway migration + JDBC 仓储代码** 视为更接近真实实现的来源。

当前核心表包括：

- `users`
- `user_friendships`
- `friend_requests`
- `groups`
- `group_memberships`
- `group_join_requests`
- `conversations`
- `messages`

其中 `conversations` 当前承担了非常关键的会话状态职责：

- `latest_seq`
- `latest_message_time`
- `uid_1_seq`
- `uid_2_seq`

这意味着系统当前已经明确采用“消息表 + 会话状态表”的组合，而不只是单独依赖消息表。

---

## 6. 测试、验证与交付现状

### 6.1 当前已验证内容

当前分支已经验证过以下内容：

- `./gradlew test --rerun-tasks` 通过。
- `RedisSeqGeneratorTest`、`JdbcUserRepositoryIntegrationTest`、`MigrationSmokeTest`、`TransactionalPersistenceTest` 已不再 skip，而是实际执行通过。
- `:app:test` 中的运行时装配测试可证明：Micronaut 上下文能够创建 Netty、Redis、Flyway、RocketMQ、持久化相关 Bean。
- 真实本地启动已验证：`podman compose up -d` 后执行 `./gradlew :app:run`，HTTP `8080` 和 TCP `9000` 正常监听，`GET /friends` 可返回成功响应。

### 6.2 Testcontainers 环境适配

为兼容当前 rootless Podman 环境，Gradle `Test` 任务现在会在以下条件下自动注入 `DOCKER_HOST`：

- 当前环境未显式设置 `DOCKER_HOST`
- `/var/run/docker.sock` 不存在
- `$XDG_RUNTIME_DIR/podman/podman.sock` 存在

这使得本地 rootless Podman 场景下的 Testcontainers 测试不再被 `disabledWithoutDocker = true` 跳过。

### 6.3 当前测试不足

- 缺少一条自动化的完整端到端链路测试，覆盖“TCP 入站 -> 逻辑层 -> RocketMQ -> 持久化 -> HTTP 查询”。
- 群聊成员权限、拉黑规则、加群 / 好友请求等业务没有形成完整测试面。
- 真实 `RocketMQ` broker 上的自动化集成验证仍较少，更多依赖单元测试、半集成测试和手工本地启动验证。

---

## 7. 当前主要缺口与技术债

当前最值得关注的缺口大致如下：

1. 社交图谱与群管理功能仍是占位实现。
2. EventBus 使用字符串协议，长期演进成本较高。
3. 私聊 / 群聊部分细业务校验尚未补齐，例如拉黑规则、成员校验、`nonce` / `ciphertext` 语义。
4. 历史查询 `limit` 缺少统一强上限保护。
5. Session 尚无 TTL 与过期语义。
6. `DELIVERED_ACK` 不做离线补推，发送方离线时会失去实时送达反馈。
7. 群缓存只有写路径和 L1 读路径，缺少完整 L2 回源。
8. MQ envelope 仍采用 `|` 分隔字符串，不够强类型。
9. 真实本地启动已验证，但自动化端到端验证仍不足。
10. 测试运行时仍存在一些非阻塞性警告（例如 JDK native access、Mockito agent），虽不影响通过，但后续值得清理。

---

## 8. 当前分支的总体判断

与此前“主链路局部可测、运行时尚未装配”的状态相比，当前分支已经前进到了新的阶段：

- `app` 已不再是空壳，而是具备真实启动链路。
- 用户、公钥、会话、消息摄入、回执、历史、状态查询、MQ 持久化消费等关键模块已经接上同一个运行时。
- 本地依赖联调和整仓测试都已经打通。
- 但在线投递与登录后补推到真实 Netty Channel 的最后一跳，仍有用户-Channel 绑定缺口未补。
- 同时，私聊发送仍依赖预先存在的好友关系 / 会话记录，而这一社交图谱创建链路还没有通过 HTTP 入口补齐。

因此，对当前分支最准确的判断不是“仍只是原型骨架”，而是：

> 这是一套已经完成运行时装配、私聊主链路核心处理大部分落地、测试与本地启动均已验证的一期 IM 后端实现；后续工作重点应转向补齐社交图谱、补完用户-Channel 绑定与在线投递闭环、打通私聊会话创建入口、完善群能力、加强自动化端到端验证与收敛技术债。
