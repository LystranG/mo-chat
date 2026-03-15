# MoChat 后端实习面试准备总结

更新时间：2026-03-14

本文档的目标不是把代码再讲一遍，而是帮你准备面试。

重点回答 5 个问题：

1. 这个项目一句话怎么讲清楚。
2. 面试官最可能从哪里开始追问。
3. 项目里的哪些点会顺手引到常见八股。
4. 哪些话能说，哪些话容易说错。
5. 如果只有有限时间，应该优先背什么。

---

## 1. 先给项目下一个准确定位

### 1.1 一句话版本

MoChat 是一个以 IM 场景为核心的 Java 后端项目，当前默认采用四个 dedicated services 的拓扑：`access-gateway`、`api-service`、`message-service`、`persistence-service`，通过 `Redis + PostgreSQL + RocketMQ + gRPC + Netty/TLS` 实现连接接入、会话管理、消息接收、在线投递、离线补发和异步持久化。

### 1.2 更稳妥的定位说法

不要直接说“这是一个完整生产级微服务 IM 系统”，更稳妥的说法是：

这是一个已经完成默认运行拓扑拆分的 IM 后端项目，服务边界比较清楚，但仍然保留 compatibility shell，底层基础设施也还是共享的，所以它更像“从模块化单体走向 dedicated services 的工程化 IM 后端实现”。

### 1.3 这个定位为什么重要

因为面试官很可能第一时间就抓你这几个点：

- 这到底是单体还是微服务？
- 为什么拆成这四个服务？
- 为什么不是 database-per-service？
- 为什么还保留 `app`？
- 你是“画了四个服务”，还是“真的把 ownership 拆清了”？

你要先把这件事说稳，后面的回答才不会塌。

---

## 2. 可以直接背的项目介绍模板

### 2.1 60 秒版本

我这个项目叫 MoChat，是一个 IM 场景的 Java 后端项目。当前默认拓扑不是单体，而是拆成了四个服务：`access-gateway` 负责长连接接入和在线连接 ownership，`api-service` 负责登录、session authority、社交关系和历史查询，`message-service` 负责消息接收、幂等、ACK 和在线投递编排，`persistence-service` 负责 RocketMQ 消费后落 PostgreSQL，推进会话状态。  
技术上主要用了 Micronaut、gRPC、Netty、Redis、PostgreSQL、RocketMQ 和 Kubernetes 运行时配置。  
这个项目最值得讲的不是 CRUD，而是几个边界语义：session 谁说了算、`SEND_ACK` 表示什么、在线路由怎么防 stale、消息怎么做幂等和顺序、以及服务拆分以后 ownership 怎么落地。

### 2.2 2 分钟版本

MoChat 是一个 IM 后端项目，我把它理解成“连接层、会话层、写路径编排层、持久化层”明确拆开的系统。  
当前默认有四个服务：`access-gateway` 专门处理 TCP/TLS 长连接、bind、心跳、在线路由和定向投递；`api-service` 负责登录、session authority、好友/群关系、历史查询；`message-service` 负责同步收消息、做幂等、分配 `msgId/seq`、同步发 RocketMQ、给发送方 ACK，并且根据 Redis 在线路由做定向投递；`persistence-service` 异步消费 MQ，把消息和会话状态写入 PostgreSQL。  
这里有几个我觉得面试最值得讲的点。第一，session authority 在 `api-service`，gateway 只消费权威结果，不自己认定 session 是否有效。第二，在线路由里不是只有 `userId -> gateway`，而是有 `sessionVersion + routeEpoch + lease`，用来防旧连接、旧登录、旧 owner 继续收消息。第三，`SEND_ACK` 不等于对端收到，也不等于持久化完成，它只表示 message-service 已接受并成功写入 MQ。第四，持久化是异步的，所以系统允许“实时先送达、历史稍后可见”的一致性窗口。  
项目工程化上也做了比较多验证，仓库里有大量单测、集成测试、协议契约测试、Kubernetes 资源测试和 Docker 打包测试，可以支撑这些语义不是只写在文档里。

---

## 3. 技术栈和亮点，面试时怎么说更自然

### 3.1 技术栈

- 语言与运行时：Java，JDK 25
- 框架：Micronaut 4.9.0
- 网络：Netty，自定义 TCP 固定头 + Protobuf，TLS 1.3
- 服务间通信：gRPC
- 缓存与协调：Redis，Lettuce
- 数据库：PostgreSQL
- 消息队列：RocketMQ 5.3.2
- 容器与部署：Dockerfile，Kubernetes Kustomize，`kind`
- 测试：JUnit 5、Mockito、Testcontainers、契约测试、集成测试
- 交付：部分服务支持 GraalVM native image

### 3.2 亮点不是“用了很多技术”，而是“边界清楚”

建议你面试时强调这 5 个亮点：

- 会话权威与连接 ownership 分离。
- 在线投递与持久化提交分离。
- ACK 语义边界清楚。
- 通过 `sessionVersion + routeEpoch` 做 stale fencing。
- 运行拓扑、Kubernetes 配置、打包方式和测试都有工程化落地。

---

## 4. 这个项目最可能被问到的 14 个点

下面这 14 个点，基本就是面试官最容易顺着问下去的主线。

每个点我都按 4 个维度写：

- 面试官可能怎么问
- 你应该怎么答
- 这个点会引到哪些八股
- 代码/文档证据

### 4.1 为什么拆成四个服务，而不是继续一个进程全干

#### 面试官可能怎么问

- 你这个项目是单体还是微服务？
- 为什么拆成 `access-gateway`、`api-service`、`message-service`、`persistence-service`？
- 为什么没做 database-per-service？

#### 你应该怎么答

这次拆分不是为了“看起来像微服务”，而是为了把 ownership 拆清。  
`access-gateway` 只管连接接入和在线路由，`api-service` 只管 session authority 和 HTTP 读侧，`message-service` 只管同步写路径编排，`persistence-service` 只管 durable truth。  
这样做的好处是边界更清楚、故障域更清楚、扩缩容粒度更细。  
但当前还共享 PostgreSQL、Redis、RocketMQ，也还保留 compatibility shell，所以更准确地说，这是一个已经完成默认运行拓扑拆分、但仍保留过渡入口和共享基础设施的 dedicated services 架构。

#### 会引到哪些八股

- 单体和微服务的优缺点
- 逻辑隔离和物理隔离的区别
- 为什么很多团队先拆服务边界，再拆数据边界
- database-per-service 为什么难
- 分布式事务为什么麻烦

#### 证据

- [README.md](../README.md)
- [docs/mochat-technical-documentation.md](./mochat-technical-documentation.md)
- [docs/architecture/decompose-im-into-core-services-skeleton.md](./architecture/decompose-im-into-core-services-skeleton.md)

### 4.2 为什么 `access-gateway` 要做长连接入口 owner

#### 面试官可能怎么问

- 为什么单独搞一个 gateway 服务？
- 为什么不是让业务服务直接接 TCP？

#### 你应该怎么答

长连接接入和业务编排是两类完全不同的问题。  
gateway 更关注连接生命周期：bind、心跳、在线状态、旧连接替换、定向投递、优雅下线。  
如果把这些和登录、好友关系、消息接收、数据库事务混在一个服务里，复杂度会很高，而且扩缩容方向也不一样。  
所以项目把“连接层”和“业务层”分开了。

#### 会引到哪些八股

- 长连接网关为什么通常单独部署
- 接入层和业务层分离的意义
- 无状态服务和有连接状态服务的区别
- 网关为什么常常要做 drain

#### 证据

- [README.md](../README.md)
- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayConnectionRuntimeLifecycle.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayConnectionRuntimeLifecycle.java)
- [connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java)

### 4.3 session authority 为什么在 `api-service`

#### 面试官可能怎么问

- 为什么 session 不放在 gateway 本地内存？
- 为什么 gateway 不能自己决定 session 是否有效？

#### 你应该怎么答

因为 gateway 只拥有连接，不拥有登录态真相。  
登录后，`SessionService` 会签发新的 `sessionId`，同时维护 `sessionVersion` 和 active session pointer。  
后续 gateway bind、heartbeat 续租、消息定向投递，都会再消费 session authority 结果。  
这样重复登录、旧登录态过期、旧 session 被替换，都有统一真相来源。

#### 会引到哪些八股

- 认证态为什么常放 Redis
- 单端登录和多端登录怎么做
- token/session 的区别
- fail-open 和 fail-closed 的取舍

#### 证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java)
- [api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java](../api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java)
- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GrpcSessionResolver.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GrpcSessionResolver.java)

### 4.4 为什么在线路由里不仅有 `gatewayPod`，还有 `sessionVersion` 和 `routeEpoch`

#### 面试官可能怎么问

- 在线状态为什么不能只存一个 `userId -> gateway`？
- 为什么 `sessionVersion` 还不够，还要再加 `routeEpoch`？

#### 你应该怎么答

`sessionVersion` 和 `routeEpoch` 解决的是两个不同问题。  
`sessionVersion` 防的是旧登录态继续占用连接，`routeEpoch` 防的是同一个登录态下旧 owner、旧 route 继续收消息。  
所以 targeted delivery 时会同时校验 `sessionId`、`sessionVersion`、`expectedRouteEpoch`。  
这是典型的 fencing token 思路。

#### 会引到哪些八股

- fencing token 是什么
- 为什么 TTL 不能完全替代版本号
- ABA 问题是什么
- Lua 原子脚本为什么有用

#### 证据

- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java)
- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/grpc/AccessGatewayInternalGrpcService.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/grpc/AccessGatewayInternalGrpcService.java)
- [docs/mochat-technical-documentation.md](./mochat-technical-documentation.md)

### 4.5 为什么要“写新路由成功后再踢旧连接”

#### 面试官可能怎么问

- 重复登录时旧连接怎么踢？
- 为什么不是先关掉旧连接再绑定新连接？

#### 你应该怎么答

因为先踢旧连接再写新路由，中间会出现短暂空窗。  
更稳妥的做法是先把新 owner 写成功，再根据旧路由信息踢掉旧连接。  
如果旧 owner 在本地，就本地关闭；如果在其他 gateway，就发远程 kick。  
这样能把“没人在线”和“旧 owner 误接消息”的风险降到更低。

#### 会引到哪些八股

- 状态切换时为什么要避免空窗
- 分布式竞态怎么收敛
- 本地替换和跨节点替换的差别

#### 证据

- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GatewayRouteReplacementHandler.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GatewayRouteReplacementHandler.java)
- [access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/InMemoryUserChannelDirectory.java](../access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/InMemoryUserChannelDirectory.java)

### 4.6 为什么 bind 要异步做，不能直接堵在 Netty handler 里

#### 面试官可能怎么问

- `SessionBindingHandler` 为什么要单独搞异步解析？
- 为什么不能直接在 Netty 线程里查 session、写 Redis？

#### 你应该怎么答

因为 Netty event loop 最怕阻塞。  
session 解析、route 写入、旧连接替换这些动作都可能触发 IO 或较慢操作。  
如果放在 event loop 里，会把同一个 loop 上的其他连接一起拖慢。  
所以这里用了异步解析和待处理队列，超过 backlog 就快速失败，保护 event loop。

#### 会引到哪些八股

- Netty Reactor 模型
- event loop 为什么不能阻塞
- 背压和 backlog 的区别
- 高并发下为什么要快速失败

#### 证据

- [connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java)
- [connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java)

### 4.7 heartbeat 在这个项目里不只是保活

#### 面试官可能怎么问

- 心跳在这个项目里除了保活还有什么用？
- stale owner 怎么自我清理？

#### 你应该怎么答

heartbeat 有三层作用：  
第一，维持连接活性；第二，续租 Redis 在线路由；第三，发现自己已经不是当前 owner 时主动关闭。  
也就是说，heartbeat 不只是“让 TCP 别断”，还是 ownership 校验点。

#### 会引到哪些八股

- lease 和 timeout 的关系
- 为什么需要主动 self-kill stale owner
- 心跳频率和超时阈值怎么取

#### 证据

- [connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java)
- [connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java)

### 4.8 一条消息从客户端到落库，完整链路是什么

#### 面试官可能怎么问

- 你把发消息这条主链路讲一下。
- 私聊消息从客户端发出到进数据库，走了哪些组件？

#### 你应该怎么答

客户端消息先进入 gateway 的 TCP pipeline，之后被路由到 message-service。  
message-service 会做协议解析、业务校验、幂等、顺序控制、分配 `msgId/seq`、同步发 RocketMQ、给发送方 `SEND_ACK`，然后尝试根据 Redis 在线路由做实时投递。  
真正的落库是在 persistence-service 异步消费 MQ 之后完成，数据库提交成功后才算 durable truth 成立。

#### 会引到哪些八股

- 同步链路和异步链路怎么划边界
- 为什么不直接落库再返回
- 最终一致性窗口怎么解释给业务

#### 证据

- [connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java](../connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java)
- [persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java](../persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java)

### 4.9 顺序保证为什么不能只说“RocketMQ 有序消息”

#### 面试官可能怎么问

- 同一会话消息顺序怎么保证？
- 如果两个请求同时发到同一个会话，会不会乱序？

#### 你应该怎么答

这个项目的顺序保证不是只有 MQ 一层。  
前面先对同一个 `conversationId` 加锁，然后用 Redis `INCR` 分配单调递增的 `seq`，后面 RocketMQ 生产端再根据会话分片 key 选择队列。  
所以它保证的是“同一会话尽量单调有序”，不是全局总序。

#### 会引到哪些八股

- 局部有序和全局有序的区别
- 为什么跨实例仍然要考虑热点会话
- 锁和分区有序分别解决什么问题

#### 证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java)
- [infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java](../infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java)
- [common/src/main/java/com/github/lystran/mochat/common/lock/JucConversationLock.java](../common/src/main/java/com/github/lystran/mochat/common/lock/JucConversationLock.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java)

### 4.10 幂等是怎么做的，为什么 key 选 `senderUid + clientMsgId`

#### 面试官可能怎么问

- 客户端超时重试怎么办？
- 为什么还能返回同一个 `msgId/seq`？
- 为什么幂等 key 不是 `conversationId + clientMsgId`？

#### 你应该怎么答

message-service 在入口就做了幂等窗口。  
第一次成功后，会把 `msgId:seq` 放进 Redis，key 是 `senderUid + clientMsgId`。  
重复请求先查幂等记录，命中后直接复用旧结果并补发 `SEND_ACK`，不会重新分配 ID。  
之所以带 `senderUid`，是为了避免不同用户的 `clientMsgId` 冲突。

#### 会引到哪些八股

- 幂等 key 怎么设计
- TTL 怎么选
- Redis 挂了时幂等怎么办
- 为什么幂等不是万能的

#### 证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java)
- [infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java](../infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java)

### 4.11 `SEND_ACK` 到底表示什么，不表示什么

#### 面试官可能怎么问

- 你们的发送成功 ACK 语义是什么？
- 客户端收到 ACK 后，消息是不是一定入库了？

#### 你应该怎么答

`SEND_ACK` 只表示 message-service 已经接受这条消息，并且同步写 RocketMQ 成功。  
它不表示接收方已经收到，不表示 PostgreSQL 已经提交，也不表示历史查询立刻可见。  
这点必须说清，因为这是这个项目最核心的边界语义之一。

#### 会引到哪些八股

- 至少一次、至多一次、恰好一次
- producer send success 还会不会丢
- 为什么 ACK 不等于持久化完成
- 用户侧“发送成功”应该怎么定义

#### 证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java)
- [docs/mochat-technical-documentation.md](./mochat-technical-documentation.md)

### 4.12 在线投递为什么只刷新一次路由，再失败就走离线兜底

#### 面试官可能怎么问

- 为什么不一直重试在线投递？
- `ROUTE_STALE`、`USER_OFFLINE`、`WRITE_FAILED` 怎么处理？

#### 你应该怎么答

同步发送链路最怕尾延迟失控。  
在线投递失败时，第一次失败可能是路由刚过期，所以会再刷新一次 Redis 路由。  
如果还失败，就把可重放 payload 放进离线队列，不做无限重试。  
这样能避免把用户的发送请求拖得太长，也能避免重试风暴。

#### 会引到哪些八股

- 重试风暴是什么
- 同步链路和补偿链路如何分工
- 离线消息为什么要可重放

#### 证据

- [message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java](../message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java)
- [infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java](../infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReplayableDeliveryPayloadCodec.java)

### 4.13 离线重放为什么做成 best-effort

#### 面试官可能怎么问

- 用户重新登录后，离线消息怎么补？
- 如果离线重放失败，会不会影响登录？

#### 你应该怎么答

登录的主目标是“让用户先成功进入系统”，不是“保证离线消息立刻补完”。  
所以登录成功后会 best-effort 调 message-service 做 replay。  
中途失败时，会把当前和后续 payload 重新入队，保证登录本身不被离线补发拖死。

#### 会引到哪些八股

- 主流程和附属流程怎么切分
- 为什么不要让补偿拖垮主链路
- 重放顺序和重复投递怎么处理

#### 证据

- [logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java)
- [api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/GrpcLoginOfflineReplayGateway.java](../api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/GrpcLoginOfflineReplayGateway.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageServiceOfflineReplayService.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageServiceOfflineReplayService.java)

### 4.14 持久化幂等和历史可见性的最终边界是什么

#### 面试官可能怎么问

- MQ 重投时怎么防止重复落库？
- 为什么“实时已送达”，历史查询还可能看不到？

#### 你应该怎么答

持久化事务里会先插入 `messages`，如果唯一键冲突，就按 `msgId` 回读，完全一致就视为 durable duplicate 成功 no-op，不再重复推进会话状态。  
而 history read-side 明确由 `api-service` 提供，它只看 committed view。  
所以系统允许出现“实时先送达、历史稍后可见”的窗口，这不是 bug，而是明确的设计取舍。

#### 会引到哪些八股

- at-least-once 消费为什么必须做持久化幂等
- savepoint 有什么用
- CQRS 和最终一致性怎么解释
- 唯一键防重和业务幂等有什么区别

#### 证据

- [persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java](../persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java)
- [persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java](../persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java)
- [logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java](../logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java)
- [docs/mochat-technical-documentation.md](./mochat-technical-documentation.md)

---

## 5. 这个项目很容易引到的八股

下面这些八股，基本都会从项目里的具体实现自然引出来。

### 5.1 Redis

最容易引到：

- Redis 是缓存还是权威状态存储
- TTL、过期、续租
- Lua 原子脚本
- 热 key、雪崩、击穿、穿透
- Redis 高可用：主从、哨兵、集群

你要学会把项目和 Redis 八股连起来：

- session authority 存在 Redis，不只是缓存
- 在线路由依赖 Redis 的 lease
- 幂等窗口在 Redis
- 离线补发队列也用 Redis
- 会话内 seq 生成依赖 Redis `INCR`

### 5.2 MySQL / PostgreSQL

最容易引到：

- 索引设计
- 游标分页和深分页
- MVCC、隔离级别、锁
- 唯一键防重
- 事务提交后再更新缓存

你要学会说：

- 聊天历史天然适合按 `seq` 做 cursor 分页，不适合大 offset
- `messages` 表和 `conversations` 表的写入是一个事务边界
- durable duplicate 不是靠“吞异常”，而是靠唯一约束 + 回读确认

### 5.3 MQ

最容易引到：

- RocketMQ 为什么适合这里
- 顺序消息怎么做
- 消费失败重试
- 至少一次语义
- 消息积压怎么办

你要学会说：

- message-service 和 persistence-service 之间用 RocketMQ 划出了 handoff boundary
- `SEND_ACK` 的边界取决于 RocketMQ 同步发送是否成功
- 持久化侧必须接受 MQ 重投，所以要做 durable idempotency

### 5.4 Netty / TCP / TLS

最容易引到：

- Netty 线程模型
- pipeline 和 handler 顺序
- 粘包拆包
- 心跳与重连
- TLS 握手和证书

你要学会说：

- 这个项目不是纯 HTTP，而是自定义 TCP 固定头 + Protobuf body
- gateway 强制 TLS 1.3
- 心跳不仅保活，还承担 ownership 续租和 stale 检测
- event loop 不能阻塞，所以 bind 做了异步解析

### 5.5 微服务 / gRPC / Kubernetes

最容易引到：

- gRPC 为什么适合内部同步调用
- 服务发现怎么做
- StatefulSet 为什么适合 gateway
- readiness / liveness / preStop / graceful shutdown
- rollout 时长连接怎么优雅下线

你要学会说：

- `api-service` 和 `message-service` 之间是普通服务调用
- `message-service -> access-gateway` 是 owner-addressed targeted delivery
- 在 Kubernetes 下，gateway 身份来自 Pod 元数据和 headless Service DNS

---

## 6. 国内大厂后端实习面试里，高频考点和这个项目怎么对应

结合公开中文面经、招聘要求摘要和技术社区总结，Java 后端实习面试大致顺序通常是：

先拷打项目，再追 Redis / 数据库 / MQ / 场景题，最后才是网络、Netty、微服务、Kubernetes。

### 6.1 第一梯队：几乎必问

- 项目介绍与个人贡献边界
- Redis
- MySQL / PostgreSQL
- MQ
- 幂等
- 事务与一致性

在这个项目里分别对应：

- 项目介绍：四服务拓扑、长连接、ACK 语义、异步持久化
- Redis：session、route、幂等、离线队列、seq
- PostgreSQL：消息表、会话状态、历史查询
- MQ：同步接入和异步持久化的边界
- 幂等：入口幂等 + 持久化幂等
- 一致性：实时送达和历史可见分离

### 6.2 第二梯队：你简历写到了就会深挖

- IM 系统设计
- Netty
- TCP / TLS
- 微服务治理
- Kubernetes

在这个项目里分别对应：

- IM：在线状态、ACK、离线重放、顺序、回执
- Netty：event loop、pipeline、异步 bind、心跳
- TCP/TLS：固定头协议、Protobuf、TLS 1.3
- 微服务治理：ownership、服务发现、失败边界
- Kubernetes：StatefulSet、headless Service、drain、preStop

### 6.3 第三梯队：面试官想拔高时会问

- epoll / io_uring / NIO 区别
- exactly-once 为什么难
- CQRS 是什么
- database-per-service 为什么难
- cache consistency 为什么没有银弹
- 为什么很多系统先逻辑拆分，再物理拆分

---

## 7. 这个项目最容易说错的 10 句话

下面这些说法要尽量避免。

### 7.1 错误说法：这个项目已经是完整微服务了

更稳妥的说法：

当前默认运行拓扑已经拆成四个服务，但仍共享基础设施，也保留 compatibility shell，所以更准确地说是 dedicated services 默认运行形态，而不是“所有层面都彻底解耦”的微服务终态。

### 7.2 错误说法：发送成功 ACK 就是消息入库成功

更稳妥的说法：

`SEND_ACK` 只代表 message-service 已接收并成功写入 RocketMQ，不代表对端已收到，也不代表 PostgreSQL 已提交。

### 7.3 错误说法：这个系统保证 exactly-once

更稳妥的说法：

这个系统更接近“入口幂等 + MQ 至少一次 + 持久化幂等 + 对客户端尽量表现为稳定结果”，而不是严格意义的 exactly-once。

### 7.4 错误说法：Redis 就是缓存

更稳妥的说法：

在这个项目里，Redis 既有缓存/协调作用，也承载 session authority 相关状态、在线路由、幂等窗口、离线队列、seq 生成等关键职责。

### 7.5 错误说法：RocketMQ 保证全局顺序

更稳妥的说法：

这里做的是按会话分片的局部有序，不是全局总序。

### 7.6 错误说法：历史查不到就是 bug

更稳妥的说法：

这个项目明确允许“实时先送达、历史稍后可见”的一致性窗口，因为实时投递和 durable commit 被设计成两条边界不同的链路。

### 7.7 错误说法：重复登录就是简单把旧连接关掉

更稳妥的说法：

这里不是“粗暴断开”，而是先写新路由，再带着 `routeEpoch` 去踢旧连接，避免空窗和误杀。

### 7.8 错误说法：Netty 快就是因为用了 NIO

更稳妥的说法：

Netty 的优势不只是 NIO，还包括事件循环模型、pipeline、内存管理、连接管理和更成熟的网络编程抽象。

### 7.9 错误说法：Kubernetes 部署就是把 jar 丢进 Pod

更稳妥的说法：

这个项目里 Kubernetes 的重点不是“跑起来”，而是服务发现、Pod 身份、gateway 的 StatefulSet、drain、probe、TLS Secret 和 rollout 过程。

### 7.10 错误说法：项目用了很多技术，所以很复杂

更稳妥的说法：

真正复杂的不是技术栈多，而是边界语义多。面试时一定要把“为什么这么划边界”讲明白。

---

## 8. 可以直接口述的 8 组高频回答

### 8.1 如果被问：这个项目最难的点是什么

我觉得最难的不是功能开发，而是把几个边界语义讲清楚并落地：谁拥有 session authority，`SEND_ACK` 到底表示什么，在线路由怎么避免 stale owner，消息怎么做幂等和顺序，以及服务拆分后各自拥有什么 ownership。  
这些点如果说不清，项目就会变成“技术很多但边界模糊”。  
这个项目的价值就在于边界是相对清楚的。

### 8.2 如果被问：你在这个项目里最值得讲的设计取舍是什么

我会讲“同步接收编排”和“异步持久化”分离。  
message-service 负责快速接收、幂等、ACK 和在线投递编排，persistence-service 负责异步 durable commit。  
这样写路径响应更快，但代价是会有“实时先送达、历史稍后可见”的窗口，这个窗口是明确设计出来的，不是偶然现象。

### 8.3 如果被问：Redis 在这个项目里起什么作用

Redis 不只是缓存。  
它承担了 session、在线路由、幂等窗口、离线重放队列和会话内 seq 生成等职责。  
所以它更像协调状态和短期权威状态的承载层。

### 8.4 如果被问：为什么 ACK 不等于入库成功

因为项目把同步接收和异步持久化拆开了。  
客户端收到 ACK 时，说明 message-service 已接受并成功发到 RocketMQ。  
真正的数据库提交在 persistence-service 异步消费之后才发生，所以 ACK 和 durable commit 不是同一个边界。

### 8.5 如果被问：如何防止旧连接继续收消息

这里不是单纯靠 Redis TTL，而是用了 `sessionVersion + routeEpoch + lease`。  
`sessionVersion` 防旧登录，`routeEpoch` 防旧 owner，lease 防僵尸在线。  
定向投递时会带着这些信息一起校验，不匹配就认定为 stale。

### 8.6 如果被问：为什么用 RocketMQ

这个项目需要同步接收后快速返回，同时把持久化和部分异步处理解耦出去。  
RocketMQ 在这里充当了 message-service 和 persistence-service 之间的 handoff boundary。  
而且项目还需要按会话做局部有序，这也和 RocketMQ 的队列模型比较契合。

### 8.7 如果被问：为什么 gateway 用 StatefulSet

因为消息定向投递不是随便打到某个 gateway，而是要打到当前 owner。  
owner 身份依赖稳定的 `gatewayPod`，而 StatefulSet 和 headless Service 正好能提供稳定 Pod 身份和 Pod DNS。  
普通 Deployment 更适合无状态业务服务。

### 8.8 如果被问：为什么你说这是个适合面试的项目

因为它能自然覆盖很多后端核心题：Redis、数据库、MQ、Netty、TCP/TLS、微服务、Kubernetes、幂等、顺序、一致性、优雅下线。  
而且这些点不是孤立的八股，而是都能落到真实代码和真实边界语义上。  
这样回答起来会更像真的做过。

---

## 9. 工程化层面的加分点

这个项目除了主功能，也有一些很适合加分的工程化细节。

### 9.1 测试覆盖面比较广

仓库里当前有 90+ 个测试类，覆盖：

- 单元测试
- HTTP / gRPC 集成测试
- 协议契约测试
- 跨 gateway 路由测试
- 持久化事务测试
- Docker 打包测试
- Kubernetes 资源契约测试

这说明项目不是“只把 happy path 写出来”，而是验证了协议、边界和部署资产。

### 9.2 运行时拓扑是可配置的

既支持本地静态 target map 兜底，也支持 Kubernetes 下基于 Pod 身份和 headless Service 的服务发现。  
这很适合拿来讲“开发环境和云原生环境的差异”。

### 9.3 不是所有服务都强行 native

仓库里有 GraalVM 相关配置和 native image 打包，但也有对 MQ 等复杂依赖的务实折中。  
这个点很适合拿来讲“工程取舍”，而不是“为了炫技全都 native”。

---

## 10. 复习优先级：如果你只有有限时间

### 10.1 第一优先级：必须背熟

- 60 秒项目介绍
- 四个服务分别负责什么
- `SEND_ACK` 表示什么
- session authority 在哪
- Redis 在线路由为什么有 `sessionVersion + routeEpoch`
- 发送主链路怎么走
- 持久化为什么是异步

### 10.2 第二优先级：高概率深挖

- 幂等 key 为什么这样设计
- 顺序保证为什么不是一句“RocketMQ 有序”
- 为什么只刷新一次路由再离线兜底
- 历史为什么用 `seq` 分页
- 为什么 gateway 要 drain
- 为什么 gateway 用 StatefulSet

### 10.3 第三优先级：拔高和补充

- Netty event loop 模型
- TCP 粘包拆包
- TLS 握手和证书链
- Redis 高可用
- PostgreSQL MVCC
- RocketMQ 消费重试
- database-per-service 和最终一致性

---

## 11. 建议你额外补的外部面试热点

截至 2026-03-14，公开中文资料里，Java 后端实习常见顺序大致是：

项目介绍 -> Redis / 数据库 / MQ -> 场景题 -> 网络 / Netty -> 微服务 / Kubernetes

比较适合你继续看的资料：

- JavaGuide 后端面试通关计划  
  <https://javaguide.cn/interview-preparation/backend-interview-plan.html>
- 小林 Coding 后端面试与数据库/Redis/网络专题  
  <https://xiaolincoding.com/backend_interview/>
- Kubernetes probes 官方文档  
  <https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/>
- RocketMQ ordered messages 官方文档  
  <https://rocketmq.apache.org/docs/featureBehavior/03fifomessage/>
- PostgreSQL MVCC 官方文档  
  <https://www.postgresql.org/docs/current/mvcc-intro.html>
- Redis persistence / cluster spec 官方文档  
  <https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/>  
  <https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/>
- Netty reference-counted objects 说明  
  <https://github.com/netty/netty/wiki/Reference-counted-objects>

---

## 12. 最后给你的建议

这个项目最大的价值，不是“技术栈很多”，而是“你可以把八股讲成场景题”。

你准备面试时，建议按下面这个顺序来背：

1. 先把项目一句话说清楚。
2. 再把消息主链路讲清楚。
3. 再把 `SEND_ACK`、session authority、在线路由 fencing 这三个边界讲清楚。
4. 然后把 Redis、数据库、MQ 的常见八股都映射回这个项目。
5. 最后补 Netty、TCP/TLS、Kubernetes 这些拔高题。

如果你能做到下面这件事，面试表现通常就不会差：

不要背“标准答案”，而是每个八股都能接一句“在我这个项目里，对应的是哪一段代码、哪一段链路、哪一个取舍”。

这才会让面试官觉得你不是在背题，而是真的做过项目。
