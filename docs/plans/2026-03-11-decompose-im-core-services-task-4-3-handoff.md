# OpenSpec `decompose-im-into-core-services` 交接（2026-03-11，Task 4.3 完成）

## 当前状态
- 已完成并勾选：`3.1`、`3.2`、`3.3`、`3.4`、`3.5`、`4.1`、`4.2`、`4.3`
- 当前下一个未完成任务：`4.4`
- 当前 worktree 固定为：
  - `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- 当前 worktree 仍然很脏，包含 `3.1 / 3.2 / 3.3 / 3.4 / 3.5 / 4.1 / 4.2 / 4.3` 的未提交改动；不要回滚

## `4.3` 最终落实的语义边界
- `api-service` 仍然是 session authority；没有回退 `4.1 / 4.2` 已完成的 authority / `sessionVersion` fence 语义。
- `4.3` 只把消息发送前置业务校验收敛成 `api-service` 内部可调用接口：
  - 私聊：`ALLOWED / NOT_FRIEND / BLOCKED`
  - 群聊：`ALLOWED / NOT_MEMBER / GROUP_NOT_FOUND + member_uids`
- 本轮没有扩到：
  - `4.4` 的历史查询边界
  - `5.2` 的跨服务接入
  - `5.3+` 的在线路由投递 / 离线回退
  - `7.3` 的 drain / rollout 集成测试

## `4.3` 关键落地

### `logic-module` 新增前置校验业务接口
- 文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/service/MessageSendPolicyService.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/service/MessageSendPolicyServiceTest.java`
- 关键点：
  - 新增 `MessageSendPolicyService`，把私聊与群聊的消息发送前置校验从 gRPC service 方法体里抽出，形成 `api-service` 内部明确可调用的业务接口。
  - 私聊校验会对 `(senderUid, recipientUid)` 做有序化后查询已有好友关系状态，并映射成 `ALLOWED / NOT_FRIEND / BLOCKED`。
  - 群聊校验会先区分 `groupId <= 0` 与真实不存在群组，再判断 sender 是否为 active member，最后在允许发送时返回 `member_uids`。

### `MessageRelationshipRepository` 补齐 `GROUP_NOT_FOUND` 所需能力
- 文件：
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/MessageRelationshipRepository.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcMessageRelationshipRepository.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/AllowAllMessageRelationshipRepository.java`
- 关键点：
  - 为支撑群聊前置校验的 `GROUP_NOT_FOUND` 语义，仓储接口新增 `groupExists(long groupId)`。
  - JDBC 实现通过 `groups` 表显式判断群存在性；allow-all 与测试 stub 同步补齐该接口，确保现有测试编译和语义都一致。

### `api-service` internal gRPC 从 placeholder 切到真实业务语义
- 文件：
  - `api-service-app/src/main/java/com/github/lystran/mochat/apiservice/grpc/ApiInternalGrpcService.java`
  - `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceGrpcConnectivityTest.java`
- 关键点：
  - `CheckPrivateMessagingPolicy` 不再使用“正数 UID 即放行”的 placeholder，而是直接委托 `MessageSendPolicyService`。
  - `GetGroupSendContext` 不再把 `senderUid` 自己塞进 `member_uids` 作为假数据，而是返回真实的 `GROUP_NOT_FOUND / NOT_MEMBER / ALLOWED + member_uids`。
  - gRPC 测试覆盖了私聊三态和群聊三态的映射，不需要拉起 HTTP / EmbeddedServer 集成链路。

## 已通过的验证
- TDD red：
  - `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.MessageSendPolicyServiceTest`
  - 初始因 `MessageSendPolicyService` 缺失而按预期失败。
- focused 绿测：
  - `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.MessageSendPolicyServiceTest`
  - `./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceGrpcConnectivityTest`
- focused 回归：
  - `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.MessageSendPolicyServiceTest --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceRelationshipAndGroupTest --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceGrpcConnectivityTest --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest`
- fresh：
  - `./gradlew :logic-module:test :api-service-app:test :connection-module:test :access-gateway-app:test --rerun-tasks`
  - 最终结果：`BUILD SUCCESSFUL in 2m 14s`

## 审查结论
- 本地边界检查结论：`4.3` 没有越过 `4.4+ / 5.x+ / 7.3`，也没有回退 `4.1 / 4.2`。
- 本地 code review 未发现 blocker / important 问题。
- 当前仅有的小风险：
  - 群存在性与成员资格目前是两次仓储查询，语义正确；若后续 `5.2` 热路径对查询次数敏感，再一起评估是否收敛。

## 下一步 OpenSpec 任务
- 若继续按 `tasks.md` 顺序推进，只从 `4.4` 开始。
- 不要回头重做 `4.3`，也不要提前跳到 `5.2+` 或 `7.3`。
- `4.4` 的范围是：
  - 保持历史查询继续归属 `api-service`，并补充它与新消息/持久化边界之间的说明和测试。

## 当前 worktree 约束
- 当前 worktree 很脏；不要使用破坏性 git 命令。
- 当前阶段仍不需要本地 K8s。
- 后续若继续推进，仍需遵守：
  - `using-superpowers`
  - `test-driven-development`
  - `subagent-driven-development`
  - 收尾用 `verification-before-completion`
