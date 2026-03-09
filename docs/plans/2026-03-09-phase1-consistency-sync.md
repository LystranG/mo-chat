# Phase1 Consistency Sync Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 `single-node-cloud-native-im-phase1` 的实现、spec、design、docs、tests 统一到可归档状态，优先保留已有 `seq` 历史契约并修复明确行为缺口。

**Architecture:** 这是已有仓库上的一致性修复，不做横向重构。历史 API 以 `tasks.md` 与现有代码为准统一到 `seq/cursorSeq/startSeq/endSeq/limit<=50`；TCP 认证失败与 MQ 发布失败在入口侧转成明确协议错误响应；TLS 默认行为在配置、运行时装配、spec、测试之间统一。

**Tech Stack:** Java 25, Gradle, Micronaut, Netty, RocketMQ, OpenSpec, JUnit 5, Mockito.

---

### Task 1: 统一历史分页契约到 seq 模型

**Files:**
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/design.md`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/specs/data-model-and-history-pagination/spec.md`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/proposal.md`
- Modify: `openspec/specs/data-model-and-history-pagination/spec.md`
- Modify: `docs/phase1-requirements.md`
- Modify: `docs/mochat-technical-documentation.md`
- Check only: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- Check only: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- Check only: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`
- Check only: `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java`

**Step 1: 先写/改失败测试**
- 将 `HistoryControllerTest` 的 mock/stub/verify 从旧的 `query(conversationId, cursorSeq, limit)` 改为当前契约 `query(conversationId, cursorSeq, startSeq, endSeq, limit)`。
- 增加一个范围查询 happy-path 测试，覆盖 `startSeq/endSeq` 正常透传。
- 增加至少一个参数互斥校验测试：`cursorSeq` 与 `startSeq/endSeq` 同时出现时报 `400`。

**Step 2: 运行红灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest -g .gradle`
- Expected: 现状下旧测试失败，证明历史测试仍绑定旧契约。

**Step 3: 最小实现/文档统一**
- 如果控制器/服务无需改动，则不改实现，只修测试和文档。
- 将设计/spec/docs 中所有 `msgId + limit` 的历史契约更新为 `seq`：
  - 游标字段 `cursorSeq`
  - 范围字段 `startSeq/endSeq`
  - 默认/最大 `limit=50`
  - 范围模式与游标模式互斥
- 明确写出“保留现实现，因为 `tasks.md` 3.5 已定义为 seq-based history API”。

**Step 4: 运行绿灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest -g .gradle`
- Run: `rg -n "msgId \+ limit|cursorSeq|startSeq|endSeq" openspec docs logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
- Expected: 测试通过；被触达的 spec/docs 不再残留旧 `msgId + limit` 描述。

**Step 5: 不提交 commit**
- 用户明确要求本次不提交。

### Task 2: 让 TCP 无效 session 返回明确认证失败

**Files:**
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java`
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- Modify: `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/specs/login-session-and-user-bootstrap/spec.md`

**Step 1: 先写失败测试**
- 在 `InboundMessageConsumerErrorResponseTest` 增加场景：当 inbound 事件带有可回路由的用户上下文时，private/group/receipt 请求中的 `sessionId` 无效要发 `ERROR_RESPONSE`，错误码为认证失败，且不得继续进入 ingest/receipt 处理。
- 在 `SessionBindingHandlerTest` 增加场景：连接首次收到无效 `sessionId` 的 private/group/receipt 请求时，直接向当前 channel 写回认证失败帧，并且不继续向后传递到业务侧。

**Step 2: 运行红灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest -g .gradle`
- Expected: 新增的 invalid-session 场景失败，证明当前实现仍在静默 return。

**Step 3: 最小实现**
- 在 `InboundMessageConsumer` 的 private/group/receipt 三条路径中，把 `resolveUserId(...).isEmpty()` 从静默 return 改为：当 inbound 事件携带路由用户上下文时发 `ERROR_RESPONSE`。
- 在连接入口补根因修复：由于 fresh invalid session 在逻辑层无法拿到 channel 句柄，`SessionBindingHandler` 需要直接向 channel 写回认证失败帧，并阻止该消息继续路由到 `InboundRouterHandler`。
- `InboundRouterHandler` 可携带已绑定用户上下文，供逻辑层在“已绑定连接后 session 失效”这类场景里继续发明确错误响应。
- 不更改有效 session 路径，不做额外重构。
- spec 中明确“chat TCP 请求 session 无效时返回认证失败协议响应”。

**Step 4: 运行绿灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest -g .gradle`

**Step 5: 不提交 commit**
- 用户明确要求本次不提交。

### Task 3: 将 RocketMQ publish failure 转成 `1500` 协议错误响应

**Files:**
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/specs/mq-persistence-pipeline-and-idempotency/spec.md`

**Step 1: 先写失败测试**
- 在 `MessageIngestServiceTest` 将 “publish 失败抛 `IllegalStateException`” 改成“抛出可映射为协议错误的领域异常”，并断言不会存 idempotency、不发 success ACK。
- 在 `InboundMessageConsumerErrorResponseTest` 增加 publish failure 场景，断言返回 `ERROR_RESPONSE`，错误码 `1500`，且没有 `SEND_ACK`。

**Step 2: 运行红灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest -g .gradle`
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest -g .gradle`
- Expected: 现状下至少 publish failure 相关断言失败。

**Step 3: 最小实现**
- 在 `MessageIngestService` 中把 MQ publish 失败从裸 `IllegalStateException` 收敛为带 `MQ_PUBLISH_FAILED (1500)` 的拒绝异常。
- 在 `InboundMessageConsumer` 中沿用既有 rejection -> `ERROR_RESPONSE` 机制发回客户端。
- 保持“不发 success ACK、不写 idempotency、不推进会话状态”的现有正确副作用约束。

**Step 4: 运行绿灯**
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest -g .gradle`
- Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest -g .gradle`

**Step 5: 不提交 commit**
- 用户明确要求本次不提交。

### Task 4: 统一 TLS 默认行为与测试口径

**Files:**
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/main/resources/application-local.yml`（仅在需要区分本地开发体验时）
- Modify: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`
- Modify: `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/specs/transport-and-connection-lifecycle/spec.md`
- Modify: `docs/runbook.md`

**Step 1: 先写失败测试**
- 在 `ChatChannelInitializerTest` 增加/调整默认构造路径的断言，确保默认运行配置下必须装配 TLS handler。
- 如果运行时工厂允许 `Optional<SslContext>` 缺失后继续创建聊天服务，则增加测试证明这与 spec 冲突。

**Step 2: 运行红灯**
- Run: `./gradlew :connection-module:test --tests com.github.lystran.mochat.connection.ChatChannelInitializerTest -g .gradle`
- Expected: 若当前默认配置/装配允许非 TLS，则新测试失败。

**Step 3: 最小实现**
- 统一方向：默认配置启用 TLS；当 TLS 开启但无法创建 `SslContext` 时启动失败，而不是以 `null` 降级到明文 pipeline。
- 若保留 `application-local.yml` 作为本地自签名默认，需确保它与默认行为一致或明确只用于本地 profile，而不是掩盖默认配置的明文行为。
- `ChatChannelInitializer` 不再把“无 `SslContext`”视为正常聊天链路。
- runbook 写清默认 TLS、自签名/证书路径要求与失败表现。

**Step 4: 运行绿灯**
- Run: `./gradlew :connection-module:test --tests com.github.lystran.mochat.connection.ChatChannelInitializerTest -g .gradle`

**Step 5: 不提交 commit**
- 用户明确要求本次不提交。

### Task 5: 重新核对 native image 结论并记录环境限制

**Files:**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`（仅在需要把 8.6 的验证口径改成带前提条件时）
- Modify: `docs/runbook.md`（如需记录 native-image 前置条件）
- Check only: `app/build.gradle.kts`

**Step 1: 先收集证据**
- 运行 native compile，确认日志是否仍显示 `Skipping :app:nativeCompile: native-image is unavailable ...`。
- 检查当前环境是否有 `native-image` 可执行文件或 GraalVM 对应工具链。

**Step 2: 执行验证**
- Run: `./gradlew :app:nativeCompile -g .gradle`
- Run: `native-image --version`（若命令不存在，记录为环境限制）

**Step 3: 最小文档修正**
- 如果 native-image 缺失，则不要宣称通过；只把结论明确写成“构建任务被跳过，当前环境无法完成真实 native binary 验证”。
- 仅在拿到新鲜证据证明真正产物生成成功时，才更新为“通过”。

**Step 4: 最终总验**
- Run: `./gradlew test -g .gradle`
- Run: `./gradlew :app:nativeCompile -g .gradle`
- 最终报告必须区分：真正通过 / 因环境限制无法验证 / 仍未解决。

**Step 5: 不提交 commit**
- 用户明确要求本次不提交。
