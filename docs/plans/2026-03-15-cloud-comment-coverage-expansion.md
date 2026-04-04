# Cloud Branch Comment Coverage Expansion Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Expand Chinese comments across the cloud-native branch so every production Java class and method has a clear purpose comment, while complex variables and non-obvious logic gain concise, plain-language explanations without changing behavior.

**Architecture:** Treat this as documentation-only editing on top of the cloud-native four-service topology. Split the codebase into disjoint write domains that match the current runtime shape so workers can annotate in parallel, then run a controller-led pass to remove overly hard-core wording and make comments explain what the code is concretely doing.

**Tech Stack:** Java 25, Gradle Kotlin DSL, Micronaut 4, Netty, gRPC Java, Redis, RocketMQ, Kubernetes topology docs, OpenSpec Markdown specs

---

### Task 1: Inventory cloud-native source scope

**Files:**
- Modify: `docs/plans/2026-03-15-cloud-comment-coverage-expansion.md`

**Step 1: Define scope**
- Cover every file under `*/src/main/java`.
- Skip tests, generated protobuf outputs, YAML, SQL, and docs content themselves.

**Step 2: Define comment rules**
- Every top-level `class / interface / record / enum` gets a short Chinese purpose comment.
- Every explicit constructor and method gets a short Chinese responsibility comment.
- Add inline comments only for business-complex variables, routing ownership, service-to-service calls, fallback branches, transaction boundaries, queue/window semantics, drain semantics, or non-obvious state transitions.
- Keep wording plain. Replace hard terms like `outbound`, `payload`, `watermark`, `fallback`, `subscription`, `snapshot`, `route epoch` with concrete explanations of what the code is doing whenever possible.
- Do not translate obvious code line by line.

**Step 3: Split into independent write domains**
- Domain A: `app`, `common`, `protocol`, `service-runtime`
- Domain B: `access-gateway-app`, `connection-module`
- Domain C: `api-service-app`, `logic-module/src/main/java/com/github/lystran/mochat/logic/http`
- Domain D: `logic-module/src/main/java/com/github/lystran/mochat/logic/service`, `logic-module/src/main/java/com/github/lystran/mochat/logic/repository`
- Domain E: `message-service-app`, `logic-module/src/main/java/com/github/lystran/mochat/logic/chat`, `logic-module/src/main/java/com/github/lystran/mochat/logic/mq`, `message-module`
- Domain F: `persistence-service-app`, `persistence-module`, `infra-redis`

### Task 2: Annotate Domain A

**Files:**
- Modify: all production Java files in `app`, `common`, `protocol`, `service-runtime`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for compatibility shell behavior, shared contracts, runtime config objects, ID/session/lock semantics, and legacy-vs-cloud boundaries**

### Task 3: Annotate Domain B

**Files:**
- Modify: all production Java files in `access-gateway-app`, `connection-module`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for gateway ownership, bind flow, duplicate login replacement, drain flow, TCP/TLS pipeline order, targeted gateway delivery, and route lease renewal**

### Task 4: Annotate Domain C

**Files:**
- Modify: all production Java files in `api-service-app`, `logic-module/.../http`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for login/session authority, HTTP read-side contracts, offline replay trigger, and cloud API-to-gRPC bridging**

### Task 5: Annotate Domain D

**Files:**
- Modify: all production Java files in `logic-module/.../service`, `logic-module/.../repository`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for read-side ownership, relationship checks, SQL branch meaning, receipt/state progress meaning, and repository mapping semantics**

### Task 6: Annotate Domain E

**Files:**
- Modify: all production Java files in `message-service-app`, `logic-module/.../chat`, `logic-module/.../mq`, `message-module`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for sync accept path, MQ handoff, send confirmation timing, targeted delivery, offline fallback, and replayable message packaging**

### Task 7: Annotate Domain F

**Files:**
- Modify: all production Java files in `persistence-service-app`, `persistence-module`, `infra-redis`

**Step 1: Add class-level purpose comments**

**Step 2: Add method-level responsibility comments**

**Step 3: Add inline comments for durable write ownership, Redis route/session/coordinator roles, durable idempotency, cache refresh-after-commit, and background consumer flow**

### Task 8: Controller consistency pass

**Files:**
- Modify: any touched files that need wording normalization

**Step 1: Scan for hard or unexplained terms**
- Rewrite comments that still hide meaning behind jargon.
- Prefer “把什么数据发给谁” over abstract architecture nouns.

**Step 2: Spot-check representative large files**
- `access-gateway-app/.../GatewayIngressLifecycle.java`
- `message-service-app/.../GrpcMessageRecipientDispatcher.java`
- `logic-module/.../MessageIngestService.java`
- `persistence-module/.../MqConsumer.java`
- `service-runtime/.../RuntimeTopologyConfiguration.java`

**Step 3: Remove low-value repetition**
- Compress comments that only restate method names.

### Task 9: Verification

**Files:**
- No new files

**Step 1: Run formatting sanity check**
Run: `git diff --check`
Expected: no whitespace or conflict-marker issues

**Step 2: Run compile verification**
Run: `./gradlew :app:compileJava :common:compileJava :connection-module:compileJava :infra-redis:compileJava :logic-module:compileJava :message-module:compileJava :persistence-module:compileJava :protocol:compileJava :service-runtime:compileJava :access-gateway-app:compileJava :api-service-app:compileJava :message-service-app:compileJava :persistence-service-app:compileJava`
Expected: `BUILD SUCCESSFUL`
