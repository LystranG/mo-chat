# MoChat 项目技术文档

本文档基于当前仓库 `main` 分支代码整理，目标是从工程实现视角说明：这个项目要解决什么问题、当前是如何实现的、依赖了哪些技术、以及目前还存在哪些明显缺口。

这不是需求文档，也不是未来规划文档，而是对“当前代码实际状态”的一次技术盘点。

---

## 1. 项目定位与当前状态

MoChat 的目标是实现一个面向单机高吞吐场景的 IM 后端，并提前保留向云原生/微服务演进的边界。当前仓库已经完成了以下几类工作：

- 建立了 Gradle 多模块结构，按职责拆分为 `app`、`common`、`protocol`、`connection-module`、`logic-module`、`infra-redis`、`persistence-module`。
- 建立了协议层、连接层、业务层、持久化层的基本骨架。
- 实现了部分关键链路，包括私聊消息接入、顺序号分配、幂等去重、RocketMQ 有序发送、发送 ACK、离线补推、历史查询、回执状态维护等。
- 补充了较多模块级测试，当前仓库的 `./gradlew test` 可以通过。

但从“可运行的完整系统”角度看，当前项目仍然是一个“模块化骨架 + 局部关键能力已落地”的状态，而不是一个已经完整装配完成的生产服务，核心原因包括：

- `app` 模块目前只是 Micronaut 启动壳和 Native Image 打包壳，尚未把其他模块真正装配成一个运行时应用。
- 大量社交图谱、群管理 HTTP 接口仍处于占位状态。
- Redis、RocketMQ、PostgreSQL 的很多实现已经存在，但部分能力还停留在“库代码可用、运行时未接线”的阶段。
- 数据模型、OpenSpec、设计文档与当前实现之间已经出现一些漂移，尤其是历史分页从 `msgId` 游标转向了 `seq` 游标。

一句话概括当前阶段：

> 这是一个设计边界较清晰、局部主链路已经可测，但整体运行时装配仍未完成的一期 IM 后端原型。

---

## 2. 模块架构总览

| 模块 | 主要职责 | 当前完成度 | 关键问题 |
|---|---|---|---|
| `app` | 应用入口、Micronaut 启动、Native Image 打包 | 低 | 没有把业务模块真正装配起来 |
| `common` | 定义事件总线、会话锁、幂等、离线队列、用户连接目录等抽象 | 高 | 接口清晰，但生产实现不完整 |
| `protocol` | 定义固定头协议、消息类型、错误码、protobuf schema | 高 | 协议定义领先于实际运行时接入 |
| `connection-module` | Netty 接入层、TLS、拆帧、限流、心跳、入站/出站事件桥接 | 中 | 运行时未接入 `app`，错误响应与完整心跳未闭环 |
| `logic-module` | 登录/会话、消息摄入、ACK、回执、历史查询、会话状态查询 | 中 | 私聊链路较完整，群聊与社交图谱明显不完整 |
| `infra-redis` | Redis 版事件总线、seq 生成器、幂等存储、离线队列 | 中高 | 可用，但与真实运行时装配存在距离 |
| `persistence-module` | Flyway migration、JDBC 落库、会话状态更新、群缓存写入 | 中 | 事务内核已实现，但真正 MQ 消费闭环未接通 |

### 2.1 模块边界的核心思想

项目整体采用“模块化单体 + 显式抽象接口”的方式组织：

- `common` 定义跨模块能力接口，减少业务逻辑对底层中间件的直接耦合。
- `connection-module` 负责 TCP 接入、协议处理与出入站桥接。
- `logic-module` 负责会话鉴权、幂等、顺序、ACK、查询等业务规则。
- `persistence-module` 负责消息持久化、会话最新状态维护、群消息缓存更新。
- `infra-redis` 用 Redis 为热点路径提供共享状态能力。

这个拆法解决的问题是：即使当前还是单体仓库，未来也能相对平滑地把 Redis、MQ、连接层、持久化层替换为更独立的部署单元。

### 2.2 当前最重要的现实约束

虽然模块划分清晰，但当前 `app/build.gradle.kts` 只依赖 Micronaut 自身，没有依赖 `logic-module`、`connection-module`、`infra-redis`、`persistence-module`。这意味着：

- 即使 `logic-module` 中定义了 `@Controller`，当前 `app` 也不会把这些控制器打进最终运行时类路径。
- 即使 `connection-module` 中实现了 `NettyChatServer`，当前也没有地方创建并启动它。
- 即使已经写好了 Redis、PostgreSQL、RocketMQ 相关实现，当前应用入口仍然只是一个能启动上下文的空壳。

对应文件：

- `app/build.gradle.kts`
- `app/src/main/java/com/github/lystran/mochat/Application.java`
- `app/src/main/resources/application.yml`

---

## 3. 核心技术栈

### 3.1 语言、构建与框架

- Java 25
- Gradle Kotlin DSL 多模块构建
- Micronaut 4.9.0

### 3.2 网络与协议

- Netty 4.1.108.Final
- Linux 下优先 `io_uring`，回退 `epoll` 或 `NIO`
- Protobuf 4.30.2
- 自定义固定头协议（11 字节 header + protobuf body）

### 3.3 中间件与存储

- Redis / Lettuce 6.7.1.RELEASE
- RocketMQ Client 5.3.2
- PostgreSQL JDBC 42.7.5
- Flyway 10.20.1
- Caffeine 3.2.1

### 3.4 辅助技术

- Apache Commons Codec：严格 Base64 校验
- JUnit 5、Mockito、Micronaut Test、Testcontainers
- GraalVM Native Build Tools

---

## 4. 关键功能实现说明

这一部分按“解决了什么问题 / 如何实现 / 用了什么技术 / 当前缺陷”来描述。

### 4.1 应用启动与打包

#### 解决的问题

- 提供统一应用入口。
- 提供 JVM 运行和 Native Image 构建基础。

#### 当前实现

- `Application` 只做 `Micronaut.run(...)`。
- `application.yml` 目前仅包含应用名 `mochat`。
- `app` 模块配置了 `nativeCompile`，并在缺少 `native-image` 时跳过构建，而不是直接失败。

#### 关键文件

- `app/src/main/java/com/github/lystran/mochat/Application.java`
- `app/src/main/resources/application.yml`
- `app/build.gradle.kts`

#### 使用技术

- Micronaut Runtime
- GraalVM Native Build Tools
- Logback / SnakeYAML

#### 当前缺陷

- `app` 没有把其他业务模块装配进来。
- 没有真正启动 HTTP 服务或 Netty TCP 服务。
- 没有接入 Redis、RocketMQ、PostgreSQL、TLS 证书等运行时配置。
- Native Image 支持目前更像“预留入口”，不是完整交付链路。

### 4.2 协议定义与帧结构

#### 解决的问题

- 让连接层和业务层共享统一的线协议定义。
- 避免消息类型、错误码、头结构在多个模块中重复维护。

#### 当前实现

- `protocol` 模块定义了：
  - 固定头字段偏移和默认最大帧长。
  - `MsgType`、`SerializerType`、`ErrorCode` 枚举。
  - protobuf schema：心跳、发送 ACK、错误响应、私聊/群聊请求、消息投递、客户端接收回执、二阶段送达 ACK。
- 固定头格式为：
  - magic 4 字节
  - version 1 字节
  - msgType 1 字节
  - serializer 1 字节
  - bodyLength 4 字节

#### 关键文件

- `protocol/src/main/java/com/github/lystran/mochat/protocol/FrameConstants.java`
- `protocol/src/main/java/com/github/lystran/mochat/protocol/MsgType.java`
- `protocol/src/main/java/com/github/lystran/mochat/protocol/SerializerType.java`
- `protocol/src/main/java/com/github/lystran/mochat/protocol/ErrorCode.java`
- `protocol/src/main/proto/mochat/v1/chat.proto`

#### 使用技术

- Protobuf Gradle 插件
- Protobuf Java runtime

#### 当前缺陷

- 协议层定义了 `ERROR_RESPONSE`、`SERVER_HEARTBEAT` 等能力，但运行时大多还没有真正使用。
- 协议魔数目前在连接层中重复定义，没有统一收口到 `protocol` 模块。

### 4.3 Netty 接入层与 TLS

#### 解决的问题

- 提供高性能 TCP 接入。
- 统一承载 TLS、拆帧、限流、心跳和消息路由。
- 兼顾 Linux 高性能 transport 和跨环境回退能力。

#### 当前实现

- `NettyChatServer` 启动时优先尝试 `io_uring`，失败回退到 `epoll`，再退到 `NIO`。
- `buildTls13Context(...)` 明确要求 TLS 1.3。
- `ChatChannelInitializer` 构建 pipeline：
  - `tls`
  - `frameDecoder`
  - `protobufDecodePlaceholder`
  - `rateLimit`
  - `heartbeat`
  - `inboundRouter`

#### 关键文件

- `connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`

#### 使用技术

- Netty
- OpenSSL/JDK SSL provider
- `LengthFieldBasedFrameDecoder`

#### 当前缺陷

- 连接层不是 Micronaut 生命周期组件，当前没有被 `app` 自动启动。
- TLS 上下文虽已支持构建，但证书路径和运行时装配未完成。
- 连接层当前只做头字段校验，不做真正的 protobuf 可解析性校验。
- 当前项目没有真实在线运行验证，只能证明模块级测试通过。

### 4.4 限流与心跳

#### 解决的问题

- 防止单连接高频刷消息压垮服务。
- 在客户端无响应时及时回收连接资源。

#### 当前实现

- `RateLimitHandler` 实现每连接一个令牌桶。
- 默认桶容量为 1000 msg/s，100ms 补桶一次，底层使用 `HashedWheelTimer`。
- 令牌耗尽时直接关闭连接。
- `HeartbeatHandler` 在每次读消息时重置空闲超时；若超时未收到任何消息，则关闭连接。
- 收到 `CLIENT_HEARTBEAT` 时只触发内部用户事件，不会下沉到业务消息链路。

#### 关键文件

- `connection-module/src/main/java/com/github/lystran/mochat/connection/RateLimitHandler.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java`

#### 使用技术

- Netty handler
- Netty `HashedWheelTimer`

#### 当前缺陷

- 限流参数没有从配置读取，仍是写死默认值。
- 限流时直接关连接，没有返回 `RATE_LIMITED` 标准错误帧。
- 心跳实现目前更像“读空闲关闭”，没有主动发送 `SERVER_HEARTBEAT`。
- 心跳默认超时时间是 60 秒，与需求文档中的 10s/5s 配置目标不一致。

### 4.5 登录/注册与身份公钥

#### 解决的问题

- 支持无密码登录。
- 支持首次登录时登记身份公钥，并保证公钥不可篡改。
- 为后续私聊 E2EE 奠定身份基础。

#### 当前实现

- `AuthController` 暴露 `POST /auth/login`。
- `UserService.loginOrRegister(...)` 的规则：
  - 用户名为空则拒绝。
  - 新用户必须提交 `publicKey`。
  - `publicKey` 必须是严格 Base64，解码长度必须恰好 32 字节。
  - 老用户如果再次提交 `publicKey`，必须与已存值完全一致。
- 成功登录后调用 `SessionService.issueSession(...)` 生成会话。
- 登录成功后会触发 `OfflineReplayService.replayOnLogin(...)`，做离线消息补推。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/UserService.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/InMemoryUserRepository.java`

#### 使用技术

- Micronaut HTTP Controller
- Apache Commons Codec 严格 Base64 解码

#### 当前缺陷

- 用户与公钥当前只保存在内存仓储，进程重启后会丢失。
- 没有 PostgreSQL 版 `UserRepository`。
- 没有 Redis 版公钥缓存实现。
- 这意味着“身份公钥持久化”目前只实现了校验逻辑，没有实现真正的持久化目标。

### 4.6 Session 管理

#### 解决的问题

- 给登录用户发放 `sessionId`。
- 供 TCP 消息链路和 HTTP 查询接口做用户身份绑定。

#### 当前实现

- `SessionService.issueSession(...)` 使用 `UUID.randomUUID()` 生成 session。
- Redis 存储：`mochat:session:<sessionId> -> userId`。
- 同时使用 Caffeine 维护本地缓存。
- `resolveUserId(...)` 负责从 session 找 userId，并在读路径中修正本地缓存。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`

#### 使用技术

- Redis / Lettuce
- Caffeine
- UUID

#### 当前缺陷

- 当前读路径每次都是先查 Redis，再刷新本地 Caffeine；Caffeine 没有真正承担“L2 热读缓存”的角色。
- session 没有 TTL。
- `revoke(...)` 虽然存在，但当前没有对应的 HTTP 注销接口。
- 协议层定义了 `SESSION_EXPIRED`，但当前实现没有过期语义。

### 4.7 入站消息摄入与鉴权

#### 解决的问题

- 把连接层收到的消息转成业务层可以处理的请求。
- 在业务入口层做会话鉴权和消息类型分发。

#### 当前实现

- 连接层把入站消息编码为字符串事件：`MsgType|SerializerType|Base64(body)`，发布到 `connection.inbound`。
- `InboundMessageConsumer` 作为 `@Context` Bean 订阅该 topic。
- 它负责：
  - 解析消息类型与序列化类型。
  - 只接受 `PROTOBUF`。
  - 对 `PRIVATE_MESSAGE`、`GROUP_MESSAGE`、`CLIENT_RECEIVE_ACK` 分别做 protobuf 解析。
  - 使用 `sessionId` 解析发送者/接收者身份。

#### 关键文件

- `connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`

#### 使用技术

- EventBus
- Protobuf
- Redis session 查找

#### 当前缺陷

- 事件总线边界使用字符串协议，缺少强类型约束。
- 非法消息大多是静默丢弃，没有返回标准 `ERROR_RESPONSE`。
- 连接层与业务层之间存在重复编码/解码成本。

### 4.8 持久化前清理 `sessionId`

#### 解决的问题

- 避免把会话令牌落库，防止 session/token 泄漏。

#### 当前实现

- `InboundMessageConsumer` 在处理私聊和群聊请求时，不直接持久化原始 protobuf，而是调用 `encodeWithoutSession(...)`：
  - 构造请求副本。
  - 清除 `sessionId` 字段。
  - 再进行 Base64 编码后传入下游链路。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`

#### 使用技术

- Protobuf builder
- Base64 编码

#### 当前缺陷

- 这个安全约束已经落地，但尚未在高层设计/对外文档中形成明确的安全规则说明。

### 4.9 消息顺序、幂等与 `msgId` 分配

#### 解决的问题

- 保证同一会话内消息顺序单调递增。
- 防止客户端重试造成重复消息。
- 为消息提供全局唯一 ID 和服务端时间戳。

#### 当前实现

- `MessageIngestService.ingest(...)` 的核心步骤：
  - 先按 `conversationId` 获取 `ConversationLock`。
  - 通过 `IdempotencyStore.find(senderUid, clientMsgId)` 判断是否重复。
  - 对非重复请求调用 `ConversationSeqGenerator.next(conversationId)` 获取下一个 `seq`。
  - 调用 `IdGenerator.nextId()` 分配 `msgId`。
  - 用系统时钟生成 `serverTimeMs`。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- `common/src/main/java/com/github/lystran/mochat/common/lock/ConversationLock.java`
- `common/src/main/java/com/github/lystran/mochat/common/seq/ConversationSeqGenerator.java`
- `common/src/main/java/com/github/lystran/mochat/common/idempotency/IdempotencyStore.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`

#### 使用技术

- JUC 细粒度锁
- Redis `INCR`
- Redis `SET NX PX`
- Snowflake 风格 ID 抽象

#### 当前缺陷

- 当前幂等 value 只存 `msgId + seq`，没有存设计里提到的 `serverTime`。
- `IdGenerator` 只是接口，当前文档层面没有看到统一的实际生产实现说明。
- 群聊与私聊共用这条主链，但群聊业务校验并不完整。

### 4.10 RocketMQ 有序发送与发送 ACK

#### 解决的问题

- 保证同一会话的消息按顺序进入 MQ。
- 只有在 MQ 发送成功后才向发送方回 ACK。

#### 当前实现

- `RocketMqProducer.publishOrdered(...)` 用 `conversationId` 作为分片键选择队列。
- `MessageIngestService` 在发送成功后：
  - 更新幂等存储。
  - 发送 `SEND_ACK` 到 `connection.outbound`。
- 若幂等命中，则不重复投递 MQ，但会重复返回同一个 `msgId/seq` 对应的 ACK。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`

#### 使用技术

- RocketMQ Java Client
- 自定义 `MessageQueueSelector`
- EventBus 出站桥接

#### 当前缺陷

- 当前 MQ payload 是 `|` 分隔字符串，不是 protobuf 或 JSON，契约表达偏弱。
- 代码里只能证明“MQ send 返回成功后再 ACK”，但不能直接证明“Broker 已同步刷盘”这一更强语义。
- 真正的 MQ 消费端闭环尚未在运行时接通。

### 4.11 私聊在线投递与送达回执

#### 解决的问题

- 在消息接入成功后，尽快把私聊消息实时推送给在线接收方。
- 提供二阶段送达回执，反映“接收方客户端已确认收到”。

#### 当前实现

- 私聊场景下，`MessageIngestService` 会额外调用 `emitPrivateDelivery(...)`。
- 该方法把请求中的密文字段重新封装为 `ChatMessageDelivery`，发布到 `connection.outbound`。
- 接收方客户端上报 `CLIENT_RECEIVE_ACK` 后：
  - `ReceiptService` 检查会话存在、用户是参与方、回执 seq 未超过服务端已知最新 seq。
  - 更新 `conversations.uid_1_seq` 或 `uid_2_seq`。
  - 再向发送方发布 `DELIVERED_ACK`。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptService.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/JdbcReceiptConversationStateStore.java`

#### 使用技术

- EventBus
- Protobuf
- JDBC
- Caffeine 读时补偿缓存

#### 当前缺陷

- 当前二阶段回执实际上已经存在，但需求与 OpenSpec 文档没有完全同步。
- `DELIVERED_ACK` 不会进入离线队列，因此发送方离线时可能错过送达反馈。
- 私聊请求对 `nonce == 12 bytes`、`ciphertext 必填` 的业务校验尚未完整实现。

### 4.12 离线队列与登录重放

#### 解决的问题

- 当接收方离线或写 channel 失败时，避免消息直接丢失。
- 在用户重新登录时尝试补发离线消息。

#### 当前实现

- 连接层 `OutboundEventSubscriber` 订阅 `connection.outbound`。
- 当目标用户在线时，重新编码协议帧并 `writeAndFlush` 到 channel。
- 用户不在线或写失败时，把 payload 放入 `OfflineQueue`。
- `RedisOfflineQueue` 的实现：
  - `RPUSH`
  - `LTRIM` 保留最近 50 条
  - `LPOP count` 批量取出
- 登录成功后，`OfflineReplayService.replayOnLogin(...)` 会最多重放 50 条离线消息。
- 若重放过程中再次失败，会把当前消息和剩余消息重新入队。

#### 关键文件

- `connection-module/src/main/java/com/github/lystran/mochat/connection/OutboundEventSubscriber.java`
- `common/src/main/java/com/github/lystran/mochat/common/offline/OfflineQueue.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/OfflineReplayService.java`

#### 使用技术

- Redis List
- EventBus
- Netty channel 写出

#### 当前缺陷

- 离线队列存的是完整 payload，而不是轻量引用，Redis 占用会更高。
- 重放失败后重新入队的策略可能带来顺序漂移。
- `UserChannelDirectory` 当前缺少生产实现，在线投递能力尚缺关键拼图。

### 4.13 历史消息查询

#### 解决的问题

- 为客户端提供按会话拉取消息历史的能力。
- 返回原始消息 payload 的 base64 文本，便于客户端自行恢复 protobuf。

#### 当前实现

- `HistoryController` 提供 `/history`。
- 查询前先校验：
  - `sessionId` 是否有效。
  - 请求人是否拥有该 `conversationId` 的访问权限。
- `HistoryService` 默认 `limit = 50`。
- `JdbcHistoryRepository` 目前使用 `conversation_id + seq` 进行分页：
  - 无游标：`ORDER BY seq DESC LIMIT ?`
  - 有游标：`WHERE seq < ? ORDER BY seq DESC LIMIT ?`

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java`

#### 使用技术

- Micronaut HTTP
- JDBC
- PostgreSQL

#### 当前缺陷

- 当前实现用的是 `seq` 游标，而不是需求文档里写的 `msgId` 游标。
- 代码和文档已经发生模型漂移，后续必须统一。
- 目前没有最大页大小上限保护，只做了默认值，没有做强上限限制。

### 4.14 会话状态查询与访问控制

#### 解决的问题

- 为客户端查询当前会话最新状态。
- 为私聊显示“对端已读到哪里”提供读接口。
- 防止历史/状态接口发生 IDOR（越权按会话 ID 读取他人数据）。

#### 当前实现

- `ConversationController` 提供：
  - `/conversations/{conversationId}/private-peer-received-seq`
  - `/conversations/{conversationId}/state`
- `ConversationStateService` 调用 `JdbcConversationStateRepository`：
  - 对私聊，从 `user_friendships` 判断参与者身份。
  - 对群聊，从 `group_memberships` 判断是否是活跃成员。
- `HistoryController` 与 `ConversationController` 现在都在查询前做会话访问控制检查。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/ConversationController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcConversationStateRepository.java`

#### 使用技术

- Micronaut HTTP
- JDBC
- PostgreSQL

#### 当前缺陷

- 当前访问控制已经覆盖“是否属于该会话”，但并未在历史/状态查询中考虑“好友关系已被拉黑后是否仍允许查看历史”这类更细业务规则。

### 4.15 持久化事务与会话最新状态更新

#### 解决的问题

- 把消息持久化和会话最新状态更新放进同一数据库事务。
- 避免消息已入库、会话状态未更新或反之的局部不一致。

#### 当前实现

- `MqConsumer.persistMessage(...)` 的事务步骤：
  - `insert messages`
  - `update conversations.latest_seq/latest_message_time`
  - `commit`
- `ConversationRepository.updateLatestState(...)` 仅在新消息 `seq` 更大时更新最新状态。
- 私聊回执更新使用 `GREATEST` 保证 `uid_1_seq/uid_2_seq` 单调递增。

#### 关键文件

- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`

#### 使用技术

- JDBC
- PostgreSQL
- 手动事务控制

#### 当前缺陷

- `MqConsumer` 只是事务持久化内核，不是一个真正接入 RocketMQ 的消费者。
- 当前仓库中没有完整实现“MQ 消费成功 -> DB 提交成功 -> ACK MQ”这一真实闭环。

### 4.16 群消息缓存

#### 解决的问题

- 提高群聊最近消息读取效率。
- 控制缓存容量，避免无限增长。
- 确保缓存只在 DB 提交成功后更新。

#### 当前实现

- `GroupMessageCache` 同时维护：
  - L1：Caffeine，按 `groupId -> seq -> payload` 存储。
  - L2：Redis ZSET，score 为 `seq`。
- 每个群最多缓存 500 条。
- Redis 侧通过 `zcard + zremrangebyrank` 控制容量。
- L1 侧通过 `ConcurrentSkipListMap.pollFirstEntry()` 淘汰最旧消息。
- 只在持久化事务提交成功后调用 `cache(...)`。

#### 关键文件

- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`

#### 使用技术

- Caffeine
- Redis ZSET
- PostgreSQL 提交后回调式更新

#### 当前缺陷

- 当前只有缓存写路径，没有完整的 Redis 回源读取路径。
- `recentMessages(...)` 只读 L1，本质上不是完整的 L1/L2 双层读缓存。
- 群聊的业务链路整体还不完整，所以这个缓存目前更像“基础设施先行”。

### 4.17 社交图谱与群管理 HTTP 接口

#### 解决的问题

- 需求上需要支持好友、群组、好友申请、加群申请、拉黑等社交能力。

#### 当前实现

- 目前只存在占位控制器：
  - `FriendsController`
  - `GroupsController`
- 返回的是空列表和“稍后任务实现”的说明。

#### 关键文件

- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/FriendsController.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/http/GroupsController.java`

#### 使用技术

- Micronaut HTTP Controller

#### 当前缺陷

- 这一块从功能角度几乎还未真正开始。
- 与一期需求文档相比差距最大。

---

## 5. 数据模型与“事实来源”

### 5.1 设计 DDL 与实际 migration 的关系

当前项目存在两个“表结构来源”：

- 设计基线：`docs/ddl/phase1.sql`
- 真实 migration：`persistence-module/src/main/resources/db/migration/V1__phase1.sql`

两者已经发生明显漂移，当前应该把 **Flyway migration** 视为更接近实现真相的来源。

### 5.2 当前更接近真实实现的表模型

从 migration 看，当前持久化模型的核心表包括：

- `users`
- `user_friendships`
- `friend_requests`
- `groups`
- `group_memberships`
- `group_join_requests`
- `conversations`
- `messages`

其中 `conversations` 是当前实现里非常关键的一张表，用于维护：

- `latest_seq`
- `latest_message_time`
- `uid_1_seq`
- `uid_2_seq`

这说明系统当前已经从“只有消息表”的思路，演进到“会话状态 + 消息表”的模型。

### 5.3 一个重要的现实变化：分页主键从 `msgId` 变成了 `seq`

当前实现里，历史查询和会话最新状态都围绕 `conversation_id + seq` 展开，这与最初文档中“按 `msgId + limit` 分页”的表述已经不一致。后续维护时必须明确一个原则：

> 当前代码的真实分页语义是“会话内顺序号 `seq` 游标”，不是“全局 `msgId` 游标”。

---

## 6. 测试与质量现状

### 6.1 已覆盖的重点

当前测试覆盖了以下重点链路：

- 协议常量、枚举、protobuf round-trip
- Netty 连接层的拆帧、限流、空闲超时、出站事件处理
- 登录/注册、公钥校验、session 逻辑
- 私聊消息摄入、幂等、seq 分配、发送 ACK
- 私聊二阶段回执
- 离线补推
- 历史查询和会话状态查询
- Flyway migration 和持久化事务行为
- 群缓存容量与事务后写入

### 6.2 当前测试风格

- 很多测试是模块级单元测试或半集成测试。
- `logic-module` 和 `connection-module` 里大量使用 Mockito 模拟依赖。
- `infra-redis` 和 `persistence-module` 使用了 Testcontainers 验证 Redis/PostgreSQL 行为。

### 6.3 当前测试不足

- 缺少一个真正把 `app + connection + logic + redis + mq + postgres` 全部串起来的端到端集成测试。
- 群聊成员校验、好友拉黑后发消息、`nonce` 长度校验等关键业务规则仍未补齐测试。
- 社交图谱与群管理接口几乎没有测试，因为功能本身尚未实现。

---

## 7. 当前主要缺陷与技术债

这里列出当前最值得关注的问题，优先级从高到低大致如下。

### 7.1 运行时装配未完成

- `app` 没有依赖其他核心模块。
- 连接层、业务层、持久化层还没有形成真正的统一启动链路。

### 7.2 用户、公钥没有真正持久化

- 当前仍使用 `InMemoryUserRepository`。
- 这意味着登录/注册虽然逻辑存在，但“账户体系”并未真正落地。

### 7.3 社交图谱与群管理功能基本未实现

- `FriendsController`、`GroupsController` 只是占位实现。
- 与一期需求差距很大。

### 7.4 历史分页模型与文档不一致

- 文档写 `msgId` 游标。
- 实现已转为 `seq` 游标。
- 当前必须统一，不然会持续制造误解。

### 7.5 MQ 消费闭环未真正落地

- 有生产者。
- 有事务持久化服务。
- 但没有真正的 RocketMQ 消费者装配。

### 7.6 EventBus 边界过于脆弱

- 使用字符串协议而非强类型事件。
- 维护成本低，但长期演进成本高。

### 7.7 Session Caffeine 不是实际热读缓存

- 每次解析 session 都先打 Redis。
- 热路径优化效果有限。

### 7.8 连接层错误处理不完整

- 对非法消息通常直接丢弃或关闭连接。
- 缺少标准错误帧返回。

### 7.9 心跳与限流仍偏原型化

- 心跳未形成完整请求/响应协议。
- 限流配置未参数化。
- 都还不算“可运维的正式实现”。

### 7.10 群缓存只有写路径，没有完整读路径

- Redis 已经写了。
- 但当前缓存读取仍只看本地 L1。

### 7.11 存在潜在集成风险：`kind` 大小写不一致

- 逻辑层 `MessageIngestRequest` 使用 `PRIVATE/GROUP`。
- 持久化层和数据库约束使用 `private/group`。
- 一旦未来直接映射，可能触发数据库约束失败。

### 7.12 Redis Pub/Sub 事件总线天然不可靠

- 它非常适合实时通知。
- 但不是可靠消息系统。
- 如果未来更依赖它来承担关键链路，需要额外补偿或替换方案。

---

## 8. 已经体现出的优点

虽然项目还有很多未完成部分，但当前实现已经体现出一些很好的工程方向：

- 模块边界清晰，职责拆分基本合理。
- 私聊消息主链路已经形成较完整的骨架：鉴权、幂等、顺序、MQ 发送、ACK、离线补推、回执。
- 安全意识是存在的，例如：
  - 历史/状态接口增加了会话访问控制。
  - 入站 protobuf 在持久化前会移除 `sessionId`。
- `ReceiptConversationStateStore` 使用“DB + server-known 缓存”做读时补偿，这个设计对一致性窗口问题考虑得比较细。
- 群缓存明确放在事务提交后更新，避免了缓存先行导致的数据漂移。
- 测试数量和覆盖面对于原型项目来说已经不算少。

---

## 9. 后续建议

如果要把当前项目继续推进到“真正可运行的一期系统”，建议优先按下面顺序补齐：

1. 完成 `app` 对各模块的真实装配，至少让 HTTP 控制器和 Netty 服务都能启动。
2. 实现 PostgreSQL 版 `UserRepository`，把用户、公钥、会话模型真正落库。
3. 打通 RocketMQ 消费端到持久化模块的真实运行链路。
4. 统一历史分页模型，明确到底以 `msgId` 还是 `seq` 为准，并同步所有文档。
5. 完成群聊成员校验、好友拉黑校验、私聊 `nonce/ciphertext` 校验等关键业务规则。
6. 将社交图谱和群管理接口从占位状态推进到真正实现。
7. 把 EventBus 边界逐步从字符串协议升级为更稳定的结构化事件契约。
8. 完善端到端集成测试，验证真正的“连接层 -> 逻辑层 -> MQ -> 持久化 -> 查询”闭环。

---

## 10. 结论

当前 MoChat 代码库最有价值的部分，不是它已经“做完了一期”，而是它已经把一期中最复杂、最容易设计混乱的几个技术点提前拆开并形成了清晰边界：

- 协议与连接层分离
- 业务逻辑与基础设施分离
- 热路径状态交给 Redis
- 持久化真相交给 PostgreSQL
- 顺序与幂等由独立抽象负责

这使得它虽然尚未完成，但已经具备了继续往下做的良好底子。

从代码现状看，当前最准确的判断不是“功能已经完备”，而是：

> 该项目已经完成了一套结构上合理、主链路部分可用、测试较充分的一期 IM 后端骨架；下一阶段的重点应从“补模块代码”转向“补运行时装配、统一模型语义、补齐未完成的业务闭环”。
