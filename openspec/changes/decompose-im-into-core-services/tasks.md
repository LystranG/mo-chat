## 1. 服务拆分骨架

- [x] 1.1 盘点现有 `app`、`connection-module`、`logic-module`、`message-module`、`persistence-module` 的启动与装配职责，并标注哪些 Bean 归属 `access-gateway`、`api-service`、`message-service`、`persistence-service`
- [x] 1.2 为四个服务规划新的 Gradle 模块或独立启动入口，确保每个服务都能单独启动而不再依赖单体统一装配
- [x] 1.3 提炼共享协议与领域契约层，明确哪些类型保留在 `protocol/common`，哪些只允许服务内部使用
- [x] 1.4 调整配置结构，拆出每个服务自己的 Micronaut 配置命名空间、端口和基础依赖开关

## 2. 内部 gRPC 契约

- [ ] 2.1 为 `api-service` 定义内部 gRPC 契约，至少覆盖 session 解析、私聊权限校验、群发送上下文或成员列表查询
- [ ] 2.2 为 `access-gateway` 定义内部 gRPC 契约，至少覆盖定点投递、踢旧连接、查询本地连接状态
- [ ] 2.3 为 `message-service` 定义内部命令契约，至少覆盖私聊发送、群聊发送、离线重放、接收确认
- [ ] 2.4 生成并接入 gRPC/protobuf 代码，补齐各服务之间的客户端装配与基础连通性测试

## 3. `access-gateway` 路由与连接归属

- [ ] 3.1 把 TCP 接入、握手、限流、心跳和出站写回逻辑抽离到 `access-gateway`
- [ ] 3.2 实现 bind 成功后写入 Redis 在线路由记录的流程，记录 `gatewayPod`、`connectionId`、`sessionId`、`sessionVersion`、`routeEpoch` 和租约信息
- [ ] 3.3 实现单用户单活连接语义：新 bind 覆盖旧路由并触发踢旧连接流程
- [ ] 3.4 实现心跳续租与“发现 route 已失效则自杀”的逻辑，防止旧连接继续占有在线状态
- [ ] 3.5 实现 gateway drain 模式：拒绝新归属、保留存量连接、宽限期后主动断开

## 4. `api-service` 会话与业务权威

- [ ] 4.1 把登录、session 签发与 session authority 职责集中到 `api-service`
- [ ] 4.2 为 session 引入 `sessionVersion` 语义，并让内部 session 解析接口返回足够的栅栏信息
- [ ] 4.3 把好友、拉黑、群成员资格等消息发送前置校验收敛成内部可调用的业务接口
- [ ] 4.4 保持历史查询继续归属 `api-service`，并补充它与新消息/持久化边界之间的说明和测试

## 5. `message-service` 消息编排

- [ ] 5.1 把消息摄入、幂等、`seq` 分配、`msgId` 分配和 sender ACK 逻辑迁移到 `message-service`
- [ ] 5.2 接入 `api-service` 的内部校验接口，确保私聊/群聊发送不再依赖单体内直接调用仓储
- [ ] 5.3 接入 Redis 在线路由解析与 `access-gateway` 定点投递，统一处理 `DELIVERED`、`USER_OFFLINE`、`ROUTE_STALE`、`WRITE_FAILED`
- [ ] 5.4 实现“重查路由一次，仍失败则写离线队列”的降级策略
- [ ] 5.5 调整 sender ACK 语义与离线重放逻辑，确保 `SEND_ACK` 只表示 MQ 接收成功，不表示 DB 已提交

## 6. `persistence-service` 持久化边界

- [ ] 6.1 把 MQ consumer 和持久化事务链路迁移到 `persistence-service`
- [ ] 6.2 让 `persistence-service` 独占 `messages`、`conversations`、receipt/state 推进和群缓存 post-commit 更新
- [ ] 6.3 校验 MQ 重试与数据库幂等行为，确保重复消费不会生成重复消息事实
- [ ] 6.4 补充“实时投递先完成、历史稍后可见”的最终一致性验证用例

## 7. 集成验证与回滚准备

- [ ] 7.1 补充跨 pod 场景集成测试，覆盖 A/B 两个用户落在不同 gateway 上的私聊投递
- [ ] 7.2 补充重复登录替换旧连接、route stale、自杀关闭和离线补推回退的测试
- [ ] 7.3 补充 gateway drain / rollout 场景测试，验证不接新连接且存量连接能在宽限期后重连恢复
- [ ] 7.4 更新运行文档与部署说明，描述四服务拓扑、共享基础设施、内部 gRPC 端口与回滚方式
- [ ] 7.5 清理旧的单体装配假设，确认所有服务都能独立启动并通过现有或新增验证基线
