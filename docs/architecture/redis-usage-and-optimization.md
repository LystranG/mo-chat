# MoChat 项目 Redis 使用梳理与优化建议

## 1. 先说结论

这个项目里的 Redis 不只是“缓存”。

它现在主要做 7 类事：

1. 保存登录会话状态
2. 保存“某个用户当前连在哪台网关上”的在线路由
3. 给会话分配递增的消息顺序号
4. 记录短时间内的消息幂等窗口，防止重复发送被重复处理
5. 暂存短期离线消息，等用户上线后补发
6. 做跨进程的轻量事件转发
7. 缓存群聊最近消息

但它**不是**最终消息事实库。最终消息是否真的落地，还是以 PostgreSQL 和持久化流程为准；Redis 在这里更多承担“快、轻、临时协调”的角色。

## 2. 总览表

| 用途 | 主要模块 | Redis 结构 | 典型键 | 主要命令 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 登录会话 | `api-service` / `logic-module` | `String` | `mochat:session:{sessionId}` | `SET` `GET` `DEL` | 保存单条 session 的状态、用户、版本、过期时间 |
| 当前活跃 session 指针 | `api-service` / `logic-module` | `String` | `mochat:session-active-user:{userId}` | `SET` `GET` `DEL` | 保存“这个用户现在到底认哪条 session” |
| 在线路由 | `access-gateway` / `message-service` | `String` | `online:user:{uid}` | `GET` `SET EX` `DEL` `EVAL` | 保存某个用户当前由哪个网关、哪条连接负责 |
| 在线路由版本号 | `access-gateway` | `String` 计数器 | `online:user:{uid}:route-epoch` | `INCR` | 每次重绑连接都递增，防止旧连接误写 |
| 被替换的旧路由 | `access-gateway` | `String` | `online:user:{uid}:replaced-route:{epoch}` | `SET EX` `GET` | 记录上一条旧路由，方便踢旧连接 |
| 会话内消息顺序号 | `message-service` | `String` 计数器 | `mochat:conversation:seq:{conversationId}` | `GET` `SETNX` `INCR` | 为同一会话分配单调递增的 `seq` |
| 发送幂等窗口 | `message-service` | `String` | `mochat:idempotency:{senderUid}:{clientMsgId}` | `GET` `SET NX PX` | 避免客户端重试时重复分配 `msgId/seq` |
| 离线补发队列 | `message-service` / 兼容路径 | `List` | `mochat:offline:{userId}` | `RPUSH` `LTRIM` `LPOP` | 暂存短期离线消息，按旧到新补发 |
| 事件总线 | `access-gateway` / `api-service` / 兼容路径 | `Pub/Sub` | 主题如 `connection.outbound` | `PUBLISH` `SUBSCRIBE` `UNSUBSCRIBE` | 做轻量跨进程事件广播 |
| 群最近消息缓存 | `persistence-service` | `Sorted Set` | `mochat:group:messages:{groupId}` | `ZADD` `ZCARD` `ZREMRANGEBYRANK` `ZRANGE` | 保存群聊最近一小段消息，减少查库 |

## 3. 各部分是怎么用的

### 3.1 登录会话

对应代码：

- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`

#### 它存了什么

这里有两类 key：

- `mochat:session:{sessionId}`
  - 保存一条 session 的完整信息
- `mochat:session-active-user:{userId}`
  - 保存这个用户当前真正生效的是哪条 session

`SessionService` 里可以看到这两个前缀：

- `mochat:session:`  
- `mochat:session-active-user:`  

见 `SessionService` 的常量定义和写入逻辑。

#### 为什么用 String

原因很直接：这两类数据都不大，而且读取时基本上都是“整条拿出来判断”，不是只改其中一个字段。

所以它用字符串把内容压成一条值：

- session 记录：`v1|状态|userId|sessionVersion|过期时间`
- 活跃指针：`sessionId|sessionVersion`

这样做的好处是：

- 实现简单
- 一次 `GET` 就能拿到完整信息
- 不需要为每个字段单独设计读写逻辑

#### 它现在怎么工作

`issueSession()` 会：

1. 生成新 sessionId
2. 找到旧的活跃 session
3. 把旧 session 标成 `REPLACED`
4. 写入新的 `mochat:session:{sessionId}`
5. 更新 `mochat:session-active-user:{userId}`

`resolveAuthority()` 会：

1. 先读 `mochat:session:{sessionId}`
2. 再读 `mochat:session-active-user:{userId}`
3. 判断它是不是过期、是不是被新登录顶掉、是不是仍然是当前有效会话

#### 现在的特点

- 过期时间目前是写在 value 里的，不是 Redis 原生 TTL
- 也就是说，这类 key 不会靠 Redis 自动消失，而是“有人来查时才顺手把状态改成过期”
- 代码里预留了一个本地 Caffeine 缓存，但当前读取路径并没有真正先查这个缓存，所以它现在并没有明显减少 Redis 读取次数

#### 通俗理解

可以把它理解成两张便签：

- 第一张：这条 session 的详细资料
- 第二张：这个用户现在应该认哪条 session

每次有人拿着 sessionId 来验身份，系统会同时看这两张便签。

### 3.2 在线路由

对应代码：

- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java`
- `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`

#### 它存了什么

这一部分回答的问题是：

“某个用户现在在线的话，到底连在了哪台网关、哪条连接上？”

主要 key：

- `online:user:{uid}`
  - 当前在线路由
- `online:user:{uid}:route-epoch`
  - 路由版本号计数器
- `online:user:{uid}:replaced-route:{epoch}`
  - 某次重绑时被替换掉的旧路由

#### 为什么用 String

路由本身字段也不多：

- `gatewayPod`
- `connectionId`
- `sessionId`
- `sessionVersion`
- `routeEpoch`
- `leaseDurationSeconds`
- `leaseExpiresAtEpochMilli`

项目把它们拼成一条字符串放进去。对读取方来说，好处是：

- `message-service` 查路由时，一次 `GET` 就够了
- 不需要额外做多字段拼装

其中 `gatewayPod`、`connectionId`、`sessionId` 还做了 Base64 URL 编码，避免分号和等号把格式搞乱。

#### 为什么用了 Lua

在线路由不是普通缓存，它有一个很关键的要求：

“新连接上来时，要么整套替换成功，要么不要写一半。”

这里项目用 Lua 脚本把几件事绑成一个原子动作：

1. 读取旧路由
2. 递增 `routeEpoch`
3. 写入新路由，并带上 TTL
4. 顺手把旧路由另存一份，并带上 TTL

这样做的目的，是防止旧连接和新连接在切换时互相打架。

#### TTL 是怎么用的

在线路由用了 Redis 原生过期时间。

默认租期跟网关心跳超时一致，当前默认值是 `60s`，配置在：

- `access-gateway-app/src/main/resources/application.yml`

含义很简单：

- 连接还活着，心跳会继续续租
- 长时间没心跳，路由自己过期
- `message-service` 再去查路由时，就会把它当成“不在线”

#### `message-service` 怎么读它

`GrpcMessageRecipientDispatcher` 会：

1. `GET online:user:{uid}`
2. 解析出当前负责这个用户的网关和连接
3. 根据 `gatewayPod` 找到目标网关地址
4. 发 gRPC，把消息定向投给那条连接

#### 通俗理解

它像一张“在线用户去向表”。

如果用户在线，这张表会写：

- 人在哪台网关
- 用的是哪条连接
- 这是不是最新的一次登录

消息服务发消息前，先翻这张表，再决定往哪送。

### 3.3 会话内顺序号

对应代码：

- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`

#### 它存了什么

key 形态：

- `mochat:conversation:seq:{conversationId}`

value 是一个整数计数器。

#### 为什么用 String 计数器

因为 Redis 的 `INCR` 很适合做这种“不断往上加 1”的场景。

项目要的不是“全局绝对连续”，而是“同一会话里消息顺序不能倒退”。这一点，Redis 计数器很合适。

#### 它现在怎么工作

第一次给某个会话分配顺序号时：

1. 先看看 Redis 里有没有这个 key
2. 如果没有，就先从数据库读这个会话当前最新的 `seq`
3. 用 `SETNX` 把 Redis 里的起点补上
4. 然后 `INCR` 取下一个序号

平时热路径上，核心就是 `INCR`。

#### 为什么还要本地锁

项目在进入 `INCR` 前，还会先按 `conversationId` 加一把本地锁。

这么做主要是为了：

- 让同一会话在当前进程内的处理更顺
- 让“幂等判断 + 分配 seq + 发 MQ”这一串逻辑别并发打架

#### 通俗理解

这就像每个会话都有一个自动取号机。

发一条消息，取一个新号：

- 第一条是 1
- 第二条是 2
- 第三条是 3

这样历史查询和已读推进都容易处理。

### 3.4 幂等窗口

对应代码：

- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`

#### 它存了什么

key 形态：

- `mochat:idempotency:{senderUid}:{clientMsgId}`

value 形态：

- `msgId:seq`

默认 TTL：

- 5 分钟

#### 为什么用 String

因为它本质上就是一张短期“查重便签”：

- 这条客户端消息之前有没有处理过？
- 如果处理过，当时分到的 `msgId` 和 `seq` 是多少？

这种数据非常小，用 String 最省事。

#### 它现在怎么工作

`MessageIngestService` 在真正接收消息前，先查这个 key：

- 查到了：直接复用旧的 `msgId/seq` 回给客户端
- 没查到：继续正常处理

等 MQ 明确接收成功之后，再用 `SET NX PX` 把这次结果记下来。

这里的 `NX` 很重要，它保证“只有第一次能写进去”。

#### 通俗理解

客户端可能因为网络抖动重发同一条消息。

这个结构就是给服务端一个短期记忆：

“这条我刚才处理过了，不要再来一遍。”

### 3.5 离线补发队列

对应代码：

- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageServiceOfflineReplayService.java`

#### 它存了什么

key 形态：

- `mochat:offline:{userId}`

结构：

- Redis `List`

#### 为什么用 List

因为它要解决的是一个非常直接的问题：

“用户不在线时，先把消息按顺序排队；等他上线，再按原顺序一条条补发。”

List 天生就适合做这个事。

项目的做法是：

- 入队：`RPUSH`
- 裁掉过长队列：`LTRIM`
- 出队一批：`LPOP count`

这样能保持“旧消息在前，新消息在后”。

#### 现在的边界

- 这不是长期消息存档
- 它更像短期补发缓冲区
- 队列长度默认最多 50 条

而且补发失败时，没发成功的那部分还会重新塞回队列，避免顺序乱掉。

#### 通俗理解

可以把它想成“暂存盘”。

人不在线时，消息先堆在这里；人一上线，就从最早的一条开始补。

### 3.6 事件总线

对应代码：

- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisEventBus.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/OutboundEventSubscriber.java`

#### 它用了什么

这里用的是 Redis 的 `Pub/Sub`，也就是发布订阅。

典型主题：

- `connection.outbound`

#### 为什么用 Pub/Sub

它适合“我发一个事件，谁订阅谁就收”的轻量广播场景。

例如：

- 某条要发给客户端的消息，先发到主题里
- 订阅这个主题的连接层把消息写回对应连接

#### 它的特点

优点：

- 简单
- 延迟低
- 不用额外建复杂队列

限制：

- 它不是可靠消息存储
- Redis 不会替你把 Pub/Sub 消息长期保存下来等你以后再收

所以这个项目没有把“最终可靠投递”完全压在 Pub/Sub 上。真正需要兜底的部分，还是靠 MQ、数据库和离线队列。

#### 通俗理解

Pub/Sub 更像“广播喇叭”，不是“带签收的快递柜”。

### 3.7 群最近消息缓存

对应代码：

- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`

#### 它存了什么

key 形态：

- `mochat:group:messages:{groupId}`

结构：

- Redis `Sorted Set`

score：

- `seq`

member：

- `seq|payloadBase64`

#### 为什么用 Sorted Set

因为它要的是“保留最近一小段群消息，而且能按顺序拿回来”。

Sorted Set 的优势正好在这里：

- 元素自带分数
- Redis 会按分数维护顺序
- 很适合存“最近 N 条”

项目里把 `seq` 当 score，因此：

- 越旧的消息 `seq` 越小
- 越新的消息 `seq` 越大
- 裁掉最旧消息时很方便

#### 它现在怎么工作

持久化服务把消息真正写进 PostgreSQL 之后，如果这是群消息，就顺手更新缓存：

1. `ZADD`
2. `ZCARD`
3. 超出窗口就 `ZREMRANGEBYRANK`

本地进程里还有一层 Caffeine，先查本地，没命中再去 Redis 读。

默认每个群保留最近：

- 500 条消息

#### 通俗理解

这部分像“群聊最近消息速查页”。

真正的大账本在数据库；Redis 这里只放最近的一小段，方便快速拿。

## 4. 这个项目为什么选这些结构

把选择理由说得再直白一点：

- `String`
  - 适合单条、小块、整条读写的数据
  - 这个项目用它来放 session、幂等记录、在线路由、计数器

- `List`
  - 适合“先进先出”或“按顺序排队”
  - 这个项目用它来放离线补发队列

- `Sorted Set`
  - 适合“既要保存一批数据，又要按某个分数排序”
  - 这个项目用它来放群最近消息窗口

- `Pub/Sub`
  - 适合轻量广播
  - 这个项目用它来做跨进程事件转发，不承担最终可靠存储

## 5. 当前实现里值得注意的点

### 5.1 做得比较对的地方

1. 在线路由写入用了 Lua，把“更新路由 + 增加版本号 + 保留旧路由”绑成原子操作，这个方向是对的。
2. 幂等窗口用了 `SET NX PX`，这是 Redis 很常见也很稳妥的做法。
3. 会话顺序号用了 `INCR`，和这个场景很匹配。
4. 群缓存用了 `Sorted Set`，比用普通 `List` 更适合按 `seq` 维护窗口。
5. 离线消息队列用 `RPUSH + LTRIM + LPOP`，顺序语义比较清楚。

### 5.2 现在已经能看出来的问题

#### 问题 1：`route-epoch` 计数 key 没有过期时间

当前在线路由脚本会：

- 给 `online:user:{uid}` 设置 TTL
- 给 `online:user:{uid}:replaced-route:{epoch}` 设置 TTL

但是：

- `online:user:{uid}:route-epoch` 是通过 `INCR` 增长的
- 没有配套 `EXPIRE`

这意味着：

- 某个用户哪怕早就不在线了
- 这个“路由版本号计数器”key 也可能一直留在 Redis 里

这不是立刻炸掉系统的大问题，但它会让 Redis 里留下越来越多本该消失的计数 key。

#### 问题 2：session 过期是“写在值里”，不是 Redis 原生 TTL

`SessionService` 里虽然有 30 天的过期概念，但现在是：

- 把过期时间写进字符串
- 读取时自己比较时间

Redis 本身并不会自动清掉过期 session key。

结果就是：

- 没有人来查的旧 session，可能一直留着
- Redis 会积累很多历史 session 记录

#### 问题 3：离线队列没有保留期限

`mochat:offline:{userId}` 现在只限制“最多 50 条”，但没有 TTL。

如果一个用户很久不上线：

- 队列不会自己过期
- Redis 会一直帮他留着这 50 条

如果用户量大，这类短期缓冲数据会越积越多。

#### 问题 4：群最近消息缓存没有 TTL

`mochat:group:messages:{groupId}` 是派生缓存，理论上随时都能从数据库重新补回来。

但它当前没有 TTL。

这意味着：

- 哪怕某个群很久没人说话
- 它的缓存 key 也可能一直留着

#### 问题 5：有些操作是“多条命令连着发”，但还没做批量优化

例如：

- 离线队列入队：`RPUSH` 后再 `LTRIM`
- 群缓存更新：`ZADD` 后再 `ZCARD` 再 `ZREMRANGEBYRANK`
- 群消息分发时，每个接收人都单独 `GET` 一次在线路由

在本机开发时这不明显，但网络来回成本一上来，就会让 Redis 往返次数变多。

#### 问题 6：Session 的本地缓存目前没有真正吃到收益

`SessionService` 里建了 Caffeine 缓存，也会 `put` 和 `invalidate`，但当前读取逻辑并没有先查它。

也就是说：

- 现在它更像“做了准备”
- 还不是“真正生效的 Redis 减压手段”

## 6. 怎么优化，按优先级来

下面分成三档：

- 立刻值得做
- 看业务量决定
- 不建议现在急着做

### 6.1 立刻值得做

#### 优化 1：给 `online:user:{uid}:route-epoch` 补过期策略

建议做法：

- 在写路由 Lua 脚本里，给 `route-epoch` 也补一个 `EXPIRE`
- 或者把它和主路由 key 的生命周期绑在一起

为什么值当先做：

- 改动不大
- 收益明确
- 可以直接减少无用 key 的堆积

#### 优化 2：给“短期协调数据”补 TTL

优先考虑这几类：

- `mochat:offline:{userId}`
- `mochat:group:messages:{groupId}`

原因：

- 这两类都不是最终事实数据
- Redis 丢了它们，系统仍然能靠数据库或后续行为恢复

比较稳妥的思路：

- 离线队列：每次入队时刷新 TTL，比如保留 3 天、7 天或 30 天
- 群缓存：每次写缓存时刷新 TTL，比如几小时到几天

这样能让 Redis 更像“临时工作台”，而不是“越放越多的储物间”。

#### 优化 3：session 要么真用 Redis TTL，要么补后台清理

这块有两种路线：

1. 保守路线
   - 保持当前字符串格式
   - 定时扫旧 session 并删除
2. 更直接的路线
   - 在写 `mochat:session:{sessionId}` 时直接带 TTL
   - 如果还想保留“EXPIRED”和“REPLACED”的可解释状态，就再加一个短期 tombstone 或轻量状态记录

如果你更看重“Redis 干净”和“自动回收”，第二种更值得做。

#### 优化 4：把多次往返压成一次

优先看这几块：

- 离线队列 `RPUSH + LTRIM`
- 群缓存 `ZADD + ZCARD + ZREMRANGEBYRANK`
- 批量读取在线路由

可选方法：

- Lettuce pipeline
- Lua 脚本
- `MGET` 这种多 key 一次读取

适合这个项目的理解方式是：

不是 Redis 算得慢，而是“来回跑太多趟”。

### 6.2 看业务量决定

#### 优化 5：群消息分发时批量拉路由

当前群消息分发是逐个用户去 `GET online:user:{uid}`。

如果群规模不大，这样完全能用。

但如果群成员变多，或者发送频率变高，可以考虑：

- 先批量收集 recipient uid
- 用 `MGET` 或 pipeline 一次拉一批路由
- 再按 `gatewayPod` 分组发 gRPC

这样能减少：

- Redis 往返次数
- 重复的路由解析成本

#### 优化 6：如果确实要保留 Session 本地缓存，就把它真正接到读取路径

现在的 Caffeine 更像“摆在那儿了，还没真的干活”。

可以考虑：

- 对短时间内反复校验的活跃 session 做极短 TTL 本地缓存
- 命中时少打一次 Redis

但这里要非常小心：

- Redis 现在是权威状态
- 本地缓存不能长时间自作主张

所以如果做，建议：

- 缓存时间短
- 只缓存明确的活跃结果
- 新登录、撤销、替换时一定主动失效

#### 优化 7：群缓存读路径按需要再扩大

当前群缓存已经是“Redis + 本地内存”两级结构，但从代码使用情况看，它更像为后续读优化预留的能力。

如果之后群历史读取真的很热，再继续做：

- 更明确的读命中统计
- 更合适的窗口大小
- 按热点群做更细的本地策略

### 6.3 不建议现在急着做

#### 不建议 1：为了“看起来规范”把所有 String 都改成 Hash

Redis Hash 确实适合字段化数据，但这个项目里不少数据其实是：

- 很小
- 整条读写
- 字段数固定

这时候 String 反而更直接。

所以：

- session、幂等记录、在线路由现在用 String 没有本质问题
- 不要为了“结构化”就先做一轮大改

#### 不建议 2：把 Pub/Sub 当成可靠队列来加码使用

Pub/Sub 很适合广播，但它不是这套系统里最稳的可靠投递底座。

如果以后真的需要：

- 消息能积压
- 消费端能补拉
- 有明确消费确认

那应该优先考虑：

- 继续让 RocketMQ 承担可靠异步链路
- 或者评估 Redis Streams

而不是硬把 Pub/Sub 往“可靠队列”方向拧。

## 7. 一份更接地气的判断

如果把 Redis 在这个项目里的职责用生活化的话说：

- session：门禁卡登记表
- 在线路由：人现在在哪个窗口办事
- seq：排号机
- 幂等窗口：刚办过的业务临时记录
- 离线队列：人不在时的暂存箱
- Pub/Sub：广播喇叭
- 群缓存：最近消息速查页

这也决定了优化方向：

- 该长期保存的，不要只靠 Redis
- 只是临时协调的，要敢于设置过期
- 需要顺序的，用适合顺序的结构
- 需要减少网络来回的，优先考虑批量请求

## 8. 建议的落地顺序

### 第一批

1. 给 `online:user:{uid}:route-epoch` 补 TTL
2. 给 `mochat:offline:{userId}` 补 TTL
3. 给 `mochat:group:messages:{groupId}` 补 TTL

### 第二批

1. 评估 session 改成 Redis 原生 TTL 还是保留现状加后台清理
2. 给离线队列和群缓存更新加 pipeline 或 Lua
3. 给群消息分发加批量路由读取

### 第三批

1. 决定 `SessionService` 的本地缓存到底要不要真正生效
2. 再看是否要调整在线路由存储格式

## 9. 代码定位

和 Redis 直接相关的核心实现主要在这些文件：

- `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`
- `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/RedisOnlineRouteChannelSessionRegistry.java`
- `message-service-app/src/main/java/com/github/lystran/mochat/messageservice/grpc/GrpcMessageRecipientDispatcher.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
- `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisEventBus.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`

## 10. 外部参考

这份文档整理时额外参考了 Redis 官方文档：

- Redis data types  
  <https://redis.io/docs/latest/develop/data-types/>
- Redis Strings  
  <https://redis.io/docs/latest/develop/data-types/strings/>
- Redis Lists  
  <https://redis.io/docs/latest/develop/data-types/lists/>
- Redis Sorted Sets  
  <https://redis.io/docs/latest/develop/data-types/sorted-sets/>
- Redis Pub/Sub  
  <https://redis.io/docs/latest/develop/pubsub/>
- Redis EXPIRE  
  <https://redis.io/docs/latest/commands/expire/>
- Redis Pipelining  
  <https://redis.io/docs/latest/develop/using-commands/pipelining/>

这些资料主要用来确认：

- 不同数据结构本来擅长什么
- TTL 的语义
- 批量请求为什么能减少来回开销
- Pub/Sub 适合做什么、不适合做什么
