# Access Gateway Task 3.1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `access-gateway-app` own the TCP ingress/runtime skeleton for Task 3.1 without implementing route-write, duplicate-kick, stale self-termination, or drain behavior.

**Architecture:** Keep `connection-module` as the transport implementation and assemble the runtime inside `access-gateway-app`. Add a gRPC-backed `SessionResolver` adapter for bind-time session validation, wire Redis `EventBus`/`OfflineQueue`, `UserChannelDirectory`, TLS, `NettyChatServer`, and `OutboundEventSubscriber`, and start them through a dedicated lifecycle bean.

**Tech Stack:** Micronaut 4, Netty, gRPC, Lettuce Redis, JUnit 5

---

### Task 1: Prove `access-gateway-app` can assemble the connection runtime

**Files:**
- Modify: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`
- Modify: `access-gateway-app/build.gradle.kts`
- Modify: `access-gateway-app/src/main/resources/application.yml`
- Create: `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/**`

**Step 1: Write the failing test**

Add assertions that the application context exposes:
- `NettyChatServer`
- `OutboundEventSubscriber`
- `UserChannelDirectory`
- `EventBus`
- `OfflineQueue`

**Step 2: Run test to verify it fails**

Run: `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest`
Expected: FAIL because those runtime beans are not wired in `access-gateway-app`.

**Step 3: Write minimal implementation**

Add an `access-gateway-app` runtime factory/lifecycle that:
- creates Redis client/connection/pubsub beans
- creates Redis-backed `EventBus` and `OfflineQueue`
- creates `UserChannelDirectory`
- creates TLS context and `NettyChatServer`
- creates `OutboundEventSubscriber`

**Step 4: Run test to verify it passes**

Run: `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest`
Expected: PASS

### Task 2: Prove bind/handshake uses `api-service` gRPC rather than local monolith wiring

**Files:**
- Create: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewaySessionResolverAdapterTest.java`
- Create: `access-gateway-app/src/main/java/com/github/lystran/mochat/accessgateway/runtime/GrpcSessionResolver.java`

**Step 1: Write the failing test**

Add a test that:
- boots a Micronaut context with a server-side `SessionAuthorityApi`
- resolves `active:42:7` through the `SessionResolver` bean
- uses that resolver in `ChatChannelInitializer`
- verifies a private message binds user `42` into `UserChannelDirectory`
- verifies non-`ACTIVE` sessions are rejected

**Step 2: Run test to verify it fails**

Run: `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewaySessionResolverAdapterTest`
Expected: FAIL because no `SessionResolver` bean bridges gRPC to the existing connection pipeline.

**Step 3: Write minimal implementation**

Add a `SessionResolver` adapter backed by `SessionAuthorityApiBlockingStub`:
- return `Optional.of(userId)` only when gRPC status is `ACTIVE`
- return empty for invalid / expired / replaced / blank sessions

**Step 4: Run test to verify it passes**

Run: `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewaySessionResolverAdapterTest`
Expected: PASS

### Task 3: Verify the focused Task 3.1 slice end-to-end

**Files:**
- Modify: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayGrpcWiringTest.java`
- Modify: `openspec/changes/decompose-im-into-core-services/tasks.md` (only if implementation is complete)
- Modify: `docs/architecture/decompose-im-into-core-services-skeleton.md` (only if implementation changes the documented skeleton)

**Step 1: Run focused test set**

Run: `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest --tests com.github.lystran.mochat.accessgateway.AccessGatewaySessionResolverAdapterTest --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcWiringTest --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest`
Expected: PASS

**Step 2: Update docs/tasks only if green and only for Task 3.1**

- Mark `3.1` complete without touching the existing Task 2 completion note.
- Adjust architecture skeleton wording only if the new owner/runtime wiring should be documented now.

**Step 3: Run final verification**

Run: `./gradlew :access-gateway-app:test`
Expected: PASS
