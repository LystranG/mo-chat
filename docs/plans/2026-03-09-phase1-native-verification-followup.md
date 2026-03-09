# Phase1 Native Verification Follow-up Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 用 fresh 证据重新核验 Phase 1 的 GraalVM native build 状态，并仅在真实通过后更新任务与文档结论。

**Architecture:** 这次工作不改动主链路行为，先以 `command -v native-image`、`native-image --version` 与 `./gradlew :app:nativeCompile -g .gradle` 为事实来源确认是否真实生成 native binary。若通过，则把 `tasks.md`、`docs/runbook.md`、`docs/mochat-technical-documentation.md` 统一到“已真实验证通过”；若存在轻量缺口，仅收敛旧日志文案，不伪造完成状态。可选任务仅在实现已支持且测试缺口明显时补一个 TLS 显式证书优先于自签名的正向测试。

**Tech Stack:** Java 25, Gradle, GraalVM Native Image, Micronaut, Netty, JUnit 5, OpenSpec.

---

### Task 1: 记录真实 native 验证结论

**Files:**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- Modify: `docs/runbook.md`
- Modify: `docs/mochat-technical-documentation.md`
- Check only: `app/build.gradle.kts`
- Check only: `app/build/native/nativeCompile/mo-chat`

**Step 1: 固化 fresh 证据**

- Run: `command -v native-image`
- Expected: 返回实际可执行文件路径。
- Run: `native-image --version`
- Expected: 输出 Oracle GraalVM `25.0.1` 版本信息。
- Run: `./gradlew :app:nativeCompile -g .gradle`
- Expected: `:app:nativeCompile` 实际执行并生成 `app/build/native/nativeCompile/mo-chat`，`BUILD SUCCESSFUL`。

**Step 2: 最小文本更新**

- 将 `tasks.md` 8.6 从“当前环境缺少 native-image，未验证”更新为“已在 2026-03-09 的 GraalVM 25.0.1 环境完成真实 native binary 验证”。
- 更新 `docs/runbook.md` 的 native limitation / fallback 相关段落，删除过时的“2026-03-09 native-image 缺失”结论，改成 fresh 通过结论，并保留前置条件说明。
- 更新 `docs/mochat-technical-documentation.md` 中仍写着“当前环境 fresh 证据为 native-image 不可用”的段落，使之与本次真实构建结果一致。

**Step 3: 文本自检**

- Run: `rg -n "native-image.*127|native-image is unavailable|未验证|无法完成真实 native binary 验证" openspec/changes/single-node-cloud-native-im-phase1/tasks.md docs/runbook.md docs/mochat-technical-documentation.md`
- Expected: 不再在这三处保留与本次 fresh 证据冲突的旧结论。

**Step 4: 不提交 commit**

- 用户明确要求本次不提交。

### Task 2: 可选补齐 TLS 显式证书优先级正向测试

**Files:**
- Modify: `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`
- Check only: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`

**Step 1: 先确认是否值得补**

- 检查 `MochatRuntimeFactory.buildSslContext(...)` 是否已经在“证书路径成对存在”时直接走 `NettyChatServer.buildTls13Context(...)`，不依赖 `selfSigned` 开关。
- 若实现已经满足且现有测试只缺正向覆盖，则进入 Step 2；否则本任务跳过，不做额外行为修改。

**Step 2: 写最小正向测试**

- 用临时文件生成一对有效证书链 / 私钥（可使用 Netty `SelfSignedCertificate` 作为测试输入材料，但测试断言的目标是“显式路径优先”，不是“自签名可用”）。
- 调用 `MochatRuntimeFactory.buildSslContext(certPath, keyPath, true)`，断言成功返回 `SslContext`，从而证明显式有效证书对优先于 `selfSigned=true` 的回退路径。

**Step 3: fresh 验证**

- Run: `./gradlew :app:test --tests com.github.lystran.mochat.runtime.MochatRuntimeFactoryTest -g .gradle`
- Expected: 目标测试类通过。

**Step 4: 不提交 commit**

- 用户明确要求本次不提交。
