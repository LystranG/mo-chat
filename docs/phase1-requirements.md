# MoChat 一期需求文档（V1）

## 1. 背景与目标

MoChat 一期目标是交付一个**单机高吞吐**、具备云原生演进能力的 IM 后端系统。当前形态为单体应用，但架构需提前抽象，支持未来低成本拆分为微服务。

核心目标：
- 支撑高并发消息收发（Netty + Reactor + `io_uring`）
- 保障可靠投递与持久化（RocketMQ + PostgreSQL）
- 满足基础安全（TLS1.3 + 私聊 E2EE）
- 保证可维护、可扩展、可演进（模块化边界 + 接口解耦）

## 2. 技术栈与约束

- 语言与框架：Java 25 + Micronaut
- 网络模型：Netty 主从 Reactor，优先 Linux `io_uring` AIO
- 序列化协议：Protobuf（文本字段按 UTF-8）
- 存储：PostgreSQL
- 缓存：Caffeine + Redis 二级缓存
- 消息队列：RocketMQ（同步刷盘）
- 安全：TLS 1.3（自签证书）+ 私聊 E2EE
- 构建与交付：Gradle + GraalVM Native Image
- 包路径：`com.github.lystran.mochat`
- 版本策略：优先使用最新稳定依赖

## 3. 一期功能范围（In Scope）

### 3.1 接入与会话
- 提供 HTTP 登录/注册接口：输入用户名即可登录；若用户不存在则需要同时提交一个 base64 编码的 X25519 身份公钥完成注册（公钥不可修改，除非创建新账户）。
- 无密码模式（一期简化）。
- 登录后签发 `sessionId`，写入 Redis，并由 Caffeine 做二级缓存。
- TCP 聊天请求需携带并校验 `sessionId`。

### 3.2 网络与协议
- 使用 Netty 实现主从 Reactor。
- Linux 环境优先使用 `io_uring`（当前内核版本 6.19.5）；若初始化失败则回退到 Netty 系统默认实现。
- 使用自定义协议：固定长度帧头 + 变长 Protobuf body。
- 帧头字段：魔数(4 bytes) + 版本(1 byte) + 消息类型(1 byte) + 序列化方式(1 byte, 目前仅 protobuf) + body 长度(4 bytes, 大端) + body(N bytes)。
- 使用 `LengthFieldBasedFrameDecoder` 控制最大帧长，默认最大 64KB（可配置）。
- msgType 与异常响应码集合由后端设计并以枚举形式固化（见本文件“协议枚举与异常码”章节）。
- 需要消息校验（结构、会话、业务合法性、编码合法性）。

### 3.3 消息元数据与安全
- 客户端生成 `clientMsgId`（用于幂等去重与发送 ACK 关联）。
- 服务端使用雪花算法生成全局唯一 `msgId`（用于落库主键与下游一致性标识）；历史分页使用会话内单调递增的 `seq`。
- 消息时间戳由服务端生成并回写。
- 服务端在 RocketMQ 确认同步刷盘成功后返回发送 ACK，ACK 中包含 `clientMsgId` 与 `msgId`。
- 客户端与服务端之间采用 TLS1.3 加密 TCP 通道。

### 3.4 私聊与群聊
- 私聊文本消息使用 E2EE：X25519 + AES-GCM。
- 私聊消息字段在原有基础上增加 `nonce`（客户端随机生成，固定 12 bytes）与 `ciphertext`（二进制密文）；TCP+Protobuf 传输使用 `bytes` 字段。
- 服务端只存私聊密文（包含 `nonce` 与 `ciphertext`）。
- 用户身份公钥在注册时上传并持久化（可缓存），公钥无 TTL 且不可修改。
- 群聊消息按当前要求以明文存储。

### 3.5 缓存策略
- 群聊消息采用 Caffeine + Redis 二级缓存。
- 每个群最多缓存 500 条，超过后从尾部淘汰。
- 缓存更新触发点统一为“DB 事务提交成功后”。

### 3.6 MQ 与持久化
- 发送消息后服务端写入 RocketMQ（同步刷盘）。
- RocketMQ 返回成功后，服务端给发送方返回 ACK（发送成功）。
- 持久化模块从 MQ 批量消费并写入 PostgreSQL。
- 数据库事务提交成功后，再向消息队列确认消费。

### 3.7 可靠性与一致性
- 幂等：按 `(senderUid, clientMsgId)` 去重，Redis 缓存 5 分钟。
- 在线用户：若对方 channel 在线，服务端优先直推（不要求接收方 ACK）。
- 离线队列：对方不在线或投递写失败则写入 Redis 离线队列（最多 50 条），上线后补推。

### 3.8 公钥缓存与补偿流程
- 用户身份公钥在注册时上传，服务端需要进行校验：base64 解码校验、长度校验（32 bytes）、格式校验（按 X25519 原始公钥字节串处理）。
- 公钥持久化到 PostgreSQL，并可缓存到 Redis（无 TTL）。

### 3.9 心跳与连接管理
- 心跳频率默认 10s 一次（可配置）。
- 5s 内无响应（可配置）则踢下线并释放 channel 资源。

### 3.10 历史记录查询
- 采用基于 `seq` 的历史分页契约：游标模式使用 `cursorSeq`，范围模式使用 `startSeq/endSeq`。
- `cursorSeq` 与 `startSeq/endSeq` 互斥；范围模式要求 `startSeq/endSeq` 成对出现。
- `limit` 默认 50，且最大不超过 50。

### 3.11 客户端限流
- 客户端发送消息采用令牌桶限流。
- 以“一个 channel（一个用户会话）一个令牌桶”的粒度执行限流。
- 在 Netty pipeline 中通过自定义 handler 实现，定时任务使用 Netty `HashedWheelTimer`。
- 桶容量和令牌补充速率可配置。

### 3.12 架构与工程化
- 按职责拆分为消息模块与持久化模块。
- 模块间通过对外接口与事件驱动解耦。
- 对未来云原生演进相关能力做抽象（如 user->channel 管理器）。
- 使用 Git 管理迭代；Gradle 管理依赖。
- Base64 编解码等通用工具优先使用 Apache Commons。
- 实施阶段需使用 Context7 MCP 查询关键依赖文档，作为技术选型与实现依据。

### 3.13 HTTP 接口（一期必须覆盖）

- 登录/注册
- 创建群聊
- 私聊历史记录拉取
- 群聊历史记录拉取
- 获取好友列表
- 获取群聊列表
- 发送好友请求
- 获取发送过的好友请求
- 获取收到的好友请求
- 处理好友请求（同意/拒绝）
- 删好友
- 拉黑好友
- 解除拉黑（基于 `blocked_by` 可逆操作）
- 离开群聊
- 群主获取群的加群申请
- 群主处理加群申请（同意/拒绝）
- 群主踢人
- 群主解散群聊

注：当前清单未显式包含“创建群聊”接口，但由于群聊由用户创建，一期建议补齐创建接口。

## 4. 非功能性要求（NFR）

- 高可维护性：清晰分层、接口隔离、低耦合。
- 高可扩展性：模块可替换、部署形态可演进。
- 可观测与可调优：关键链路应具备日志/指标埋点能力（一期至少预留）。
- 安全性：传输加密、会话校验、消息合法性校验。

## 5. 数据模型（一期最小集合）

约束：
- 所有表主键 `id` 使用雪花算法生成。
- 表结构需合理设计索引，优先覆盖：好友列表、群列表、群成员列表、历史消息基于 `conversation_id + seq` 的游标/范围查询。
- 需要产出一份 DDL SQL 文件（建议路径：`docs/ddl/phase1.sql`）。

建议最小表：
- `users`（用户：包含 username 与身份公钥）
- `user_friendships`（好友关系：`(id, uid_1, uid_2, status, blocked_by)`，其中 `uid_1 < uid_2`，status=ok/blocked，`blocked_by` 标识谁发起拉黑）
- `friend_requests`（好友请求：包含 `sign` 字段，base64 字符串；服务端仅转发/存储，不校验签名）
- `groups`（群聊：包含基本信息与 owner_id）
- `group_memberships`（群成员关系：群 id 与用户 id 的关系）
- `group_join_requests`（加群申请：包含 `sign` 字段，base64 字符串；由群主处理）
- `messages`（消息：包含 `msgId`、`clientMsgId`、时间戳、会话维度等；消息体以 Protobuf 二进制编码并以 base64 形式存储以便 HTTP 拉取）

## 6. 关键流程（摘要）

### 6.1 登录流程
HTTP 登录/注册 -> 用户存在性检查 -> 若新用户则校验并写入身份公钥 -> 生成 `sessionId` -> Redis 持久化 -> Caffeine 缓存 -> 返回登录结果。

### 6.2 消息发送流程
TCP 收包 -> 会话与消息校验 -> 幂等检查（(senderUid, clientMsgId)）-> 服务端生成 `msgId` 与时间戳 -> 发送 RocketMQ（同步刷盘）-> 成功 ACK 发送方（含 clientMsgId + msgId）。

### 6.3 持久化流程
持久化模块批量拉取 MQ -> 事务写 PostgreSQL -> 事务提交成功后确认 MQ 消费 ->（群聊）事务提交成功后更新二级缓存。

### 6.4 在线投递流程
若接收方在线 -> 直推（不要求接收方 ACK）。若接收方不在线或写失败 -> 写入离线队列，上线后补推。

## 7. 已识别问题与需确认项

以下是当前需求中存在的潜在歧义/风险点（建议在实现前确认）：

1. **雪花算法参数未固定**：需明确 epoch、workerId/datacenterId 的配置方式（一期先通过配置文件设置 workerId，默认 1）。
2. **好友请求/加群申请的签名算法未定义**：当前仅约束“服务端不校验、只转发”，仍需由客户端侧统一签名算法与字段规范。

## 8. 协议枚举与异常码（一期基线）

### 8.1 msgType（1 byte）数值映射

- 1: CLIENT_HEARTBEAT（客户端心跳）
- 2: SERVER_HEARTBEAT（服务端心跳）
- 3: PRIVATE_MESSAGE（私聊消息，body 为 protobuf）
- 4: GROUP_MESSAGE（群聊消息，body 为 protobuf）
- 5: SEND_ACK（服务端对发送方的投递 ACK，MQ 同步刷盘成功后返回）
- 6: ERROR_RESPONSE（异常响应）

### 8.2 serializer（1 byte）数值映射

- 1: PROTOBUF

### 8.3 ErrorCode（protobuf body 中的异常码）

建议使用 int32 枚举值：
- 1000: SESSION_INVALID
- 1001: SESSION_EXPIRED
- 1100: RATE_LIMITED
- 1200: INVALID_FRAME
- 1201: INVALID_BODY
- 1202: UNSUPPORTED_VERSION
- 1203: UNSUPPORTED_SERIALIZER
- 1300: NOT_FRIEND
- 1301: FRIEND_BLOCKED
- 1400: NOT_IN_GROUP
- 1500: MQ_PUBLISH_FAILED
- 1501: INTERNAL_ERROR

## 9. 一期验收标准（建议）

- 登录与会话：用户名直登可用，`sessionId` 可用于 TCP 鉴权。
- 消息链路：发送 -> MQ -> 持久化 -> ACK 主链路可跑通。
- 可靠性：幂等、离线补推可验证。
- 安全：TLS1.3 生效，私聊密文落库。
- 性能与稳定性：心跳、限流、缓存淘汰逻辑生效。
- 工程交付：可通过 GraalVM native image 完成打包。

## 10. 暂不包含（Out of Scope）

- 客户端实现
- 多机分布式一致性与跨机路由
- 复杂权限系统与审计系统
- 完整生产级 SRE 体系（告警、自动扩缩容等）
