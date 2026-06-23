# MoChat 前端客户端行为实现清单

本文面向前端和客户端开发，描述客户端需要承担的行为职责、依赖的服务端接口和当前已知限制。本文以当前代码事实为准，不展开后端服务拆分细节。

## 1. 范围和原则

客户端需要负责：

- 本地生成、保存和使用身份私钥。
- 保存登录态和会话态。
- 建立并维护聊天 TCP/TLS 长连接。
- 按外部 TCP 帧协议和 protobuf 编解码消息。
- 维护本地会话、消息、发送状态、历史游标和 seq 连续性。
- 对实时消息、离线补发消息和历史补拉消息去重合并。
- 对私聊消息执行端到端加密和解密。
- 根据好友、拉黑、群成员状态控制发送能力。

服务端负责：

- 保存账号身份公钥。
- 签发和校验 session。
- 接受消息并分配 `msgId`、会话内 `seq` 和 `serverTimeMs`。
- 执行发送策略校验、在线投递、离线补发和历史查询。
- 提供 conversation state、history 和社交/群管理接口。

客户端不能把 `SEND_ACK` 理解为消息已经持久化、已被对端收到或已经可通过历史接口查询。`SEND_ACK` 只表示服务端消息接受链路已经成功写入 MQ。

## 2. 账号与密钥

首次注册或首次登录时，客户端必须生成一组身份密钥对：

- 私钥只保存在客户端本地，不上传服务端。
- 公钥通过 `/auth/login` 的 `publicKey` 字段上传。
- `publicKey` 必须是 base64 字符串，解码后长度为 32 字节。
- 已存在账号再次登录时，如果继续携带 `publicKey`，必须与服务端已保存的公钥一致。
- 服务端不会接受同一账号替换为另一把身份公钥。

本地私钥丢失时，客户端不应静默生成新密钥并尝试覆盖服务端公钥。该情况应作为账号不可解密或需要恢复流程的风险处理。

私聊文本使用端到端加密：

- 客户端在发送前使用对端身份公钥完成加密。
- `EncryptedText.nonce` 必须是每条消息唯一的 12 字节 nonce。
- `EncryptedText.ciphertext` 保存密文。
- 服务端只转发和持久化密文结构，不理解明文。

当前缺口：代码中只看到登录阶段的公钥上报和校验，未看到稳定的好友公钥、用户公钥或群成员公钥批量查询接口。前端实现私聊 E2EE 前，需要后端补齐公钥分发接口，或在好友/用户资料接口中返回公钥。

## 3. 登录与 session

客户端通过 `POST /auth/login` 登录或首次 bootstrap 用户。请求体包含：

- `username`
- 首次登录必填的 `publicKey`

登录成功后，服务端返回：

- `userId`
- `username`
- `sessionId`

客户端需要保存 `sessionId`，并在后续 HTTP 和 TCP 请求中使用：

- HTTP 接口通常通过 query 参数或请求体传递 `sessionId`。
- TCP 聊天消息体中也需要携带 `sessionId`。

登录成功后，服务端会尝试触发离线消息补发。但客户端不能只依赖登录补发来保证消息完整性。每次登录、重连或进入会话后，客户端仍应通过本地最大连续 seq 与 `/conversations/{conversationId}/state` 对齐，必要时使用 `/history` 补拉缺口。

当服务端返回 session invalid 或 HTTP `401` 时，客户端应清理当前会话态，停止继续发送聊天帧，并引导重新登录。

## 4. TCP/TLS 连接与帧协议

聊天长连接使用 access-gateway 的 TCP/TLS 入口。客户端必须使用 TLS 连接，不能使用明文 TCP。

外部聊天协议为固定帧头加 protobuf body。帧头字段如下：

| 字段 | 长度 | 说明 |
| --- | ---: | --- |
| `magic` | 4 字节 | 固定为 `0x4D4F4348` |
| `version` | 1 字节 | 当前为 `1` |
| `msgType` | 1 字节 | 消息类型编号 |
| `serializer` | 1 字节 | 当前只有 `PROTOBUF = 1` |
| `bodyLength` | 4 字节 | protobuf body 长度 |

固定头总长度为 11 字节。默认最大帧长为 64 KiB，服务端可通过 gateway 配置覆盖。

当前外部消息类型包括：

- `CLIENT_HEARTBEAT = 1`
- `SERVER_HEARTBEAT = 2`
- `PRIVATE_MESSAGE = 3`
- `GROUP_MESSAGE = 4`
- `SEND_ACK = 5`
- `ERROR_RESPONSE = 6`
- `CLIENT_RECEIVE_ACK = 7`
- `DELIVERED_ACK = 8`

客户端必须使用 `protocol/src/main/proto/mochat/v1/chat.proto` 中定义的 protobuf message 序列化和反序列化 body。

## 5. Session bind 与连接 ownership

当前协议没有独立的 bind 帧。session bind 发生在客户端发送第一条带 `sessionId` 的业务帧时：

- `PRIVATE_MESSAGE`
- `GROUP_MESSAGE`
- `CLIENT_RECEIVE_ACK`

access-gateway 会解析其中的 `sessionId`，调用 session authority 校验，校验通过后把该连接登记为当前用户的在线 route。

客户端需要理解以下行为：

- 新连接成功 bind 后，可能覆盖同一账号的旧连接。
- 旧连接可能被服务端主动 kick，也可能在下一次心跳续租时发现自己 stale 后关闭。
- gateway 进入 draining 时，会拒绝新 ownership，并返回错误后关闭连接。
- 客户端收到连接关闭后应进入重连流程，而不是继续在旧连接上发送。

当前限制：access-gateway 当前主线尚未明显把 TCP 上行稳定转成 `message-service` 的 `MessageCommandApi.Send*` gRPC 命令；gateway 入站仍会发布到 `connection.inbound`，而 dedicated message-service 的 legacy inbound consumer 默认关闭。前端联调时必须确认实际发送入口，文档不能假设“TCP 上行已稳定直达 message-service gRPC”。

## 6. 心跳与重连

服务端会按配置周期发送 `SERVER_HEARTBEAT`，当前默认间隔是 10 秒。客户端必须主动发送 `CLIENT_HEARTBEAT`。

关键规则：

- 服务端只有收到 `CLIENT_HEARTBEAT` 才会重置连接 idle timeout 并触发 route 续租。
- 不能假设任意业务上行或下行消息都会续命。
- 当前服务端默认 idle timeout 为 60 秒。
- 客户端发送心跳的周期必须小于服务端 idle timeout，建议按 10-20 秒发送一次。
- 客户端如果连续多个心跳周期没有收到 `SERVER_HEARTBEAT`，应主动断开并重连。可以使用 3 个心跳周期作为客户端策略，但这不是服务端固定协议语义。

需要触发重连的情况：

- TCP/TLS 连接断开。
- 本地判断心跳超时。
- 写入 TCP 失败。
- 收到 `ERROR_RESPONSE` 且表示 session invalid、gateway draining 或内部错误需要换连接。
- 设备网络切换。
- app 从后台恢复后发现连接不可用。

重连建议：

- 使用指数退避，避免网络抖动时无限高频重连。
- 重连成功后，用当前 `sessionId` 发送业务帧或回执帧触发 bind。
- bind 后执行消息恢复：离线补发由登录链路尝试触发，客户端仍需根据 conversation state 与 history 做完整性校准。

## 7. 消息发送

客户端发送消息前必须生成 `clientMsgId`：

- `clientMsgId` 用于发送方维度的幂等。
- 服务端当前 Redis 幂等窗口是短期窗口，不是永久 exactly-once 保证。
- 幂等记录在 MQ publish 成功后写入。
- 客户端应在本地保存 `clientMsgId -> 本地消息` 映射，用于重试和 ACK 归并。

私聊消息使用 `PrivateMessageReq`：

- `sessionId`
- `clientMsgId`
- `conversationId`
- `toUid`
- `contents`

私聊文本内容应使用 `EncryptedText`。客户端负责加密、nonce 生成和解密失败处理。

群聊消息使用 `GroupMessageReq`：

- `sessionId`
- `clientMsgId`
- `conversationId`
- `groupId`
- `contents`

当前协议支持群聊 `PlainText`，不要把群聊写成已经具备 E2EE。服务端当前发送策略只校验群存在和发送者是否为活跃成员，不涉及群密钥、成员公钥批量查询或群密钥轮换。

媒体消息使用 `MediaMetadata`：

- 协议只承载 URL、缩略图 URL、文件大小、mime、文件名、时长、宽高、预览文本和波形等元数据。
- 文件上传、下载鉴权、URL 过期、私聊媒体是否先加密再上传，目前不是该协议结构能够独立保证的能力。
- 前端实现媒体消息前，需要确认媒体上传服务和私聊媒体加密策略。

## 8. 发送 ACK 和本地发送状态

服务端接受消息后返回 `SEND_ACK`，包含：

- `clientMsgId`
- `msgId`
- `seq`
- `serverTimeMs`

字段含义：

- `clientMsgId`：客户端生成的发送幂等 ID。
- `msgId`：服务端生成的全局消息 ID。
- `seq`：会话内顺序号。
- `serverTimeMs`：message-service 接受时间，不等于 DB commit 时间。

客户端推荐发送状态：

- `pending`：本地已创建，尚未收到 `SEND_ACK`。
- `accepted`：收到 `SEND_ACK`，服务端已接受并写入 MQ。
- `failed`：明确被拒绝或超时后重试策略结束。
- `delivered/read`：仅私聊在收到对端 `DELIVERED_ACK` 后推进，当前 dedicated 服务端回执能力仍需联调确认。

`SEND_ACK` 后可以把本地消息从 `pending` 标记为 `accepted`，但不能标记为“已持久化可查”或“对方已收到”。

## 9. 消息接收、本地缓存与 seq 连续性

客户端收到 `ChatMessageDelivery` 后，需要按以下规则处理：

- 按 `conversationId + seq` 去重。
- 保存 `msgId`、`seq`、`serverTimeMs`、`fromUid`、`contents` 和解析后的展示数据。
- 私聊密文在本地解密后展示，解密失败应显示可恢复错误状态，不应丢弃原始密文。
- 维护每个 conversation 的最大连续 seq，而不是只保存最大 seen seq。

实时投递、离线补发和历史补拉可能送达同一条消息。客户端必须把三种来源合并到同一份本地消息表。

当收到的 seq 大于本地最大连续 seq + 1 时：

- 先缓存新消息。
- 标记会话存在缺口。
- 通过 `/history` 使用 `startSeq/endSeq` 补拉缺口。
- 缺口补齐后再推进最大连续 seq。

客户端打开会话时应优先展示本地已有的最新消息，再异步执行服务端 state 对齐和历史补拉。

## 10. 历史补拉

客户端通过 `/conversations/{conversationId}/state` 查询服务端已持久化的会话状态：

- `conversationId`
- `latestSeq`
- `latestMessageTime`

如果本地最大连续 seq 小于服务端 `latestSeq`，应使用 `/history` 补拉缺失消息。

`/history` 支持两种模式：

1. 游标分页：`cursorSeq`
   - 表示查询某条 seq 之前的历史消息。
   - 适合向上翻历史。

2. 区间补拉：`startSeq` + `endSeq`
   - 表示查询闭区间内消息。
   - 适合修复 seq 缺口或对齐最新状态。

限制和行为：

- `cursorSeq` 与 `startSeq/endSeq` 互斥。
- `startSeq` 和 `endSeq` 必须同时传。
- `startSeq <= endSeq`。
- 默认 limit 为 50。
- 最大 limit 为 50。
- 返回项按 seq 倒序。
- `payloadBase64` 需要先 base64 解码，再按 protobuf 解析为原始消息 body。

客户端不能把 Redis offline queue 当作 durable history。offline queue 只是实时投递失败后的临时补发队列。最终一致的消息完整性应以 `/history` 和 conversation state 为兜底。

## 11. 回执与已读展示

外部协议包含：

- `ClientReceiveAck`
- `DeliveredAck`

私聊场景下，客户端在本地最大连续接收 seq 推进后，可以发送：

- `sessionId`
- `conversationId`
- `latestReceivedSeq`

客户端收到 `DeliveredAck` 后，应更新自己发出的私聊消息状态：

- `conversationId`
- `toUid`
- `latestReceivedSeq`
- `serverTimeMs`

对于私聊，可以把小于等于 `latestReceivedSeq` 且由当前用户发出的消息标记为对端已收到或已读到该 seq。具体 UI 文案使用“已读”还是“已送达”，需要和产品定义保持一致，因为当前协议名表达的是 received ack。

群聊当前不实现成员级 delivered/read state。不要展示群成员级已读列表。

当前限制：

- legacy/compatibility 路径中存在完整的 `CLIENT_RECEIVE_ACK -> DELIVERED_ACK` 逻辑。
- dedicated `message-service` 的 `AcknowledgeReceipt` 当前仍是 skeleton，只返回 accepted，不完整推进 receipt state。
- 前端应实现协议支持，但回执功能联调前需要确认后端 dedicated 路径是否已补齐。

## 12. 好友、拉黑和群状态

好友状态会影响私聊能力：

- 好友申请 `pending` 或 `rejected` 时，不能允许发送私聊。
- 好友申请 `accepted` 后，应刷新好友列表和会话入口。
- 删除好友后，客户端应禁用对应私聊发送能力。
- 拉黑后，客户端应禁用或提示发送受限。
- 取消拉黑后，应重新刷新好友状态和发送能力。

群状态会影响群聊能力：

- 入群申请 `accepted` 后，应刷新群列表和群会话入口。
- 退群后，客户端不能再向该群发送消息。
- 被踢后，客户端应置灰或移除群会话发送入口。
- 群解散后，应停止发送并更新会话状态。
- 群主不能退群、群主不能被踢等服务端拒绝场景，客户端应展示明确错误。

前端不能只依赖本地 UI 状态判断是否可发送。服务端仍会执行发送策略校验。客户端需要处理消息被 rejected 的情况，并刷新社交/群状态。

## 13. 本地存储建议

客户端建议至少维护以下本地数据：

- 当前用户资料：`userId`、`username`。
- 身份密钥：私钥、安全存储引用、公钥指纹。
- session：`sessionId`、登录时间、失效状态。
- 会话表：`conversationId`、类型、对端/群信息、本地最大连续 seq、最早已加载 seq、服务端 latestSeq 快照。
- 消息表：`conversationId`、`seq`、`msgId`、`clientMsgId`、方向、发送者、服务端时间、内容、原始 payload、展示状态。
- pending 发送表：`clientMsgId`、重试次数、最近错误、待发送 protobuf body。
- 历史补拉状态：缺口区间、游标、是否正在加载、最近失败原因。
- 私聊回执状态：peer latest received seq。
- 社交和群状态缓存：好友状态、拉黑状态、群 membership 状态。

私钥和 session 应使用平台安全存储。消息落盘是否加密由产品安全要求决定；私聊密文原文建议保留，以便解密失败后可重试。

## 14. 错误和恢复

客户端需要处理以下错误：

- HTTP `401` 或 TCP `SESSION_INVALID`：清理 session 并重新登录。
- TCP `gateway draining`：断开并重连到其他 gateway。
- 消息 rejected：展示失败，并根据错误刷新好友/群状态。
- `SEND_ACK` 超时：保留 `clientMsgId`，按重试策略重发；收到重复 ACK 时归并到同一条本地消息。
- 收到重复消息：按 `conversationId + seq` 去重。
- 收到乱序消息：先缓存，再补拉缺口。
- 历史补拉 404：视为无权限或会话不存在，刷新会话入口。
- 历史补拉 401：重新登录。
- 解密失败：保留密文和错误状态，不推进为正常可读消息。
- 网络切换或 app 恢复：检查 TCP 状态，必要时重连并做 seq 校准。

恢复原则：

- 本地状态必须可重放。
- pending 消息依赖 `clientMsgId` 重试和归并。
- accepted 消息不能重复展示。
- seq 缺口通过 `/history` 修复。
- 离线补发不是最终兜底，history 才是客户端校准依据。

## 15. 已知后端限制和待确认项

当前前端实现前需要确认或推动补齐：

- 公钥查询接口缺失：需要按用户、好友或群成员批量获取 identity public key。
- dedicated receipt ack 未完整实现：`message-service` 的 `AcknowledgeReceipt` 当前仍是 skeleton。
- TCP 上行到 message-service 的 dedicated 主线入口需要联调确认。
- 群聊当前不是 E2EE。
- 媒体上传、下载鉴权和私聊媒体加密策略未在当前聊天 protobuf 中完整定义。
- `SEND_ACK` 与 history 可见性之间存在异步窗口，客户端必须容忍 accepted 后短时间查不到历史。

## 16. 代码依据

- 外部协议：`protocol/src/main/proto/mochat/v1/chat.proto`
- 消息类型和帧常量：`protocol/src/main/java/com/github/lystran/mochat/protocol/MsgType.java`、`FrameConstants.java`、`SerializerType.java`
- 登录和公钥校验：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`、`logic-module/src/main/java/com/github/lystran/mochat/logic/service/UserService.java`
- TCP pipeline、心跳和 bind：`connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`、`HeartbeatHandler.java`、`SessionBindingHandler.java`
- 消息接受和投递：`logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- message-service 命令入口：`message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/MessageCommandGrpcService.java`
- 历史和会话状态：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`、`ConversationController.java`
- 好友和群接口：`logic-module/src/main/java/com/github/lystran/mochat/logic/http/FriendsController.java`、`GroupsController.java`
- 当前服务边界记忆：`docs/codebase/README.md` 及对应边界目录 README
