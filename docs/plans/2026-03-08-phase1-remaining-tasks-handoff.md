# OpenSpec `single-node-cloud-native-im-phase1` 交接（2026-03-08）

## 当前状态
- 已完成并勾选：`4.6`、`4.7`、`8.3`、`8.4`
- `8.4` 已 fresh 验证通过：
  - `./gradlew :logic-module:test --tests 'com.github.lystran.mochat.logic.chat.InboundMessageConsumerLifecycleTest' --tests 'com.github.lystran.mochat.logic.http.AuthControllerOfflineReplayHttpTest' --rerun-tasks`
- `8.4` 规格复核结论：PASS
- `8.4` 代码复核已补齐两条测试加严：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`

## 剩余 OpenSpec 任务
- `1.3` Create module structure for `message-module` and `persistence-module` with interface/event contracts.
- `2.3` Generate protobuf Java classes and integrate codec handlers into Netty pipeline.
- `8.1` Add integration tests for protocol framing, max frame length, and error responses.
- `8.2` Add integration tests for login/register key validation and session auth.
- `8.5` Add integration tests for group cache update ordering (after DB commit) and 500-entry eviction.
- `8.6` Add GraalVM native image build profile and verify native binary build passes.

## 重要约束
- 不要覆盖/回滚现有脏改，尤其不要碰：
  - `docs/runbook.md`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/HistoryRepository.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java`
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`
- 工作区当前有大量未提交改动，必须做外科手术式修改。
- 用户要求：继续使用 superpower subagent + TDD；每完成一批都更新 `openspec/changes/single-node-cloud-native-im-phase1/tasks.md` 勾选，并运行定向测试后再汇报。

## 建议执行顺序
1. 先核对 `1.3`、`2.3` 是否已有部分实现，仅补足缺口，不做无谓重构。
2. 再补 `8.1`、`8.2`、`8.5` 的集成测试，优先沿用现有 `MicronautTest` / 进程内集成测试模式。
3. 最后处理 `8.6`，补 native build profile，并用最小可验证命令证明能过。
4. 每个子任务完成后：
   - 更新 `tasks.md`
   - 运行对应定向测试/构建
   - 再进入下一个子任务

## 已知测试落点
- `8.3`：`logic-module/src/test/java/com/github/lystran/mochat/logic/http/SocialGraphLifecycleHttpIntegrationTest.java`
- `8.4`：
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`

## 新会话建议技能
- `using-superpowers`
- `brainstorming`
- `writing-plans`
- `test-driven-development`
- `subagent-driven-development`
- `verification-before-completion`
- 如收到 reviewer 反馈，再用 `receiving-code-review`
