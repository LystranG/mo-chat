# Phase 1 Verification Follow-up Handoff Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> **For Claude:** Before each fix area, use superpowers:systematic-debugging.
> **For Claude:** For every behavior change, use superpowers:test-driven-development.
> **For Claude:** Before any success claim, use superpowers:verification-before-completion.

**Goal:** Resolve the critical verification gaps found for `single-node-cloud-native-im-phase1`, get the workspace back to a compilable state, and produce fresh verification evidence for the fixed behavior.

**Architecture:** Keep the existing modular-monolith structure intact. Fix the current failures at their source instead of papering over symptoms: compile blockers first, then transport/auth error semantics, then docs/tests drift. Preserve the existing contracts between `connection-module`, `logic-module`, `message-module`, and `persistence-module`.

**Tech Stack:** Gradle 9, Java 25, Micronaut 4, Netty, protobuf, PostgreSQL/Flyway, Redis/Lettuce, RocketMQ, GraalVM native build tools.

---

## Verified Baseline From Previous Session

- Fresh command already run: `./gradlew test`
- Actual result: build failed in `:connection-module:compileJava`
- First concrete failure:
  - `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java:19`
  - `Sharable is not a repeatable annotation interface`
- Additional drift already observed from source review:
  - TLS is specified as mandatory in spec/design, but runtime/config currently allow TCP without TLS.
  - TCP invalid-session path currently drops messages silently instead of emitting `ERROR_RESPONSE`.
  - MQ publish failure path is not consistently mapped to `MQ_PUBLISH_FAILED`.
  - Baseline DDL doc `docs/ddl/phase1.sql` diverges from Flyway migration `persistence-module/src/main/resources/db/migration/V1__phase1.sql`.
  - `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java` references `buildMandatorySslContext(...)`, but the method is not present in `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`.

## Manual Preconditions To Analyze Early

The next session must explicitly check whether any of these require user input before code changes continue:

1. Dirty worktree coordination:
   - The repo already had many modified/untracked files during verification.
   - If intended fixes conflict with unrelated user changes, stop and ask before overwriting behavior.

2. TLS rollout expectation:
   - Spec/design say chat TCP must enforce TLS 1.3.
   - Confirm whether local developer default should become self-signed TLS-on, or whether startup should fail unless TLS is enabled explicitly.

3. Native image environment:
   - Final verification should include `:app:nativeCompile`.
   - If `native-image` is unavailable locally, the session must report that as an environment prerequisite instead of claiming success.

4. DDL doc policy:
   - Confirm whether `docs/ddl/phase1.sql` is expected to exactly mirror Flyway schema, or is allowed to be a looser design-time document.
   - If product expectation is unclear, ask before making broad documentation edits.

### Task 1: Reproduce And Clear The Compile Baseline

**Files:**
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- Verify: `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`

**Step 1: Reproduce the current baseline failure**

Run: `./gradlew test`
Expected: FAIL at `:connection-module:compileJava` with duplicate `@ChannelHandler.Sharable`.

**Step 2: Inspect the concrete source of the failure**

Check:
- `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`

Expected findings:
- duplicate `import io.netty.channel.ChannelHandler;`
- duplicate `@ChannelHandler.Sharable`

**Step 3: Write or update a focused compile/behavior test if needed**

If no test already protects `SessionBindingHandler` compilation/usage, add the smallest relevant test under:
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`

**Step 4: Apply the minimal fix**

Fix only the duplicate annotation/import problem first.

**Step 5: Re-run a narrow verification**

Run: `./gradlew :connection-module:compileJava :connection-module:test --tests com.github.lystran.mochat.connection.SessionBindingHandlerTest`
Expected: PASS for the touched module/tests.

**Step 6: Re-run the broader baseline**

Run: `./gradlew test`
Expected: Either next failure surfaces, or the build moves further than the previous compile stop.

### Task 2: Resolve Runtime Factory And TLS Test Drift

**Files:**
- Modify: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`
- Check: `openspec/changes/single-node-cloud-native-im-phase1/design.md`
- Check: `openspec/changes/single-node-cloud-native-im-phase1/specs/transport-and-connection-lifecycle/spec.md`

**Step 1: Re-read the requirement and design**

Read:
- `openspec/changes/single-node-cloud-native-im-phase1/design.md`
- `openspec/changes/single-node-cloud-native-im-phase1/specs/transport-and-connection-lifecycle/spec.md`

Expected conclusion:
- chat TCP must enforce TLS 1.3
- default operational path should support self-signed TLS

**Step 2: Inspect runtime and tests for drift**

Read:
- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/resources/application.yml`
- `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`

Expected findings:
- runtime currently allows `sslContext.orElse(null)`
- default config currently sets `mochat.tls.enabled=false`
- test references a method that does not exist

**Step 3: Write failing tests first**

Add or update tests that prove:
- chat TCP cannot start in non-TLS mode if spec says TLS is mandatory
- certificate/private-key pairing rules are enforced consistently
- self-signed mode works when configured

Suggested command:
- `./gradlew :app:test --tests com.github.lystran.mochat.runtime.MochatRuntimeFactoryTest`

Expected before fix: FAIL on the mismatched implementation/spec behavior.

**Step 4: Implement the minimal spec-aligned runtime change**

Align runtime factory and default config with the agreed TLS policy.

**Step 5: Verify locally**

Run:
- `./gradlew :app:test --tests com.github.lystran.mochat.runtime.MochatRuntimeFactoryTest`

Expected: PASS.

### Task 3: Make Invalid TCP Sessions Return Explicit Error Responses

**Files:**
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Modify or add tests in:
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerLifecycleTest.java`

**Step 1: Write failing tests for invalid session handling**

Cover at least:
- invalid `sessionId` on `PRIVATE_MESSAGE`
- invalid `sessionId` on `GROUP_MESSAGE`
- invalid `sessionId` on `CLIENT_RECEIVE_ACK` if protocol requires an auth failure response there too

Expected behavior:
- emit `ERROR_RESPONSE`
- use `ErrorCode.SESSION_INVALID` or `SESSION_EXPIRED`
- do not publish to RocketMQ

**Step 2: Run the focused tests and confirm RED**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest`
Expected: FAIL because current code silently returns.

**Step 3: Implement the minimal fix**

Update `InboundMessageConsumer` so invalid session paths emit the correct outbound error event instead of silently dropping the frame.

**Step 4: Verify GREEN**

Run:
- `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest`

Expected: PASS.

### Task 4: Map MQ Publish Failures To `MQ_PUBLISH_FAILED`

**Files:**
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- Check: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageRejectException.java`
- Verify tests:
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`

**Step 1: Reproduce the intended failing behavior from tests**

Run:
- `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest`

Expected: if compile is fixed, these tests should expose or protect the MQ failure contract.

**Step 2: Trace the failure path**

Inspect:
- `MessageIngestService.ingest(...)`
- `InboundMessageConsumer.consumePrivate(...)`
- `InboundMessageConsumer.consumeGroup(...)`

Expected finding:
- publish failure currently becomes `IllegalStateException`, not a structured rejection path

**Step 3: Make the smallest fix**

Convert ordered publish false/throw cases into `MessageRejectException(ErrorCode.MQ_PUBLISH_FAILED, ...)`.

**Step 4: Verify**

Run:
- `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest`

Expected: PASS with no `SEND_ACK` emitted on failed publish.

### Task 5: Return Validation Errors For Malformed Bodies

**Files:**
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Verify tests:
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
  - add a focused malformed-body test if needed

**Step 1: Identify the exact malformed-body cases the current implementation drops silently**

Check:
- invalid protobuf bytes for private/group requests
- any malformed body path that currently catches `InvalidProtocolBufferException` and returns

**Step 2: Write failing tests**

Expected behavior:
- `ERROR_RESPONSE`
- `ErrorCode.INVALID_BODY`
- no publish side effects

**Step 3: Implement the minimal change**

Keep parsing logic simple; just stop swallowing malformed body errors silently.

**Step 4: Verify**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest`
Expected: PASS.

### Task 6: Close The Spec/Doc/Test Coverage Drift

**Files:**
- Modify: `docs/ddl/phase1.sql`
- Modify or add:
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
  - HTTP integration tests if needed for range-mode coverage
- Check: `persistence-module/src/main/resources/db/migration/V1__phase1.sql`

**Step 1: Diff the baseline DDL doc against the migration**

Compare:
- `docs/ddl/phase1.sql`
- `persistence-module/src/main/resources/db/migration/V1__phase1.sql`

Expected findings:
- `docs/ddl/phase1.sql` lacks `conversations`
- history/index shape does not match the real migration

**Step 2: Decide whether user confirmation is needed**

If the repository treats `docs/ddl/phase1.sql` as an exact artifact, update it.
If not clear, stop and ask the user.

**Step 3: Add missing history scenario tests**

Cover:
- `startSeq/endSeq` range query
- `cursorSeq` with `startSeq/endSeq` mutual exclusion
- `limit > 50` normalization

**Step 4: Verify**

Run:
- `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest`

Expected: PASS.

### Task 7: Run Final Verification Evidence

**Files:**
- No code changes required unless failures surface

**Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: PASS.

**Step 2: Run native-image verification**

Run: `./gradlew :app:nativeCompile`
Expected:
- PASS if `native-image` is installed
- otherwise explicit report that environment prerequisite is missing

**Step 3: Re-check the original verification dimensions**

Re-open and re-check:
- `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- `openspec/changes/single-node-cloud-native-im-phase1/design.md`
- `openspec/changes/single-node-cloud-native-im-phase1/specs/**/*.md`

Expected:
- no remaining critical verification gaps
- warnings only if explicitly accepted by the user

