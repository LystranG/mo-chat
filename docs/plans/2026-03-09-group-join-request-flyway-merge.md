# Group Join Request Flyway Repair And Main Merge Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 `group_join_requests_pending_pair_uniq` 从基线 `V1__phase1.sql` 迁移为安全的版本化 Flyway migration，保留当前仓储行为，完成 integration worktree fresh 验证、本地 clean merge，以及技术文档精简。

**Architecture:** 先在 `integration/phase1-main-sync` 上用 TDD 修复 Flyway migration 策略并补升级路径验证，再运行仓储级和整仓 fresh 测试。随后创建新的 clean merge worktree 从 `main` 本地合并 integration 分支，并在 merged 结果上再次 fresh 验证。最后仅在 merged 结果上收敛技术总览文档，使其只陈述当前已确认技术事实。

**Tech Stack:** Java 25, Gradle Kotlin DSL, Flyway, PostgreSQL, JUnit 5, Testcontainers, Git worktree

---

### Task 1: 补升级路径失败测试

**Files:**
- Modify: `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcGroupRepositoryIntegrationTest.java`
- Create: `persistence-module/src/test/resources/db/migration/legacy/V1__phase1.sql`

**Step 1: Write the failing test**

在 `JdbcGroupRepositoryIntegrationTest` 新增一个升级路径测试：

```java
@Test
void migrationAddsPendingJoinRequestUniqueIndexForLegacyV1Schema() throws SQLException {
    migrateLegacySchemaWithoutPendingJoinRequestUniqueIndex();

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .load()
        .migrate();

    assertTrue(indexExists("group_join_requests_pending_pair_uniq"));
}
```

其中 legacy `V1__phase1.sql` 要模拟“旧库已执行 V1，但没有 `group_join_requests_pending_pair_uniq`”。

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest.migrationAddsPendingJoinRequestUniqueIndexForLegacyV1Schema -g .gradle
```

Expected: FAIL，因为当前主 migration 体系下没有新的版本化 migration 去补建该索引。

**Step 3: Write minimal implementation**

- 为测试补充 legacy schema 迁移辅助方法。
- 增加索引存在性查询辅助方法，例如查询 `pg_indexes`。
- 保持现有其他测试初始化方式不变。

**Step 4: Run test to verify it passes**

Run 同上命令。

Expected: PASS，证明升级路径可补齐唯一索引。

**Step 5: Commit**

```bash
git add logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcGroupRepositoryIntegrationTest.java persistence-module/src/test/resources/db/migration/legacy/V1__phase1.sql
git commit -m "test: cover legacy flyway upgrade for group join index"
```

### Task 2: 将索引迁移出 V1 并保留行为

**Files:**
- Modify: `persistence-module/src/main/resources/db/migration/V1__phase1.sql`
- Create: `persistence-module/src/main/resources/db/migration/V2__group_join_requests_pending_pair_uniq.sql`
- Test: `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcGroupRepositoryIntegrationTest.java`

**Step 1: Write the failing test**

复用 Task 1 的失败测试，并确认现有重复 pending 请求行为测试仍然存在：

```java
@Test
void duplicatePendingJoinRequestIsRejected() {
    repository.createJoinRequest(22L, group.groupId(), "opaque-sign");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> repository.createJoinRequest(22L, group.groupId(), "opaque-sign-2")
    );

    assertEquals("pending group join request already exists", exception.getMessage());
}
```

**Step 2: Run tests to verify they fail/pass in the right place**

Run:

```bash
./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest.migrationAddsPendingJoinRequestUniqueIndexForLegacyV1Schema --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest.duplicatePendingJoinRequestIsRejected -g .gradle
```

Expected:
- 升级路径测试当前 FAIL
- 重复 pending 请求测试仍 PASS

**Step 3: Write minimal implementation**

- 从 `V1__phase1.sql` 删除 `group_join_requests_pending_pair_uniq`。
- 新增 `V2__group_join_requests_pending_pair_uniq.sql`：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS group_join_requests_pending_pair_uniq
  ON group_join_requests (group_id, from_uid)
  WHERE status = 'pending';
```

- 不修改 `JdbcGroupRepository` 的业务逻辑和异常映射。

**Step 4: Run tests to verify they pass**

Run 同上命令。

Expected: 两个测试都 PASS。

**Step 5: Commit**

```bash
git add persistence-module/src/main/resources/db/migration/V1__phase1.sql persistence-module/src/main/resources/db/migration/V2__group_join_requests_pending_pair_uniq.sql logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcGroupRepositoryIntegrationTest.java persistence-module/src/test/resources/db/migration/legacy/V1__phase1.sql
git commit -m "fix: version group join request pending index migration"
```

### Task 3: 在 integration worktree fresh 验证

**Files:**
- Test: `logic-module/src/test/java/com/github/lystran/mochat/logic/repository/JdbcGroupRepositoryIntegrationTest.java`

**Step 1: Run repository integration verification**

Run:

```bash
./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest -g .gradle
```

Expected: PASS。

**Step 2: Run full fresh verification**

Run:

```bash
./gradlew test -g .gradle
```

Expected: PASS。

**Step 3: Review results**

- 记录 exit code、失败数和关键信息。
- 若失败，先修复再继续，不能带着未验证状态进入 merge。

**Step 4: Commit**

```bash
git status --short
```

Expected: 除必要修改外无额外脏改动。

### Task 4: 创建 clean merge worktree 并执行本地 merge

**Files:**
- N/A（git worktree / merge 操作）

**Step 1: Verify worktree location is safe**

Run:

```bash
git check-ignore -q .worktrees
```

Expected: exit code `0`，说明 `.worktrees` 已被忽略。

**Step 2: Create the merge worktree**

Run:

```bash
git worktree add .worktrees/main-merge-phase1 -b merge/main-phase1 main
```

Expected: 新 worktree 基于 `main` 创建成功。

**Step 3: Merge integration branch**

Run:

```bash
git -C .worktrees/main-merge-phase1 merge --no-ff integration/phase1-main-sync
```

Expected: merge 成功；若冲突，逐个解决并保留 TLS 强制与 `application-local.yml`。

**Step 4: Commit**

```bash
git -C .worktrees/main-merge-phase1 status --short --branch
```

Expected: merge 完成后的状态清晰可继续验证。

### Task 5: 在 merged 结果上 fresh 验证

**Files:**
- N/A（测试执行）

**Step 1: Run merged full verification**

Run:

```bash
cd .worktrees/main-merge-phase1
./gradlew test -g .gradle
```

Expected: PASS。

**Step 2: Review output**

- 确认 `BUILD SUCCESSFUL`
- 记录是否存在非阻塞警告，但不得把 warning 说成 failure

**Step 3: Commit**

无需新 commit；进入文档精简前先确认 merge 结果可用。

### Task 6: 精简技术总览文档

**Files:**
- Modify: `docs/mochat-technical-documentation.md`
- Reference: `docs/runbook.md`

**Step 1: Write the failing test**

此任务属于文档收敛，不做自动化测试，改为先定义验收检查：

- 文档不再叙述分支历史与临时背景
- 保留强制 TLS 现实
- 不删除 `application-local.yml` 的事实存在
- 不把 runbook 式操作步骤堆入技术总览
- 压缩重复的 gap / summary 段落

**Step 2: Run verification to verify current doc fails the checklist**

人工检查当前文档，确认至少存在以下问题：

- 多处出现“当前分支 / 当前状态 / 本地验证 / runbook 式命令”重复叙述
- 尾部总结与前文 gap 重复

**Step 3: Write minimal implementation**

- 将文档改写为“当前系统技术事实总览”
- 删去带明显阶段性/历史性语气的段落
- 将运行步骤性细节压缩为简短验证事实
- 合并重复的 gap / summary

**Step 4: Run verification to verify it passes**

人工对照 Step 1 的检查项逐项确认；必要时用 `rg` 检查是否还残留显著的历史措辞或重复段落。

**Step 5: Commit**

```bash
git -C .worktrees/main-merge-phase1 add docs/mochat-technical-documentation.md
git -C .worktrees/main-merge-phase1 commit -m "doc: trim technical overview"
```

### Task 7: 完成前最终核对

**Files:**
- Modify: `docs/mochat-technical-documentation.md`
- Reference: `app/src/main/resources/application-local.yml`

**Step 1: Run final verification commands**

Run:

```bash
git -C .worktrees/main-merge-phase1 status --short --branch
./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest -g .gradle
./gradlew test -g .gradle
git -C .worktrees/main-merge-phase1 status --short --branch
```

Expected:
- integration worktree 上仓储测试和全量测试均 PASS
- merge worktree 上工作树状态清晰

**Step 2: Check hard requirements**

- TLS 仍为强制开启语义
- `app/src/main/resources/application-local.yml` 仍在 git 中
- 没有在根目录 dirty `main` worktree 上执行 merge
- 没有 force push
- 没有删除现有 worktree

**Step 3: Commit**

此步只做状态核对，不额外提交。
