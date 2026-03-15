# MoChat 简历对齐版面试稿

更新时间：2026-03-14

这份文档专门服务于你的简历项目条目。

目标不是重复上一份总复习，而是解决 4 个更实际的问题：

1. 你简历里这段项目描述怎么说更稳。
2. 哪几处表述容易被面试官抓住反问。
3. 每一条 bullet 面试时该怎么展开。
4. 如果面试官顺着追问，你应该怎么接。

---

## 1. 先说结论：你这段简历能打，但有 5 处建议收紧

结合你简历上的内容，这几个点方向是对的：

- 高吞吐、长连接、低延迟，这些都符合 IM 项目的核心卖点。
- `Netty + RocketMQ + PostgreSQL` 这组技术栈也很像一个真的后端项目。
- 你把亮点放在 I/O、可靠投递、并发安全、分层解耦上，这是对的。

但如果直接按截图原文去讲，面试时最容易被抓住的有 5 处：

### 1.1 `E2EE` 这个词要讲得更保守

你现在的写法是“支持群聊和 E2EE 私聊对话”。

更稳妥的理解是：

- 这个项目对私聊密文载荷是友好的，协议里有 `nonce` 和 `ciphertext`
- 用户登录时也有 `publicKey` 校验
- 服务端主要负责密文透传、身份校验和消息投递

但如果面试官继续追问：

- 你们的密钥协商怎么做？
- 双棘轮做了吗？
- 前向保密怎么保证？

你就不能把它说成“我完整实现了端到端加密协议栈”。

更稳的说法是：

支持私聊密文传输和身份公钥校验，服务端不解密消息内容，整体设计是 E2EE-friendly 的。

### 1.2 “Redis Lua 脚本实现消息 seq 严格原子递增”这句不够准确

当前代码里：

- `seq` 分配主要依赖 `conversation lock + Redis INCR`
- Lua 脚本主要用于在线路由写入、续租、清理，以及 `routeEpoch` 的原子推进

所以如果面试官问你：

- 你 Lua 脚本具体怎么做 seq 分配？

你不要硬扛。

更准确的说法是：

使用 `conversation lock + Redis INCR` 保证会话内 `seq` 单调递增，使用 Redis Lua 脚本维护在线路由和 `routeEpoch` 的原子更新。

### 1.3 “两阶段 ACK”要明确不是事务里的 two-phase commit

你简历里写“两阶段 ACK 机制”，这个方向没问题，但要定义清楚。

这里更准确地说，是业务层面的两段确认：

- 第一段：`SEND_ACK`
- 第二段：`DELIVERED_ACK`

不是：

- XA 两阶段提交
- RocketMQ 事务消息的两阶段事务协议

如果不提前说明，面试官可能会故意问偏。

### 1.4 “用户级限流”建议改成“连接级限流”

当前代码里的 `RateLimitHandler` 更接近：

- 基于 Netty pipeline 的连接级令牌桶限流
- 超过阈值直接关闭连接

它不是严格意义上的“按用户维度做全局限流”。

更稳的说法是：

实现了连接级令牌桶限流，并结合心跳检测清理无效连接。

### 1.5 `io_uring(Proactor)` 这句不要和面试官死磕理论分类

你写“基于 Linux 原生 io_uring（Proactor 模型）与 Netty 传统多路复用（主从 Reactor 模型）的双引擎切换策略”，这句话很能吸引面试官，但也很容易被挑刺。

建议你的心态是：

- 重点讲“支持 io_uring 路径，并且能 fallback 到 epoll/NIO”
- 重点讲“这是工程上的双路径启动策略”
- 不要现场和面试官争“这到底算不算严格教科书意义上的 Proactor”

你真正要表达的重点应该是：

这个网关对底层 I/O 传输路径做了工程化切换和兜底，而不是只会默认 NIO。

---

## 2. 推荐的简历写法

下面给你两版。

一版是“保守稳妥版”，更适合实习面试。  
一版是“亮点强化版”，更适合你想突出 IM / Netty / 云原生特点的时候。

### 2.1 保守稳妥版

`Mo-Chat（个人项目）`

- 独立设计并开发的高吞吐、可靠、安全的 IM 后端系统，支持群聊与私聊，并实现登录态管理、在线路由、消息 ACK、离线补发与异步持久化。
- 技术栈：`Java 25`、`Micronaut`、`Netty`、`gRPC`、`Redis`、`RocketMQ`、`PostgreSQL`
- 高性能 I/O：在网关层实现 `io_uring` 与 `epoll/NIO` 的双路径启动策略，结合 Netty 事件循环承载 TCP/TLS 长连接。
- 低延迟通信：使用自定义固定头协议 + Protobuf 编解码，维持长连接、减少握手开销，并通过同步接收 / 异步持久化分离降低发送路径时延。
- 可靠投递：基于 RocketMQ 构建消息接收与异步持久化链路，设计 `SEND_ACK + DELIVERED_ACK` 两段确认机制，支持在线定向投递与离线补发。
- 并发与一致性：使用 `conversation lock + Redis INCR` 分配会话内严格递增 `seq`，并使用 Redis 维护请求幂等窗口；通过 Lua 脚本原子维护在线路由与 `routeEpoch`，避免 stale owner 继续收消息。
- 稳定性治理：在 Netty pipeline 中实现连接级令牌桶限流、心跳检测与僵尸连接清理，支持 gateway drain 与滚动发布。
- 架构解耦：按接入层、业务编排层、持久化层拆分职责，其中逻辑与持久化服务默认无状态，便于横向扩容。

### 2.2 亮点强化版

`Mo-Chat（个人项目）`

- 独立设计并开发的高吞吐、可靠、安全 IM 后端系统，支持群聊、私聊密文载荷透传、登录态管理、在线路由、消息 ACK、离线重放与异步持久化。
- 基于 `Netty + 自定义 TCP 协议 + Protobuf` 构建长连接网关，支持 `io_uring` 与 `epoll/NIO` 双路径启动，降低连接层时延与资源开销。
- 基于 `Redis + routeEpoch + sessionVersion` 实现在线路由 fencing，解决重复登录、旧连接替换和 stale owner 误投递问题。
- 基于 `RocketMQ` 构建“同步接收编排 + 异步 durable commit”链路，设计 `SEND_ACK + DELIVERED_ACK` 两段确认机制，实现在线定向投递与离线补发。
- 基于 `conversation lock + Redis INCR + 持久化唯一约束` 实现消息顺序控制、入口幂等与落库防重，并通过 PostgreSQL 支撑历史查询与会话状态推进。

---

## 3. 如果你不改简历原文，面试时要主动修正的 5 个点

这一节很重要。

如果你暂时不改简历，现场也一定要这样解释，不然面试官很容易顺着不准确的表述深挖。

### 3.1 关于 `E2EE`

不要说：

我完整实现了端到端加密协议。

更稳地说：

项目支持私聊密文载荷透传，协议里包含 `nonce` 和 `ciphertext`，登录时也会校验 `publicKey`，服务端不解密私聊内容。严格来说，我更愿意把它描述成“对 E2EE 友好的私聊消息链路”。

### 3.2 关于 `Lua + seq`

不要说：

我用 Lua 实现了消息 seq 原子递增。

更稳地说：

当前 `seq` 分配主要靠 `conversation lock + Redis INCR`，Lua 主要用在在线路由写入和 `routeEpoch` 更新的原子性上。

### 3.3 关于“两阶段 ACK”

不要说：

我们做了类似事务消息的两阶段提交。

更稳地说：

这里的“两阶段 ACK”指的是业务语义上的两段确认：发送方 `SEND_ACK` 和接收方送达后的 `DELIVERED_ACK`，不是分布式事务协议。

### 3.4 关于“用户级限流”

不要说：

我做了严格的用户级全局限流。

更稳地说：

当前落地的是连接级令牌桶限流，加上心跳检测和无效连接清理。如果未来要做严格的用户级全局限流，还要结合用户维度的共享状态。

### 3.5 关于 `io_uring(Proactor)`

不要说：

我实现了完整的 Proactor 网络框架。

更稳地说：

我在工程上支持了基于 `io_uring` 的传输路径，并保留了 `epoll/NIO` 回退策略，重点是提升连接层 I/O 路径的灵活性和性能兜底能力。

---

## 4. 按你简历的每个 bullet，分别怎么展开

下面这部分最适合直接练口语。

每个点我都写成 4 段：

- 简历这条到底在表达什么
- 你 20 到 40 秒怎么展开
- 面试官可能继续追问什么
- 你不能乱说什么

### 4.1 描述：高吞吐、可靠、安全的 IM 后端系统

#### 这条到底在表达什么

你想表达的是：

- 这不是普通 CRUD
- 这是一个有连接、消息、状态、一致性和安全边界的系统

#### 20 到 40 秒展开话术

我这个项目不是传统的 HTTP CRUD 服务，而是一个 IM 场景后端。  
它需要同时处理长连接接入、登录态管理、在线路由、消息接收、可靠投递、离线补发和异步持久化。  
所以我在设计时重点关注吞吐、时延、消息边界语义和连接层稳定性，而不是单纯把接口写出来。

#### 面试官可能继续追问

- 你说的“高吞吐”具体体现在哪？
- 你说“可靠”，可靠到什么程度？
- 安全主要体现在哪些点？

#### 你不能乱说什么

- 不要张口就说“百万级并发”除非你真有压测数据
- 不要把“可靠”说成“绝不丢消息”
- 不要把“安全”说成“完整实现了端到端加密协议”

### 4.2 高性能 I/O：`io_uring + epoll/NIO` 双路径启动

#### 这条到底在表达什么

你想告诉面试官：

- 你不是只会默认 Netty
- 你确实看过网关层底层传输路径

#### 20 到 40 秒展开话术

网关层的 `NettyChatServer` 启动时会优先尝试 `io_uring` 路径，如果环境不支持，再回退到 `epoll` 或 `NIO`。  
我想解决的是底层 I/O 路径的可用性和性能兜底问题，而不是把服务写死在某一种传输实现上。  
在面试里我更愿意把它描述为“支持 `io_uring` 的双路径启动策略”，而不是死抠教科书里的模型分类。

#### 面试官可能继续追问

- `io_uring` 和 `epoll` 区别是什么？
- 为什么要保留 fallback？
- Netty 为什么快？

#### 你不能乱说什么

- 不要说你自己实现了 `io_uring`
- 不要说项目完全依赖 `io_uring`
- 不要强行和面试官辩论 Proactor/Reactor 定义

#### 代码证据

- [connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java)

### 4.3 低延迟：长连接 + 自定义协议 + Protobuf

#### 这条到底在表达什么

你想表达的是：

- 你理解聊天系统为什么要长连接
- 你理解协议设计和带宽开销

#### 20 到 40 秒展开话术

这个项目不是每次发消息都走 HTTP 短连接，而是维持 TCP/TLS 长连接。  
这样能减少重复握手开销，也更适合消息和回执这种实时场景。  
协议层用了固定头 + Protobuf body，一方面方便做粘包拆包，另一方面比纯文本协议更省带宽，也更适合高频消息场景。

#### 面试官可能继续追问

- 为什么不用 WebSocket？
- 固定头里有哪些字段？
- TCP 粘包拆包怎么处理？
- TLS 为什么还要放在网关层？

#### 你不能乱说什么

- 不要说 Protobuf 一定比所有场景都快
- 不要把“低延迟”说成“没有任何一致性等待”

#### 代码证据

- [protocol/src/main/java/com/github/lystran/mochat/protocol/FrameConstants.java](../protocol/src/main/java/com/github/lystran/mochat/protocol/FrameConstants.java)
- [connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java)

### 4.4 可靠消息投递：RocketMQ + 两段 ACK

#### 这条到底在表达什么

你想表达的是：

- 发送路径不是“收到就算成功”
- 你明确划分了发送确认和送达确认

#### 20 到 40 秒展开话术

消息进入 message-service 后，不是直接告诉客户端“成功了”，而是先做校验、幂等、分配 `msgId/seq`，然后同步发 RocketMQ。  
只有 MQ publish 成功后才给发送方 `SEND_ACK`。  
如果接收方客户端后续上报回执，还会推进 `DELIVERED_ACK`。  
所以这里是业务层面的两段确认，不是事务里的 two-phase commit。

#### 面试官可能继续追问

- `SEND_ACK` 为什么不等于入库成功？
- `DELIVERED_ACK` 在什么情况下产生？
- 如果 MQ 写成功但持久化消费者挂了怎么办？

#### 你不能乱说什么

- 不要说 ACK 就代表“绝不丢消息”
- 不要说这是事务消息的二阶段提交
- 不要说“客户端可知是否送达”适用于所有消息类型和所有场景

#### 代码证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/EventBusSenderAckPublisher.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/EventBusSenderAckPublisher.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptService.java)

### 4.5 并发安全：顺序、幂等、防重复

#### 这条到底在表达什么

你想表达的是：

- 你处理过并发下的顺序和重复发送问题

#### 20 到 40 秒展开话术

同一会话里的消息顺序，当前主要靠 `conversation lock + Redis INCR` 来保证会话内 `seq` 单调递增。  
客户端重试导致的重复发送，入口侧会用 `senderUid + clientMsgId` 做幂等窗口，命中后直接复用历史结果，不会重新分配 `msgId/seq`。  
另外在线路由写入和 `routeEpoch` 推进用了 Lua 脚本，避免 stale owner 继续收消息。

#### 面试官可能继续追问

- 为什么幂等 key 选这个？
- Redis 掉了怎么办？
- 为什么顺序保证不能只靠 RocketMQ？
- `routeEpoch` 解决什么问题？

#### 你不能乱说什么

- 不要把 Lua 和 seq 分配混为一谈
- 不要说系统实现了严格意义 exactly-once

#### 代码证据

- [infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java](../infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java)
- [infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java](../infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java)
- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java)

### 4.6 限流与连接治理：Netty 令牌桶 + 心跳

#### 这条到底在表达什么

你想表达的是：

- 你不只关注 happy path，还处理了连接层稳定性

#### 20 到 40 秒展开话术

在网关的 Netty pipeline 里，我做了连接级令牌桶限流。  
如果某个连接短时间内消息过多，会被快速关闭，防止单连接拖垮事件循环。  
同时也有 heartbeat 机制，既能保活，也能清理无效连接和 stale owner。

#### 面试官可能继续追问

- 这是连接级还是用户级限流？
- 为什么超限直接关连接？
- 心跳频率和超时怎么定？

#### 你不能乱说什么

- 不要说已经做了严格的用户级全局限流
- 不要说心跳只是保活，它在这里还有 ownership 校验作用

#### 代码证据

- [connection-module/src/main/java/com/github/lystran/mochat/connection/RateLimitHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/RateLimitHandler.java)
- [connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java)

### 4.7 分层解耦：接入层、业务层、持久化层

#### 这条到底在表达什么

你想表达的是：

- 你不是只堆技术，而是做了职责分层

#### 20 到 40 秒展开话术

这个项目的拆分核心不是“把模块拆出来”，而是把 ownership 拆开。  
接入层负责连接，业务层负责 session authority、消息接收和在线投递编排，持久化层负责 durable truth。  
这样能让逻辑服务和持久化服务默认保持无状态，扩容时也更清晰；而 gateway 因为持有连接 ownership，所以是另外一类运行形态。

#### 面试官可能继续追问

- 为什么 gateway 不能完全无状态？
- 为什么 persistence-service 不直接对外提供 history？
- 为什么没做 database-per-service？

#### 你不能乱说什么

- 不要简单说“所有服务都无状态”
- gateway 明显带有连接 ownership，不要说成普通 Deployment 服务

#### 代码/文档证据

- [README.md](../README.md)
- [docs/mochat-technical-documentation.md](./mochat-technical-documentation.md)
- [docs/runbook.md](./runbook.md)

---

## 5. 简历版 90 秒项目介绍

这一段最适合你按简历去讲。

我这个项目叫 MoChat，是一个 IM 场景的 Java 后端系统。  
它的默认运行形态不是单体，而是拆成了四个服务：`access-gateway` 负责 TCP/TLS 长连接接入、心跳和在线路由；`api-service` 负责登录、session authority、好友关系和历史查询；`message-service` 负责消息接收、幂等、分配 `msgId/seq`、发送方 ACK 和在线投递编排；`persistence-service` 负责异步消费 RocketMQ 并把消息和会话状态写入 PostgreSQL。  
我在这个项目里重点做了几类事情。第一是连接层，我在网关层支持了 `io_uring` 与 `epoll/NIO` 的双路径启动，并使用自定义固定头协议 + Protobuf 承载长连接消息。第二是消息链路，我把同步接收和异步持久化拆开，基于 RocketMQ 做 handoff boundary，并设计了 `SEND_ACK + DELIVERED_ACK` 两段确认。第三是并发与一致性，我用 `conversation lock + Redis INCR` 保证会话内 `seq` 单调递增，用 Redis 维护幂等窗口，并用 Lua 脚本原子维护在线路由和 `routeEpoch`，避免 stale owner 继续收消息。  
这个项目最值得讲的不是某个接口，而是几个边界语义：session 谁说了算、ACK 到底表示什么、在线路由怎么 fencing、以及实时送达和历史可见为什么会分开。

---

## 6. 面试官如果盯着你的简历逐行抠，你可以怎么答

### 6.1 如果他问：你说高吞吐，有数据吗

你可以这样答：

我不会直接说夸张的吞吐数字，因为这次简历里我更想强调的是架构设计方向。  
高吞吐主要体现在几件事：长连接减少重复握手、自定义协议减少带宽浪费、消息接收和持久化分离、RocketMQ 承接异步写入、逻辑服务默认无状态便于扩容。  
如果需要更强的数据支撑，我后续会补压测指标。

### 6.2 如果他问：你说可靠，为什么 ACK 不等于入库

你可以这样答：

因为这个项目故意把“发送方已知已接收”和“数据库 durable commit”拆成了两个边界。  
`SEND_ACK` 解决的是发送路径响应问题，durable commit 解决的是最终历史真相问题。  
这种设计牺牲了一部分强实时一致性，但换来更好的发送路径时延和更清晰的异步边界。

### 6.3 如果他问：你说 E2EE，密钥协商在哪里

你可以这样答：

我会更准确地把它描述成“支持私聊密文载荷透传和身份公钥校验”。  
当前服务端侧重点是承载 `nonce/ciphertext`、不解密私聊内容，并在登录阶段校验 `publicKey` 一致性。  
如果面试里要讨论完整 E2EE 协议，比如双棘轮和前向保密，那是我后续想继续完善的方向。

### 6.4 如果他问：为什么用户级限流和代码里不完全一致

你可以这样答：

这个点我会修正表述。  
当前代码里严格落地的是连接级令牌桶限流，而不是严格意义上的用户级全局限流。  
如果要做用户级限流，还需要把用户维度的共享状态纳入设计。

---

## 7. 这份简历条目最容易引到的八股

### 7.1 一定会被引到

- Redis
- PostgreSQL / MySQL
- MQ
- Netty
- TCP / TLS

### 7.2 你写了这条后，大概率会被引到

- Reactor / event loop
- 幂等
- 顺序保证
- 最终一致性
- 微服务边界
- Kubernetes StatefulSet / headless Service / drain

### 7.3 如果面试官想拔高

- io_uring 和 epoll 区别
- exactly-once 为什么难
- fencing token
- CQRS
- database-per-service

---

## 8. 最后给你的建议

这份项目条目真正的核心竞争力不是“技术词很多”，而是：

- 你能把每个技术词对应到具体链路
- 你能说明白边界语义
- 你知道哪些地方是项目当前真实落地，哪些地方只是更稳妥的工程描述

你面试时最应该记住的一句话是：

不要把自己说成“实现了所有理论上的最强形态”，而要把自己说成“做了真实可落地的工程取舍，并且知道边界在哪里”。

这会比一味堆名词更像大厂想要的后端实习生。
