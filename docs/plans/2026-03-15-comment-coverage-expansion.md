# Comment Coverage Expansion Implementation Plan
> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Expand Chinese comments across production Java sources so every class and method has a purpose comment, while complex variables and non-obvious logic gain concise explanatory comments without changing behavior.

**Architecture:** Treat comments as documentation-only edits on existing production sources. Split the codebase into disjoint module groups so workers can annotate in parallel, then run a single consistency pass to normalize tone, avoid code translation, and keep comments aligned with docs/OpenSpec facts.

**Tech Stack:** Java 25, Gradle Kotlin DSL, Micronaut 4, Netty, Redis, RocketMQ, OpenSpec Markdown docs

---

### Task 1: Inventory and grouping

**Files:**
- Modify: `docs/plans/2026-03-15-comment-coverage-expansion.md`

**Step 1: Define scope**
- Cover all files under `app/common/connection-module/infra-redis/logic-module/message-module/persistence-module/protocol/src/main/java`.
- Skip tests and generated protobuf classes.

**Step 2: Define comment rules**
- Every top-level class/interface/record/enum gets a short Chinese purpose comment.
- Every method gets a short Chinese comment describing responsibility; overloads may share wording adapted to arguments.
- Add inline comments only for business-complex variables, transactional boundaries, fallback logic, queue/window semantics, or subtle validation/ordering.
- Do not comment trivial getters, obvious assignments, or language boilerplate beyond the required method-purpose line.

**Step 3: Split into independent edit domains**
- Domain A: `app` + `common` + `protocol`
- Domain B: `connection-module` + `infra-redis`
- Domain C: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat` + `logic-module/.../service` + `logic-module/.../mq`
- Domain D: `logic-module/.../http` + `logic-module/.../repository` + `message-module` + `persistence-module`

### Task 2: Annotate Domain A

**Files:**
- Modify: all production Java files in `app`, `common`, `protocol`

**Step 1: Add class-level purpose comments**
**Step 2: Add method-level responsibility comments**
**Step 3: Add inline comments for lifecycle, bean assembly, in-memory fallback, and protocol constant semantics where not obvious**

### Task 3: Annotate Domain B

**Files:**
- Modify: all production Java files in `connection-module`, `infra-redis`

**Step 1: Add class-level purpose comments**
**Step 2: Add method-level responsibility comments**
**Step 3: Add inline comments for pipeline ordering, session binding, transport fallback, best-effort fan-out, seq generation, Redis queue/cache semantics**

### Task 4: Annotate Domain C

**Files:**
- Modify: all production Java files in `logic-module` chat/service/mq packages

**Step 1: Add class-level purpose comments**
**Step 2: Add method-level responsibility comments**
**Step 3: Add inline comments for MQ publish order, ACK semantics, replay/re-enqueue flow, session resolution, delivery watermark, validation branches**

### Task 5: Annotate Domain D

**Files:**
- Modify: all production Java files in `logic-module` http/repository packages, `message-module`, `persistence-module`

**Step 1: Add class-level purpose comments**
**Step 2: Add method-level responsibility comments**
**Step 3: Add inline comments for repository SQL branching, HTTP parameter contracts, transaction/cache boundaries, message contract intent**

### Task 6: Consistency pass and verification

**Files:**
- Modify: any touched files that need normalization

**Step 1: Review all diffs**
- Remove comments that merely translate code.
- Ensure wording is concise and factual.
- Ensure class/method comments exist everywhere in scope.

**Step 2: Run compilation verification**
Run: `./gradlew :app:compileJava :common:compileJava :connection-module:compileJava :infra-redis:compileJava :logic-module:compileJava :message-module:compileJava :persistence-module:compileJava :protocol:compileJava`
Expected: `BUILD SUCCESSFUL`
