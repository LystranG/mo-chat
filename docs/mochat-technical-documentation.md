# MoChat 项目技术文档

本文档基于当前代码状态整理，目标是从工程实现视角说明：MoChat 目前已经实现了什么、真实运行链路如何装配、哪些能力已经验证、以及还剩下哪些明显缺口。

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
- 真实 `:app:run` 已结合本地 `podman compose` 依赖验证过：HTTP `8080` 和 Netty TCP `9000` 可以监听；好友列表探针应以先登录拿到 `sessionId` 后再请求 `GET /friends?sessionId=...` 为准。
- `./gradlew test --rerun-tasks` 当前可通过；此前被 skip 的 4 个 Testcontainers 测试在 rootless Podman 环境下也已经恢复为实际执行。

但它仍然不是“功能完备的一期 IM 成品”。当前仍保留的主要现实限制包括：

- 社交图谱 HTTP 生命周期入口已具备基础能力：包括好友申请 / 处理、拉黑 / 解黑、建群、入群申请 / 审批、踢人、解散等。
- EventBus 边界仍采用字符串协议，不是强类型事件契约。
- 消息摄入侧已补齐好友拉黑、群成员以及私聊 `nonce` / `ciphertext` 的基础校验，但更细粒度的群权限规则、私聊会话初始化入口与更多端到端覆盖仍未完整。
- 私聊发送依赖数据库中预先存在的好友关系 / 会话记录；当前仍缺少独立的私聊会话初始化入口与更完整的关系创建链路校验。
- 群缓存只有写入和 L1 读取路径，没有完整 L2 回源读取路径。
- 用户与 Netty Channel 的绑定、在线写回和离线重放到 `connection.outbound` 的链路已经接通；但围绕 HTTP 登录、真实 TCP 连接与离线补推的端到端自动化验证仍不足。
- 全链路端到端自动化测试仍不足，当前更接近“模块级 + 半集成 + 真实本地启动验证”的组合。

一句话概括当前阶段：

> 这已经是一套能够完成真实运行时装配、主链路核心处理较完整、测试可通过的一期 IM 后端实现；社交图谱与群管理已有基础 HTTP 生命周期入口，但群缓存回源、自动化端到端验证与部分治理细节仍未补齐。

---

## 2. 模块架构总览

| 模块 | 主要职责 | 当前完成度 | 当前说明 |
|---|---|---|---|
| `app` | 应用入口、Micronaut 装配、运行时生命周期、Native Image 打包 | 高 | 已装配其他模块并验证真实启动 |
| `common` | 事件总线、会话锁、幂等、离线队列、用户连接目录等抽象 | 高 | 抽象稳定，支撑各模块组合 |
| `protocol` | 固定头协议、消息类型、错误码、protobuf schema | 高 | 线协议稳定，可支撑 TCP 入出站 |
| `connection-module` | Netty TCP、TLS、拆帧、限流、心跳、出入站桥接 | 中高 | 已接入 `app`，具备错误帧返回与基础心跳/鉴权绑定能力，完整连接治理仍在补齐 |
| `logic-module` | 登录/注册、Session、消息摄入、ACK、回执、历史/状态查询 | 中高 | 私聊主链路较完整，社交图谱与群管理基础 HTTP 生命周期入口已落地，但仍缺更细粒度规则与更完整覆盖 |
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
- 聊天 TCP 连接默认强制 TLS；若显式将 `mochat.tls.enabled=false`，运行时会直接 fail-fast。
- `mochat.tls.certificate-path` 与 `mochat.tls.private-key-path` 必须要么同时提供，要么在允许自签名（`mochat.tls.self-signed=true`）时一起留空并由运行时生成自签名证书。
- 当显式提供一对有效且匹配的证书链 / 私钥时，运行时优先使用它们，而不是生成自签名证书。

#### 已验证情况

- `podman compose up -d` 后执行 `./gradlew :app:run`，已实际观察到 `8080` 与 `9000` 监听。
- 好友列表 HTTP 探针应按当前接口签名执行：先通过 `POST /auth/login` 获取 `sessionId`，再请求 `GET /friends?sessionId=...`。
- 2026-03-09 的 fresh native 验证中，`command -v native-image` 指向 `/home/lystran/.local/share/mise/installs/java/oracle-graalvm-25.0.1/bin/native-image`，`native-image --version` 为 Oracle GraalVM `25.0.1`；`./gradlew :app:nativeCompile -g .gradle` 实际执行成功，输出了 native image 完成日志并生成 `app/build/native/nativeCompile/mo-chat`。

#### 当前缺口

- 启动链路已经打通，但并没有覆盖完整消息收发的自动化端到端测试。

### 4.2 协议、Netty 接入与连接层基础能力

#### 当前实现

- `protocol` 模块定义固定头、`MsgType`、`SerializerType`、`ErrorCode` 以及 protobuf schema。
- `NettyChatServer` 启动时优先尝试 `io_uring`，失败则回退 `epoll` 或 `NIO`。
- `ChatChannelInitializer` 组织 TLS、拆帧、限流、心跳、Session 绑定和入站路由处理器。
- `OutboundEventSubscriber` 负责把 `connection.outbound` 事件重新编码为二进制帧，并在目录中找到目标用户 Channel 时写回在线连接。

#### 已落地能力

- Netty TCP 服务已经接入 `app` 生命周期。
- 出站写回、离线入队、离线回放这三段基础设施代码都已存在。
- `SessionBindingHandler` 会在首个带 `sessionId` 的私聊 / 群聊 / 回执帧上解析会话并绑定用户与 Netty Channel。
- `HeartbeatHandler` 会周期性发送 `SERVER_HEARTBEAT`，并在客户端心跳超时时关闭连接、清理绑定。
- `DELIVERED_ACK` 被显式排除在离线队列之外，避免回执堆积。

#### 当前缺口

- 连接层已经对无效 Session 返回 `ERROR_RESPONSE`，业务侧也会对好友/群成员校验失败与 MQ 发布失败发出标准错误帧；但更多协议级异常仍主要依赖丢弃或关闭连接。
- 心跳已具备服务端主动 `SERVER_HEARTBEAT` 与客户端超时关闭闭环，但连接治理的可观测性仍待补齐。
- EventBus 出入站边界仍是字符串编码，不是结构化事件对象。
- 用户-Channel 绑定已接入连接层，但围绕真实 TCP 连接的端到端自动化覆盖仍不足。

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
- 群聊与私聊共用主链，群成员校验、好友拉黑校验以及私聊 `nonce` / `ciphertext` 基础校验已经落地；但更完整的群权限规则、私聊会话初始化入口与端到端覆盖仍未补齐。
- `SEND_ACK` / 私聊投递事件已经进入在线写回 / 离线队列通道，但缺少覆盖 HTTP 登录与真实 TCP 收包的端到端自动化验证。
- 由于私聊会话初始化入口仍未完成，当前分支并不能从空数据库状态直接跑通完整私聊发送业务。

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
- `DELIVERED_ACK` 会走与普通在线投递相同的出站通道，但发送方离线时仍不会收到补推回执。

### 4.6 历史查询与会话状态查询

#### 当前实现

- `HistoryController` 提供 `/history` 查询接口。
- `ConversationController` 提供：
  - `/conversations/{conversationId}/private-peer-received-seq`
  - `/conversations/{conversationId}/state`
- 查询前都会通过 Session 做鉴权，并借助 `ConversationStateService` / `JdbcConversationStateRepository` 做访问控制。
- 当前历史分页的真实语义是基于 `conversation_id + seq`，并统一到 `seq` 模型：
  - 无游标：按 `seq DESC LIMIT ?`
  - 游标模式：按 `seq < cursorSeq` 分页
  - 范围模式：按 `seq BETWEEN startSeq AND endSeq` 查询，再按 `seq DESC LIMIT ?` 截断窗口
  - `cursorSeq` 与 `startSeq/endSeq` 互斥，`startSeq/endSeq` 必须成对出现
  - `limit` 默认 50，且由 `HistoryService` 统一裁剪到不超过 50

#### 当前缺口

- 当前只做“是否属于该会话”的访问控制，未覆盖更细的业务规则，例如拉黑后的历史可见性。

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
- 登录后的离线重放会重新发布到 `connection.outbound`；若用户此时已有绑定 Channel，则会直接写回在线连接，但这一行为的真实 TCP 端到端自动化验证仍不足。

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

- `FriendsController` 与 `GroupsController` 已通过 `app` 真正装配并对外暴露基础 HTTP 生命周期入口。
- 当前已覆盖基础好友申请 / 处理、好友列表、删除好友、拉黑 / 解黑、建群、群列表、退群、入群申请 / 审批、踢人、解散等链路。

#### 当前缺口

- 这一块仍是当前分支距离一期需求差距最大的部分。
- 尚未补齐更细粒度的群权限规则、独立的私聊会话初始化入口，以及覆盖 HTTP + TCP 联动的更多端到端测试。

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
- 真实本地启动已验证：`podman compose up -d` 后执行 `./gradlew :app:run`，HTTP `8080` 和 TCP `9000` 正常监听；好友列表探针需先登录拿到 `sessionId`，再请求 `GET /friends?sessionId=...`。

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

1. 社交图谱与群管理已具备基础 HTTP 生命周期入口，但仍未形成完整的一期能力闭环。
2. EventBus 使用字符串协议，长期演进成本较高。
3. 更细粒度的群权限规则、私聊会话初始化入口与端到端覆盖仍未补齐；不过消息摄入侧的拉黑校验、成员校验以及私聊 `nonce` / `ciphertext` 基础约束已落地。
4. 历史查询虽已具备 `limit<=50` 的统一裁剪，但更细粒度的历史可见性规则仍待补齐。
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
- 在线投递、回执写回与登录后离线重放已经接到同一条 `connection.outbound` -> Netty Channel 通道，但真实 TCP 场景下的端到端自动化验证仍不足。
- 同时，私聊发送仍依赖预先存在的好友关系 / 会话记录，而独立的私聊会话初始化入口与更完整的链路校验仍未补齐。

因此，对当前分支最准确的判断不是“仍只是原型骨架”，而是：

> 这是一套已经完成运行时装配、私聊主链路核心处理大部分落地、测试与本地启动均已验证的一期 IM 后端实现；后续工作重点应转向补齐私聊会话创建入口、收紧群权限与治理规则、完善群管理缓存回源、加强自动化端到端验证并继续收敛技术债。
