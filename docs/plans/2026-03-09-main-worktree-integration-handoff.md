# Main And Worktree Integration Handoff Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> **For Claude:** Before any success claim, use superpowers:verification-before-completion.
> **For Claude:** If merge or test behavior is surprising, use superpowers:systematic-debugging before changing code.

**Goal:** Safely integrate the content of `/home/lystran/programming/java/practice/mo-chat/.worktrees/single-node-cloud-native-im-phase1-sync` back into the main baseline without overwriting unrelated dirty changes or regressing the archived OpenSpec state.

**Architecture:** Do not treat this as a normal branch merge. `main` and `fix/single-node-cloud-native-im-phase1-sync` currently point to the same commit, so the real task is to reconcile two dirty working trees. The safest approach is to create a fresh integration branch/worktree from the shared base commit, import the intended end state deliberately, preserve `main`-only cleanup where it is still valid, then verify on the integrated result.

**Tech Stack:** Git worktrees, Gradle 9, Java 25, Micronaut 4, Netty, protobuf, OpenSpec.

---

## Verified Baseline

- Fresh command already run in the phase1 sync worktree:
  - `./gradlew test -g .gradle`
  - Result: `BUILD SUCCESSFUL`
- Shared git base already confirmed:
  - `main` HEAD = `10b16674e80d1b2dabb59f1af25dce09e8e935f0`
  - `fix/single-node-cloud-native-im-phase1-sync` HEAD = `10b16674e80d1b2dabb59f1af25dce09e8e935f0`
- Therefore `git merge` alone will not bring worktree content back; there are no committed branch deltas yet.
- Path-level dirty-state audit already confirmed:
  - 97 overlapping dirty paths
  - 78 overlapping paths have identical file content
  - 15 overlapping file paths have different content
  - 4 paths are state conflicts (`main` still has active change files, worktree deleted them due to archive flow)
  - 3 `main`-only dirty paths
  - 26 worktree-only dirty paths

## Critical Integration Facts

### Fact 1: This is not a clean merge candidate yet

- `git diff --stat main...fix/single-node-cloud-native-im-phase1-sync` is empty because both refs point to the same commit.
- Both sides carry large uncommitted working tree state.
- Integration must happen in a third, clean worktree or branch created from the shared base.

### Fact 2: Most overlap is low-risk duplication

The majority of overlapping dirty files are byte-identical across `main` and the phase1 sync worktree. These are not the danger zone.

### Fact 3: A small set of files are the true conflict surface

These paths differ in content and must be reviewed deliberately:

- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/resources/application.yml`
- `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- `docs/mochat-technical-documentation.md`
- `docs/runbook.md`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
- `openspec/specs/login-session-and-user-bootstrap/spec.md`
- `openspec/specs/mq-persistence-pipeline-and-idempotency/spec.md`
- `openspec/specs/transport-and-connection-lifecycle/spec.md`

### Fact 4: OpenSpec state already diverged

- `main` still carries active change files under:
  - `openspec/changes/single-node-cloud-native-im-phase1/...`
- The phase1 sync worktree already:
  - synced relevant deltas into `openspec/specs/...`
  - archived the change into:
    - `openspec/changes/archive/2026-03-09-single-node-cloud-native-im-phase1/...`
- This means OpenSpec cannot be integrated by path-level blind copy. The target integrated state should reflect the archived worktree state, not the still-active change state in `main`.

### Fact 5: `main` has one meaningful code-side cleanup absent from the worktree

- `main`-only delete:
  - `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestEnvelope.java`
- The file still exists in the phase1 sync worktree, but a full-text search found no remaining references there.
- The integrated result should preserve the deletion unless fresh code review proves otherwise.

## Task 1: Create A Clean Integration Sandbox

**Files:**
- No code edits yet
- Create a new git worktree from the shared base commit

**Step 1: Confirm shared base is still unchanged**

Run:

```bash
git rev-parse main
git -C .worktrees/single-node-cloud-native-im-phase1-sync rev-parse HEAD
```

Expected: both still equal `10b16674e80d1b2dabb59f1af25dce09e8e935f0`.

**Step 2: Create a fresh integration branch/worktree**

Suggested shape:

```bash
git worktree add .worktrees/integration-phase1-main-sync -b integration/phase1-main-sync 10b16674e80d1b2dabb59f1af25dce09e8e935f0
```

Expected: a clean sandbox with no pre-existing dirty files.

**Step 3: Verify the integration sandbox is clean**

Run:

```bash
git -C .worktrees/integration-phase1-main-sync status --short --branch
```

Expected: no dirty files.

## Task 2: Import Low-Risk Worktree State First

**Files:**
- Import worktree-only files from `/home/lystran/programming/java/practice/mo-chat/.worktrees/single-node-cloud-native-im-phase1-sync`

**Step 1: Bring over worktree-only non-OpenSpec implementation files**

Review and import at least:

- `connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/http/HistoryControllerTest.java`
- `docs/plans/2026-03-09-phase1-consistency-sync.md`
- `docs/plans/2026-03-09-phase1-native-verification-followup.md`

**Step 2: Bring over worktree-only OpenSpec archive directory**

Target expected state includes:

- `openspec/changes/archive/2026-03-09-single-node-cloud-native-im-phase1/...`

**Step 3: Do not reintroduce the active change directory**

Do not import:

- `openspec/changes/single-node-cloud-native-im-phase1/...`

The integrated target should represent the archived outcome, not a still-active change.

## Task 3: Resolve The 15 True Conflict Files Deliberately

**Files:**
- The 15 file paths listed in “Fact 3”

**Step 1: Prefer the phase1 sync worktree for the following categories**

Use the phase1 sync worktree as source of truth for:

- TLS mandatory behavior and self-signed defaults
- explicit certificate precedence coverage
- session invalid error response semantics
- MQ publish failure mapping to `MQ_PUBLISH_FAILED`
- synced OpenSpec main spec files
- native verification doc updates that no longer hardcode build duration

This mainly points to taking the worktree version of:

- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/resources/application.yml`
- `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`
- `connection-module/src/main/java/com/github/lystran/mochat/connection/SessionBindingHandler.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/ChatChannelInitializerTest.java`
- `connection-module/src/test/java/com/github/lystran/mochat/connection/SessionBindingHandlerTest.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumerErrorResponseTest.java`
- `logic-module/src/test/java/com/github/lystran/mochat/logic/chat/MessageIngestServiceTest.java`
- `openspec/specs/login-session-and-user-bootstrap/spec.md`
- `openspec/specs/mq-persistence-pipeline-and-idempotency/spec.md`
- `openspec/specs/transport-and-connection-lifecycle/spec.md`

**Step 2: Re-check the document conflicts before overwriting**

Before taking the worktree versions of:

- `docs/mochat-technical-documentation.md`
- `docs/runbook.md`

run a final diff against current `main` and ensure no newer `main`-only documentation edits need to be preserved.

**Step 3: Preserve the `main`-only deletion**

Keep deleted:

- `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestEnvelope.java`

Do not restore it from the worktree unless a fresh search shows it is required.

## Task 4: Remove The Stale Active Change State

**Files:**
- Remove active change directory from the integration sandbox

**Step 1: Ensure main specs already contain synced delta behavior**

Confirm the integration sandbox includes the synced versions of:

- `openspec/specs/login-session-and-user-bootstrap/spec.md`
- `openspec/specs/mq-persistence-pipeline-and-idempotency/spec.md`
- `openspec/specs/transport-and-connection-lifecycle/spec.md`

**Step 2: Remove the stale active change directory**

Target removed state:

- `openspec/changes/single-node-cloud-native-im-phase1/`

**Step 3: Keep the archive result**

Target kept state:

- `openspec/changes/archive/2026-03-09-single-node-cloud-native-im-phase1/`

## Task 5: Reconcile Remaining `main`-Only Metadata

**Files:**
- `docs/plans/2026-03-09-phase1-verification-followup-handoff.md`
- `mise.toml`

**Step 1: Keep `main`-only handoff history unless clearly obsolete**

Default action:
- preserve `docs/plans/2026-03-09-phase1-verification-followup-handoff.md`

**Step 2: Preserve local tool metadata unless it is proven accidental**

Default action:
- preserve `mise.toml`

If either file is user-local and should stay out of the final integration branch, confirm before dropping it.

## Task 6: Verify The Integrated Result

**Files:**
- Whole repo in the clean integration sandbox

**Step 1: Confirm no stale active change remains**

Run:

```bash
fd single-node-cloud-native-im-phase1 openspec/changes openspec/specs
```

Expected:
- archive path exists
- no active `openspec/changes/single-node-cloud-native-im-phase1/` remains

**Step 2: Verify the key behavioral suites**

Run:

```bash
./gradlew :app:test --tests com.github.lystran.mochat.runtime.MochatRuntimeFactoryTest -g .gradle
./gradlew :connection-module:test --tests com.github.lystran.mochat.connection.SessionBindingHandlerTest --tests com.github.lystran.mochat.connection.ChatChannelInitializerTest -g .gradle
./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.chat.InboundMessageConsumerErrorResponseTest --tests com.github.lystran.mochat.logic.chat.MessageIngestServiceTest -g .gradle
```

Expected: all pass.

**Step 3: Verify the whole repository**

Run:

```bash
./gradlew test -g .gradle
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Review the final git status**

Run:

```bash
git status --short
```

Expected:
- only the intended integrated changes are present
- no accidental resurrection of `MessageIngestEnvelope.java`
- no duplicate active/archive OpenSpec state

## Task 7: Commit The Integrated State

**Files:**
- All intended integrated files in the clean integration sandbox

**Step 1: Commit once the integrated result is verified**

Suggested commit message:

```bash
git add .
git commit -m "feat: integrate phase1 sync worktree into main baseline"
```

**Step 2: Only after commit, consider merging back to `main`**

At that point it becomes a normal merge/cherry-pick decision instead of a working-tree reconciliation problem.
