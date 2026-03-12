# MoChat 技术总览

本文档只记录当前代码库已经落地的稳定技术事实，用来说明默认运行拓扑、服务边界、核心交互语义、数据 ownership 和兼容壳定位。

启动命令、排障步骤、端口探测、TLS 证书生成和回滚操作不在这里展开，统一见 `docs/runbook.md`。

## 1. 默认运行形态

MoChat 当前默认采用四个 dedicated services 加共享基础设施的形态运行：

- `access-gateway`
- `api-service`
- `message-service`
- `persistence-service`

它们共享同一套 PostgreSQL、Redis 和 RocketMQ，但运行时 ownership 已经按服务拆开，而不是继续把业务装配在单个 Micronaut 进程里。

`app` 仍然存在，但它的角色已经降为 compatibility shell。它不再是默认拓扑的 source of truth，只在需要恢复 legacy 兼容路径时才应被显式启用。

## 2. 服务边界

### 2.1 `access-gateway`

`access-gateway` 负责长连接入口和在线连接 ownership：

- TCP/TLS 聊天入口
- Session bind 入口与会话权威校验
- 心跳、续租、超时关闭
- Redis 在线 route 写入与续租
- duplicate login 后的旧连接踢除
- targeted delivery 的本地 channel 写回
- drain / rollout 期间的新连接拒绝与存量连接宽限关闭

它只拥有连接生命周期和在线路由，不承担消息持久化真相或 history read-side。

### 2.2 `api-service`

`api-service` 负责账户、会话权威和读侧 HTTP：

- 登录与首次用户 bootstrap
- authoritative session record 签发与校验
- 好友、拉黑、群成员资格等消息前置业务校验
- social graph 和 group management HTTP 生命周期
- history query 与 conversation state read-side
- 登录后离线重放触发

它是 session authority owner，也是 history read-side owner。

### 2.3 `message-service`

`message-service` 负责同步消息接受路径和在线投递编排：

- 私聊 / 群聊命令入口
- Redis 幂等窗口
- `msgId` 与会话内 `seq` 分配
- RocketMQ 同步 publish
- sender `SEND_ACK`
- Redis route 解析
- 针对 owning gateway 的 targeted delivery
- route refresh once 后的 offline fallback
- replayable offline envelope 编排

它不拥有 durable message truth，也不承载 history query。

### 2.4 `persistence-service`

`persistence-service` 负责异步 durable truth：

- RocketMQ consume
- 事务内写入 `messages` 与 `conversations`
- receipt/state 推进
- post-commit group cache update
- duplicate consume 下的 durable idempotency

它是消息事实和 conversation advancement 的默认 owner。

### 2.5 共享层

当前共享层主要包括：

- `protocol`: 外部 TCP 协议和内部 gRPC protobuf 契约
- `common`: 跨服务抽象，例如 session、event bus、offline queue、id 生成和连接目录契约
- `service-runtime`: 各 dedicated service 的配置模型
- `message-module`: 需要跨服务共享的消息域契约

共享层只保留协议和契约，不应重新演化成隐式单体装配层。

## 3. 核心交互语义

### 3.1 bind 后才建立 gateway ownership

用户只有在 `access-gateway` 成功解析 session 并完成 bind 之后，才会被视为在线 owner 已建立。

成功 bind 会把在线 route 写入 Redis，并带上以下 fencing 信息：

- `gatewayPod`
- `connectionId`
- `sessionId`
- `sessionVersion`
- `routeEpoch`
- lease metadata

当前 phase 1 只允许单用户单活连接。更新 bind 会覆盖旧 route，并让旧连接在显式替换或下一次心跳续租时被判定为 stale。

### 3.2 session authority、`sessionVersion` 与 `routeEpoch`

会话权威由 `api-service` 持有，`access-gateway` 和 targeted delivery 只能消费 authority 结果，不能自定 session 有效性。

当前 stale fence 由两层组成：

- `sessionVersion`: 防止旧 session 或旧登录状态继续占有连接
- `routeEpoch`: 防止旧 route 或旧 gateway owner 继续接收投递

这两层 fence 同时用于：

- bind 阶段的 ownership 建立
- heartbeat renew
- duplicate login 替换旧连接
- `message-service -> access-gateway` 的 targeted delivery

### 3.3 在线投递、offline fallback 与 sender ACK

`message-service` 会在接受消息时完成：

- 幂等判断
- `msgId` / `seq` 分配
- RocketMQ publish
- sender `SEND_ACK`

`SEND_ACK` 只表示消息已经被 `message-service` 接受并成功写入 MQ，不表示：

- recipient 已确认接收
- 数据库事务已提交
- history 查询已经可见

针对在线 recipient，`message-service` 会先按 Redis route 做 targeted delivery。若结果为 `ROUTE_STALE`、`USER_OFFLINE` 或 `WRITE_FAILED`，它最多刷新一次 route；仍失败时才写入 offline envelope。

### 3.4 history visibility 与 durable commit 边界

history query 继续归属 `api-service`。当前语义明确区分三件事：

- sender accepted
- realtime delivery attempted/completed
- durable persistence committed

前两者都不等于 history 已可见。`/history` 与 `/conversations/{id}/state` 以后续持久化提交结果为准，因此系统允许出现“实时先送达、历史稍后可见”的窗口。

## 4. 数据 ownership 与真相来源

### 4.1 PostgreSQL

PostgreSQL 中的 durable truth 由 `persistence-service` 写入和推进，核心对象包括：

- `messages`
- `conversations`
- receipt/state 相关进度

当文档、DDL 参考稿和实际代码不一致时，应以以下两类事实为准：

- Flyway migrations
- JDBC 仓储与运行时代码

`docs/ddl/phase1.sql` 只能作为参考，不是最终 truth source。

### 4.2 Redis

Redis 当前承担两类不同职责：

- `api-service` 的 authoritative session state
- `access-gateway` / `message-service` 使用的在线 route、幂等窗口和短期 offline replay 协调数据

Redis 在这里是权威 session / coordination store，但不是最终消息事实库。

### 4.3 RocketMQ

RocketMQ 是同步接受和异步持久化之间的 handoff boundary：

- `message-service` publish 成功后才返回 `SEND_ACK`
- `persistence-service` commit 成功后才确认消费

这条边界定义了系统为何允许 realtime delivery 与 durable visibility 暂时分离。

## 5. 协议与运行时事实

当前稳定的协议与运行时事实包括：

- 外部聊天链路仍保持固定头 + protobuf body 的 TCP 协议
- 聊天 TCP 默认要求 TLS 1.3，禁用 TLS 会 fail-fast
- `access-gateway`、`api-service`、`message-service` 之间的同步调用使用内部 gRPC
- `message-service -> access-gateway` 的路由不是随机负载均衡，而是 owner-addressed targeted delivery
- phase 1 本地默认端口基线为：
  - `api-service` HTTP `8080`，gRPC `19091`
  - `message-service` gRPC `19092`
  - `access-gateway-a` TCP `9000`，gRPC `19093`
  - `access-gateway-b` TCP `9001`，gRPC `19094`

这些是拓扑和契约事实；具体启动方式与环境变量见 `docs/runbook.md`。

## 6. Compatibility Shell 与回滚姿态

`app` 当前保留的唯一核心意义是 compatibility shell：

- 它可以在 dedicated services 拓扑之外提供 legacy 入口
- 但默认不再 materialize persistence ownership
- 相关兼容路径必须通过显式开关启用，而不是作为默认行为恢复

当前回滚策略是 runtime-entrypoint 级别回滚，而不是数据层回滚。也就是说，保留共享 PostgreSQL / Redis / RocketMQ，不在 phase 1 内引入 schema migration rollback 或跨服务数据迁移。

## 7. 当前明确非目标与剩余缺口

以下边界在当前阶段是明确的非目标或延后项：

- 不做 database-per-service
- 不做 distributed transaction
- 不做 service mesh 或 KEDA
- 不支持 multi-device concurrent presence
- 不做 live connection migration
- 不实现 group member-level delivered/read state

当前仍保留的主要技术限制包括：

- gateway route target 和 peer target 仍依赖显式配置，而不是动态服务发现
- offline queue 仍以 replayable envelope 为主，未做进一步压缩或长期归档
- shared infrastructure 仍然存在，因此服务隔离是 logical ownership，而非物理隔离
- compatibility shell 仍在仓库中保留，说明迁移已经完成默认路径切换，但还没有彻底移除 legacy 回退面

## 8. 总体判断

MoChat 当前已经完成从“模块化单体默认运行”到“dedicated services 默认运行”的切换。稳定的主干事实是：

- 四个服务的职责边界已经落地
- internal gRPC、Redis route 和 RocketMQ handoff 已成为默认运行语义
- sender accepted、online delivery 和 durable visibility 的边界已经被显式定义并得到 focused 验证
- `app` 只作为 compatibility shell 存在，不再代表默认生产拓扑

因此，当前仓库更准确的定位是：

> 一个以四服务 dedicated topology 为默认运行形态、并以 OpenSpec 规格驱动边界定义的 IM 后端实现。
