# MoChat 技术总览

本文档只描述当前代码库中已经落地并得到确认的技术事实，用于说明系统边界、运行时装配、核心能力、数据真相来源和主要缺口。

操作步骤、启动命令和排障细节不放在这里；这些内容统一放在 `docs/runbook.md`。

## 1. 系统定位

MoChat 当前采用“模块化单体 + 显式接口边界”的形态，目标是在单机高吞吐 IM 场景下交付可运行的一期后端，同时为后续拆分为更独立的运行单元保留边界。

当前仓库已经不是单纯的骨架工程，而是一个可启动、可迁移、可测试的运行时组合：

- `app` 负责 Micronaut 装配、生命周期管理和 Native Image 构建入口。
- `common` 提供事件总线、幂等、会话锁、离线队列和用户连接目录等跨模块抽象。
- `protocol` 定义 TCP 固定头、错误码和 protobuf 协议。
- `connection-module` 负责 Netty TCP、TLS、拆帧、心跳、限流、Session 绑定和出入站桥接。
- `logic-module` 负责登录注册、Session、消息摄入、ACK、历史查询、会话状态、好友与群管理 HTTP 生命周期。
- `infra-redis` 提供 Redis 驱动的 `seq`、事件分发、幂等和离线队列实现。
- `persistence-module` 负责 Flyway migration、JDBC 真相存储、RocketMQ 持久化消费和群消息缓存。

## 2. 运行时与配置事实

### 2.1 装配方式

`app` 已经把以下运行时能力装配到同一个 Micronaut 进程中：

- HTTP 服务
- Netty TCP 聊天入口
- PostgreSQL `DataSource`
- Flyway migration
- Redis 客户端与 Pub/Sub
- RocketMQ producer / consumer
- JDBC 仓储与持久化消费链路

运行时核心装配集中在 `MochatRuntimeFactory` 及各类 lifecycle Bean，而不是放在 `Application` 主类中。

### 2.2 TLS 现实

聊天 TCP 当前是强制 TLS 的，这一点不是文档约定，而是运行时约束：

- `mochat.tls.enabled=false` 会直接触发 fail-fast，运行时不会退化为明文 TCP。
- `mochat.tls.certificate-path` 与 `mochat.tls.private-key-path` 必须成对提供。
- 如果没有显式提供证书材料，则只有在 `mochat.tls.self-signed=true` 时才会生成自签名证书启动。
- 当显式提供证书链与私钥时，运行时优先使用用户提供的材料。

### 2.3 配置文件角色

- `app/src/main/resources/application.yml` 提供运行时默认配置。
- `app/src/main/resources/application-local.yml` 仍被 git 跟踪，用于本地依赖与 TLS 相关覆盖值的项目内默认约定。

`application-local.yml` 当前保留了本地 PostgreSQL、Redis、RocketMQ 与 TLS 相关配置键，因此它属于受版本控制的本地支持文件，而不是临时个人文件。

## 3. 已实现的核心能力

### 3.1 认证、用户与 Session

- `AuthController` 提供登录入口。
- 首次登录要求提供 32 字节公钥；已存在用户再次登录时会校验公钥一致性。
- 运行时有 `DataSource` 时优先走 `JdbcUserRepository`，否则回退到内存实现。
- `SessionService` 使用 Redis 保存 `sessionId -> userId`，并配合本地缓存读取。
- 登录成功后会触发离线消息重放。

### 3.2 连接层与在线投递

- `ChatChannelInitializer` 已组装 TLS、拆帧、限流、心跳、Session 绑定和入站路由。
- `SessionBindingHandler` 会在首个带 `sessionId` 的业务帧上解析会话并绑定用户与 Netty Channel。
- `HeartbeatHandler` 会发送服务端心跳并在客户端超时后关闭连接、清理绑定。
- `OutboundEventSubscriber` 负责把 `connection.outbound` 事件编码为二进制帧，并优先写回在线连接。
- `DELIVERED_ACK` 被排除在离线队列之外，避免回执消息积压。

### 3.3 消息摄入、顺序、幂等与回执

- `InboundMessageConsumer` 处理私聊、群聊和客户端送达 ACK。
- `MessageIngestService` 在会话锁内完成幂等判断、`seq` 分配、`msgId` 分配、RocketMQ 有序发送和发送方 ACK。
- 私聊发送前会校验会话参与者与关系约束；群聊发送前会校验成员资格。
- `ReceiptService` 支持私聊二阶段送达回执，并将最新接收 `seq` 写回会话状态。

### 3.4 历史查询与会话状态

- 历史查询基于 `conversation_id + seq` 工作，而不是仅依赖时间戳。
- 支持无游标、游标分页和 `startSeq/endSeq` 范围查询。
- 会话状态查询和历史查询都会先做 Session 鉴权及会话访问控制。

### 3.5 好友与群管理

基础 HTTP 生命周期入口已经落地，当前已覆盖：

- 好友申请、处理、列表、删除好友、拉黑、解黑
- 建群、群列表、退群、踢人、解散
- 入群申请、审批

这部分已经具备 JDBC 真相仓储和对应测试覆盖，但更细粒度的权限约束仍未补齐。

## 4. 数据与持久化事实

### 4.1 真相来源

数据库相关事实以以下两类实现为准：

- Flyway migration
- JDBC 仓储代码

`docs/ddl/phase1.sql` 仍可作为设计参考，但当它与 migration 或仓储实现不一致时，应以 migration 和代码为准。

### 4.2 当前关键表

当前主干数据模型包含：

- `users`
- `user_friendships`
- `friend_requests`
- `groups`
- `group_memberships`
- `group_join_requests`
- `conversations`
- `messages`

其中 `conversations` 维护 `latest_seq`、`latest_message_time`、`uid_1_seq` 和 `uid_2_seq`，因此它不仅是会话索引表，也是回执与状态查询的重要事实来源。

### 4.3 Flyway 状态

当前群入群申请的 pending 唯一约束已经从基线 migration 中拆出，采用独立版本化 migration 维护：

- `V1__phase1.sql` 保留基线表结构与通用索引
- `V2__group_join_requests_pending_pair_uniq.sql` 创建 `group_join_requests_pending_pair_uniq`

这样可以兼容“旧库已经执行过 `V1`”的升级路径，避免通过修改基线 migration 破坏 Flyway checksum。

### 4.4 持久化与缓存链路

- `RocketMqPersistenceConsumer` 负责消费逻辑层发出的持久化消息。
- `MqConsumer.persistMessage(...)` 会在一个 JDBC 事务内写 `messages` 并推进 `conversations` 状态。
- 群消息缓存只在事务提交成功后更新。
- `GroupMessageCache` 当前具备写路径、L1 读取和 Redis ZSET 作为 L2 存储，但尚未形成完整的 L2 回源读链路。

## 5. 验证状态

当前仓库已经具备持续可运行的自动化验证基础：

- Flyway 升级路径测试已覆盖 legacy `V1` 升级到当前 migration 后补建 `group_join_requests_pending_pair_uniq` 的场景。
- 群仓储集成测试和整仓测试已经在修复前后的主干候选结果上完成 fresh 通过。
- 本仓库也已有 JVM 启动验证和 Native Image 构建验证。

这些事实说明当前代码库已经具备：

- 可迁移的数据库模式
- 可合并的主干状态
- 可重复执行的整仓自动化测试基线

## 6. 主要缺口

当前最主要的技术缺口可以收敛为以下几类：

1. 连接层虽然具备 Session 绑定、在线写回和离线重放基础设施，但缺少更完整的 TCP 端到端自动化验证。
2. 私聊主链路已经具备核心处理能力，但独立的私聊会话初始化入口仍未补齐，因此不能把“空数据库直接发起完整私聊链路”视为已完成能力。
3. 好友与群管理已有基础 HTTP 生命周期入口，但更细粒度的群权限规则和一致性约束仍待补齐。
4. `DELIVERED_ACK` 不做离线补推，发送方离线时不会收到补偿性送达反馈。
5. EventBus 与 MQ envelope 仍以字符串协议为主，契约表达和长期演进性都偏弱。
6. 群消息缓存尚未形成完整的 L1/L2 回源读链路。
7. Session 仍缺少 TTL 与真正的过期语义。
8. 历史查询目前只覆盖会话级访问控制，尚未扩展到更细的业务可见性规则。
9. 测试运行时仍存在一些非阻塞性 JDK / 依赖告警，但不影响当前测试通过。

## 7. 总体判断

MoChat 当前已经具备统一运行时装配、持久化 schema 管理、消息主链路处理、基础好友与群管理入口，以及可重复执行的整仓测试基线。

它仍然不是功能完全闭环的一期成品，但也不再是“只有骨架的原型”。更准确的判断是：

> 这是一个已经完成主运行时装配并具备可验证主干状态的 IM 后端实现；后续重点应放在补齐私聊会话初始化、收紧群权限与一致性规则、完善缓存回源，以及补强真正的端到端自动化验证。
