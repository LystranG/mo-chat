# Phase1 Remaining Tasks Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完成 `single-node-cloud-native-im-phase1` 剩余的 `1.3`、`2.3`、`8.1`、`8.2`、`8.5`、`8.6`，并按批次更新 OpenSpec tasks 与跑定向验证。

**Architecture:** 维持当前单体分模块结构，不回滚现有脏改；通过新增 `message-module` 显式承载消息侧契约与事件，保持 `persistence-module` 继续负责持久化实现。协议层优先补齐“显式 codec + 集成测试 + native profile 校验”的缺口，不触碰用户禁止修改的历史查询相关文件。

**Tech Stack:** Gradle multi-module、Micronaut 4、Netty、protobuf、RocketMQ、PostgreSQL、Redis、Caffeine、GraalVM Native Build Tools。

---

### Task 1: 模块边界与协议编解码补齐（1.3 / 2.3）

**Files:**
- Create: `message-module/build.gradle.kts`
- Create: `message-module/src/main/java/com/github/lystran/mochat/message/...`
- Modify: `settings.gradle.kts`
- Modify: `logic-module/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`
- Modify: `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- Test: `protocol/src/test/java/com/github/lystran/mochat/protocol/ProtoRoundTripTest.java`

**Step 1: 写失败测试**
- 为 pipeline 增加显式 codec 期望（解码/编码/未知类型报错行为）。
- 为新模块契约增加最小编译期使用点，保证 `message-module` 真实参与装配。

**Step 2: 跑定向测试确认失败**
- Run: `./gradlew :connection-module:test --tests 'com.github.lystran.mochat.connection.ChatChannelInitializerTest' --rerun-tasks`
- Expected: 因缺少显式 codec/契约接线而失败。

**Step 3: 写最小实现**
- 新增 `message-module`，放置消息侧契约/事件对象。
- 让消息入口/持久化发布链路通过显式契约相连。
- 将 Netty pipeline 补为显式 protobuf codec handler，而不是占位式解码器。

**Step 4: 跑通过验证**
- Run: `./gradlew :protocol:test :connection-module:test :app:test --tests 'com.github.lystran.mochat.runtime.MochatRuntimeFactoryTest' --tests 'com.github.lystran.mochat.connection.ChatChannelInitializerTest' --rerun-tasks`
- Expected: 相关测试通过。

**Step 5: 更新 OpenSpec tasks**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`

### Task 2: 集成测试补齐（8.1 / 8.2 / 8.5）

**Files:**
- Modify: `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- Modify/Create: `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerHttpTest.java`
- Modify/Create: `logic-module/src/test/java/com/github/lystran/mochat/logic/http/AuthControllerOfflineReplayHttpTest.java`
- Modify/Create: `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MqConsumerTransactionTest.java`
- Modify/Create: `persistence-module/src/test/java/com/github/lystran/mochat/persistence/cache/GroupMessageCacheTest.java`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`

**Step 1: 写失败测试**
- 补协议 framing/max-frame/error response 集成测试。
- 补登录首登 key 校验、已注册 key 不可变、session chat 鉴权测试。
- 补 group cache 提交后更新与 500 条逐出边界测试。

**Step 2: 跑定向测试确认失败**
- Run: `./gradlew :connection-module:test --tests 'com.github.lystran.mochat.connection.ChatChannelInitializerTest' :logic-module:test --tests 'com.github.lystran.mochat.logic.http.AuthControllerHttpTest' :persistence-module:test --tests 'com.github.lystran.mochat.persistence.MqConsumerTransactionTest' --rerun-tasks`
- Expected: 新增断言先失败。

**Step 3: 写最小实现/补线**
- 仅补足测试所需的轻量实现缺口；不改动 `HistoryController`、`HistoryRepository`、`JdbcHistoryRepository`、`HistoryService`。

**Step 4: 跑通过验证**
- Run: `./gradlew :connection-module:test --tests 'com.github.lystran.mochat.connection.ChatChannelInitializerTest' :logic-module:test --tests 'com.github.lystran.mochat.logic.http.AuthControllerHttpTest' --tests 'com.github.lystran.mochat.logic.http.AuthControllerOfflineReplayHttpTest' :persistence-module:test --tests 'com.github.lystran.mochat.persistence.MqConsumerTransactionTest' --tests 'com.github.lystran.mochat.persistence.cache.GroupMessageCacheTest' --rerun-tasks`
- Expected: 批次测试通过。

**Step 5: 更新 OpenSpec tasks**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`

### Task 3: Native image profile 与最终复核（8.6）

**Files:**
- Modify: `app/build.gradle.kts`
- Modify/Create: `app/src/main/resources/META-INF/native-image/...`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- Optional Test: `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`

**Step 1: 写失败验证**
- 识别当前 native build 缺的 metadata/profile。
- 若环境具备 `native-image`，先跑一次收集错误。

**Step 2: 写最小修复**
- 补齐 native profile / metadata / build 参数。

**Step 3: 跑通过验证**
- Run: `./gradlew :app:nativeCompile`
- Expected: 在具备 GraalVM `native-image` 的环境中构建成功并产出二进制；若本地未配置，则明确切换到已安装 GraalVM toolchain 后再次验证，不能仅以 skip 关闭任务。

**Step 4: 更新 OpenSpec tasks**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`

### Task 4: 最终规格复核与代码复核

**Files:**
- Review: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- Review: `openspec/specs/**/spec.md`
- Review: 本轮实际变更文件

**Step 1: 规格复核**
- 用 OpenSpec 剩余任务与对应 spec 逐条对照。

**Step 2: 代码复核**
- 重点检查：未碰禁改文件、message/persistence 边界是否显式、测试是否覆盖新增行为、native build 证据是否 fresh。

**Step 3: 最终验证**
- Run: 本轮所有定向命令再次 fresh 执行。
