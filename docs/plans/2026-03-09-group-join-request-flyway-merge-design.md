# Group Join Request Flyway Repair And Main Merge Design

## Goal

修复 `group_join_requests_pending_pair_uniq` 被直接加入 `V1__phase1.sql` 的升级风险，保留现有仓储与测试行为，在 clean integration worktree 完成 fresh 验证后，再通过新的 clean merge worktree 将 `integration/phase1-main-sync` 本地合并到 `main`，最后精简技术总览文档。

## Constraints

- 聊天 TCP 强制 TLS 保持不变。
- `app/src/main/resources/application-local.yml` 必须保留。
- 不在根目录 dirty `main` worktree 上做 merge。
- 不删除现有 worktree。
- 最终修改都需要进入 git。

## Current Facts

- clean integration worktree 位于 `.worktrees/integration-phase1-main-sync`。
- 该 worktree 当前 `HEAD` 为 `c71dd34c03510a8ad0813dfc18dfdc52017fcc08`，相较上一份 handoff 中的 `346f3fb` 只多出交接文档提交。
- `group_join_requests_pending_pair_uniq` 目前位于 `persistence-module/src/main/resources/db/migration/V1__phase1.sql`。
- `JdbcGroupRepository` 通过数据库唯一约束将重复 pending 入群申请映射为 `pending group join request already exists`。
- `JdbcGroupRepositoryIntegrationTest` 已覆盖重复 pending 申请拒绝、审批成功取消 sibling pending 请求、已有 active membership 时取消 stale join request 等核心行为。

## Recommended Approach

### 1. Migration repair

- 从 `V1__phase1.sql` 删除 `group_join_requests_pending_pair_uniq` 的定义。
- 新增一个版本化 Flyway migration，只负责创建该部分唯一索引。
- 保持仓储层异常映射与业务行为不变。

### 2. Verification strategy

- 先补升级路径测试，证明“旧版 V1 已执行但尚无该唯一索引”的数据库在运行新 migration 后会创建索引。
- 保留并运行 `JdbcGroupRepositoryIntegrationTest`，证明业务行为未回退。
- 在 integration worktree fresh 运行：
  - `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.repository.JdbcGroupRepositoryIntegrationTest -g .gradle`
  - `./gradlew test -g .gradle`

### 3. Merge isolation

- 修复提交完成后，另建一个从 `main` 出发的 clean merge worktree。
- 仅在该 merge worktree 里本地 merge `integration/phase1-main-sync`。
- merge 后 fresh 运行：
  - `./gradlew test -g .gradle`

### 4. Documentation trim

- merge 验证通过后再更新 `docs/mochat-technical-documentation.md`。
- 去掉分支历史、临时背景和 runbook 式操作细节。
- 保留已确认技术事实、已验证边界、强制 TLS 现实和仍存在的主要 gap。
- 压缩重复的 gap / summary 段落，避免同一事实在多个章节重复出现。

## Alternatives Considered

### Option A: migration repair + upgrade-path test

这是推荐方案。它同时满足 Flyway 升级安全性和行为回归保护。

### Option B: migration repair only

可以完成修复，但缺少对旧库升级路径的直接证明，风险更高。

### Option C: application-level duplicate check only

不修改 migration，仅在仓储里先查再插。这会削弱数据库层并发保护，不符合当前目标，不采用。

## Planned Commit Shape

建议保持三段历史：

1. `integration/phase1-main-sync` 上单独提交 Flyway 修复与测试。
2. clean merge worktree 上生成 merge commit。
3. merge 后单独提交技术文档精简。

## Risks

- 若升级路径测试夹具构造不准确，可能只验证 fresh schema 而没有真正覆盖旧库升级。
- merge 后文档精简必须避免误删已确认事实，尤其是 TLS 强制现实与已纳入 git 的本地支持文件说明。
