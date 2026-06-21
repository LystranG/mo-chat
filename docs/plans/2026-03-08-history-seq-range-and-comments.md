# History Seq Range and Comment Pass Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `startSeq/endSeq` history range queries on top of the existing `seq` cursor API, clamp both history modes to 50 items, and synchronize the related specs/docs/comments without broad refactoring.

**Architecture:** Keep the existing `HistoryController -> HistoryService -> HistoryRepository -> JdbcHistoryRepository` pipeline and extend it with the smallest possible query-mode branching. Reuse current DTOs and access control, centralize limit normalization in service code, and only touch spec/doc files that currently disagree with the real `seq`-based model.

**Tech Stack:** Micronaut 4, Java 25, JDBC, PostgreSQL, JUnit 5, Mockito, Testcontainers, OpenSpec Markdown docs

---

### Task 1: Lock the controller and service contract with failing tests

**Files:**
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
- Create: `logic-module/src/test/java/com/github/lystran/mochat/logic/service/HistoryServiceTest.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`

**Step 1: Write the failing controller tests**

Extend `HistoryControllerTest` with cases that describe the approved API contract:

```java
assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
verify(historyService, never()).query(any());
```

Cover at least:

- `cursorSeq` with `startSeq/endSeq` together -> `400`
- only `startSeq` or only `endSeq` -> `400`
- `startSeq > endSeq` -> `400`
- range mode forwards normalized `limit=50` when omitted or oversized

**Step 2: Write the failing service tests**

Create `HistoryServiceTest.java` with a mocked `HistoryRepository` and assertions like:

```java
verify(historyRepository).findHistory(queryCaptor.capture());
assertEquals(50, queryCaptor.getValue().limit());
```

Cover at least:

- `limit <= 0` -> normalized to `50`
- `limit > 50` -> clamped to `50`
- cursor mode and range mode both use the same normalization rules

**Step 3: Run tests to verify they fail**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest --tests com.github.lystran.mochat.logic.service.HistoryServiceTest`

Expected: FAIL because the current controller/service only support `cursorSeq` mode and do not expose the normalized query contract.

**Step 4: Write minimal implementation**

- In `HistoryController`, add optional `startSeq` / `endSeq` query parameters.
- Add a small Chinese comment above the parameter validation block explaining the two supported history modes and why they are mutually exclusive.
- Introduce a small query object inside `HistoryService` or a nearby owner type, for example:

```java
public record HistoryQuery(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {}
```

- Normalize `limit` in `HistoryService` only:
  - `<= 0` -> `50`
  - `> 50` -> `50`

**Step 5: Run tests to verify they pass**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest --tests com.github.lystran.mochat.logic.service.HistoryServiceTest`

Expected: PASS.

**Step 6: Checkpoint**

No commit in this session unless the user explicitly asks for one.

### Task 2: Add repository range-query support with TDD

**Files:**
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/HistoryRepository.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java`
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepositoryTest.java`
- Create: `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepositoryIntegrationTest.java`

**Step 1: Write the failing repository unit test**

Extend `JdbcHistoryRepositoryTest` so it asserts the new range SQL shape:

```java
assertTrue(sql.contains("seq BETWEEN ? AND ?"));
assertTrue(sql.contains("ORDER BY seq DESC"));
```

**Step 2: Write the failing repository integration test**

Create `JdbcHistoryRepositoryIntegrationTest.java` using the existing logic-module PostgreSQL test stack.

Insert a conversation and a window of messages, then assert:

```java
List<HistoryMessage> messages = repository.findHistory(query);
assertEquals(List.of(30L, 29L, 28L), messages.stream().map(HistoryMessage::seq).toList());
```

Cover at least:

- range `[20, 30]` returns only messages inside that closed interval
- range with more than 50 matching rows returns the newest 50 rows in that range

**Step 3: Run tests to verify they fail**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryIntegrationTest`

Expected: FAIL because the repository interface and JDBC implementation only support `cursorSeq` mode.

**Step 4: Write minimal implementation**

- Change `HistoryRepository` to accept the unified query object instead of loose cursor parameters.
- Keep the old cursor SQL.
- Add a second SQL block for range mode:

```sql
SELECT seq, msg_id, server_ts_ms, payload_base64
FROM messages
WHERE conversation_id = ? AND seq BETWEEN ? AND ?
ORDER BY seq DESC
LIMIT ?
```

- Add a short Chinese comment explaining why the range query also uses `DESC + LIMIT` (the user explicitly wants the newest 50 rows inside the requested range).

**Step 5: Run tests to verify they pass**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryIntegrationTest`

Expected: PASS.

**Step 6: Checkpoint**

If you discover any existing caller that truly depends on `limit > 50` or on the old parameter behavior, stop here and ask the user before changing semantics further.

### Task 3: Add a focused Chinese comment pass to touched history code

**Files:**
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/HistoryService.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/repository/JdbcHistoryRepository.java`

**Step 1: Review the touched code for non-obvious logic**

Identify only the places that benefit from brief Chinese comments:

- mode exclusivity and parameter validation
- unified limit normalization
- range query ordering and limiting

**Step 2: Add the minimal comments**

Use short comments such as:

```java
// /history 只允许“游标翻页”或“seq 范围查询”二选一，避免语义冲突。
// 两种模式统一裁剪到 50，避免不同调用路径出现窗口大小漂移。
// 范围查询按 seq 倒序取最新 50 条，符合“区间内最新消息窗口”的约定。
```

Do not add comments to obvious DTOs, trivial assignments, or one-line return statements.

**Step 3: Run the focused tests again**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest --tests com.github.lystran.mochat.logic.service.HistoryServiceTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryIntegrationTest`

Expected: PASS.

**Step 4: Checkpoint**

No commit in this session unless the user explicitly asks for one.

### Task 4: Update OpenSpec, task tracking, and technical documentation

**Files:**
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/specs/data-model-and-history-pagination/spec.md`
- Modify: `openspec/specs/data-model-and-history-pagination/spec.md`
- Modify: `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- Modify: `docs/mochat-technical-documentation.md`
- Optional: `docs/runbook.md`

**Step 1: Update the change spec to the approved model**

Edit the change spec so it states:

- history pagination is `seq`-based
- `/history` also supports `startSeq/endSeq`
- both modes clamp to at most `50`

**Step 2: Sync the main spec copy**

Apply the same factual changes to `openspec/specs/data-model-and-history-pagination/spec.md` so main specs do not drift from the active change.

**Step 3: Update the task file**

Adjust task wording for `3.5` and `7.5` so they no longer claim `msgId` pagination if the codebase has now standardized on `seq`.

**Step 4: Update technical documentation**

Revise the history section in `docs/mochat-technical-documentation.md` so it explains:

- cursor mode is `seq`
- range mode is `[startSeq, endSeq]`
- both modes return at most 50 records

If `docs/runbook.md` has a natural place, add one minimal example request for the new range mode; otherwise skip it.

**Step 5: Verify doc/spec consistency**

Run: `rg -n "msgId \+ limit|cursorSeq|startSeq|endSeq|history" openspec docs logic-module/src/main/java/com/github/lystran/mochat/logic/{http,service,repository}`

Expected: no stale statements claiming history still paginates by `msgId + limit` in the touched docs/specs.

### Task 5: Run final verification on the changed surface

**Files:**
- No new files

**Step 1: Run focused verification**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.http.HistoryControllerTest --tests com.github.lystran.mochat.logic.service.HistoryServiceTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryTest --tests com.github.lystran.mochat.logic.repository.JdbcHistoryRepositoryIntegrationTest`

Expected: PASS.

**Step 2: Run broader module verification**

Run: `./gradlew :logic-module:test --rerun-tasks`

Expected: BUILD SUCCESSFUL.

**Step 3: Run full repository verification if the module stays green**

Run: `./gradlew test --rerun-tasks`

Expected: BUILD SUCCESSFUL.

**Step 4: Final checkpoint**

Stop and ask the user if any of the following happens:

- `limit > 50` breaks a real existing caller
- history access control or response ordering needs to change beyond the approved design
- supporting range mode unexpectedly requires broad refactoring outside the history stack

Otherwise, report what changed, which tests ran, and what remains unfinished.
