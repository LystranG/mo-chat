# Decompose IM Core Services Task 7.x Test Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 OpenSpec `decompose-im-into-core-services` 的 `7.1 / 7.2 / 7.3` 补齐自动化集成测试，优先采用本地 `ApplicationContext` / `EmbeddedServer` / `EmbeddedChannel` 组合，而不是先引入 Kubernetes 运行环境。

**Architecture:** 复用现有 dedicated service 测试夹具，在 JVM 内启动最小 `api-service`、`message-service`、`access-gateway` 组合，必要时用内存 Redis 替身或录制 stub 收口外部依赖。`7.1` 先证明跨 gateway 的定点私聊投递；`7.2` 在同一测试基座上继续验证 duplicate-login fence、route stale、自杀关闭和 offline fallback；`7.3` 最后补 drain-and-reconnect 语义，不扩成真实 K8s rollout。

**Tech Stack:** Micronaut `ApplicationContext` / `EmbeddedServer`、gRPC server channel、Netty `EmbeddedChannel`、JUnit 5、Mockito、现有 recording test doubles。

---

### Task 1: `7.1` 跨 gateway 私聊投递

**Files:**
- Create: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceCrossGatewayRoutingIntegrationTest.java`
- Reuse: `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceSessionAuthorityIntegrationTest.java`
- Reuse: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcConnectivityTest.java`
- Reuse: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayOnlineRouteBindingTest.java`

**Step 1: Write the failing test**

新增一个 focused 用例，名称暂定：

```java
void privateSendTargetsRecipientOwnedByOtherGateway() throws Exception
```

断言要点：
- 用户 A 绑定到 `gateway-a`，用户 B 绑定到 `gateway-b`
- `message-service` 读取到 B 的 route 后，按 `gatewayPod -> targetAddress` 命中 `gateway-b`
- 私聊命令 accepted 后，只有 `gateway-b` 上的 B 连接收到 envelope；`gateway-a` 不收到 B 的投递

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest.privateSendTargetsRecipientOwnedByOtherGateway --rerun-tasks
```

Expected: FAIL，且失败原因是缺少测试基座或跨 context 连接逻辑，而不是拼写/装配错误。

**Step 3: Write minimal implementation**

只补最小测试夹具或最小生产代码缺口：
- 先优先在测试内拼装双 gateway + message-service + api-service
- 若现有生产代码仅缺少一层可注入适配或 target-address 暴露，再做最小补丁

**Step 4: Run test to verify it passes**

Run 同上。

Expected: PASS

### Task 2: `7.2` duplicate-login / stale / offline fallback

**Files:**
- Modify: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceCrossGatewayRoutingIntegrationTest.java`
- Create if needed: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceRouteFenceIntegrationTest.java`
- Reuse: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayOnlineRouteBindingTest.java`
- Reuse: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/GatewayRouteReplacementHandlerTest.java`
- Reuse: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/GrpcMessageRecipientDispatcherTest.java`

**Step 1: Write the failing tests**

按最小行为拆成独立红测，优先顺序：

```java
void newerBindMakesOlderGatewayRouteStaleAndOldConnectionSelfTerminates() throws Exception
void staleRouteAfterSingleRefreshFallsBackToOfflineQueue() throws Exception
```

断言要点：
- 新登录覆盖旧 route 后，旧 gateway 上旧连接在 heartbeat renew 或显式检查后被关闭
- `message-service` 首次投递遇到 stale / write failed 后只重查一次 route；二次仍失败则入 offline queue
- offline replay fallback 写入的是 replayable envelope，而不是直接丢消息

**Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest --rerun-tasks
```

Expected: 新增用例先红，且红在真实缺口上。

**Step 3: Write minimal implementation**

只补最小缺口：
- 测试夹具里把 duplicate-login 与双 gateway route 切换串起来
- 若 offline fallback 缺少可观察点，优先暴露/替换测试 bean，不先重构业务流

**Step 4: Run tests to verify they pass**

Run 同上。

Expected: PASS

### Task 3: `7.3` drain / rollout / reconnect

**Files:**
- Create: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/GatewayDrainRolloutIntegrationTest.java`
- Reuse: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/runtime/AccessGatewayOnlineRouteBindingTest.java`
- Reuse: `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GatewayDrainManager.java`

**Step 1: Write the failing tests**

新增最小行为用例，名称暂定：

```java
void drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace() throws Exception
```

断言要点：
- `gateway-a` 进入 drain 后，新 bind 不再拿到 ownership
- drain grace 内旧连接仍可继续服务
- grace 到期后旧连接被关闭，客户端重新连接 `gateway-b` 后可恢复为新 active route

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.GatewayDrainRolloutIntegrationTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace --rerun-tasks
```

Expected: FAIL，失败点集中在 rollout/reconnect 集成链路尚未被测试基座串起来。

**Step 3: Write minimal implementation**

优先补测试级编排：
- 双 gateway 本地运行
- 显式触发 `startDrain()` / grace 过期
- 重连到另一 gateway 后验证新 route

仅在现有生产代码无法表达“grace 后重连恢复”时，再补最小代码缺口。

**Step 4: Run test to verify it passes**

Run 同上。

Expected: PASS

### Task 4: Focused regression verification

**Files:**
- Test only: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceCrossGatewayRoutingIntegrationTest.java`
- Test only: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/GatewayDrainRolloutIntegrationTest.java`

**Step 1: Run focused suite**

```bash
./gradlew \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.GatewayDrainRolloutIntegrationTest \
  --rerun-tasks
```

Expected: 所有新增 focused 用例通过。

**Step 2: Run surrounding regression suite**

```bash
./gradlew \
  :message-service-app:test \
  --tests com.github.lystran.mochat.messageservice.MessageServiceGrpcConnectivityTest \
  --tests com.github.lystran.mochat.messageservice.GrpcMessageRecipientDispatcherTest \
  :access-gateway-app:test \
  --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest \
  --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest \
  :api-service-app:test \
  --tests com.github.lystran.mochat.apiservice.ApiServiceSessionAuthorityIntegrationTest \
  --rerun-tasks
```

Expected: PASS
