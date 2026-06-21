# Codebase Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create runtime-boundary codebase memory under `docs/codebase` and add root `AGENTS.md` rules requiring agents to read and maintain it.

**Architecture:** Subagents analyze independent runtime boundaries and return structured findings without writing files. The controller writes all documentation in one pass to avoid file conflicts, then verifies the result against the accepted design.

**Tech Stack:** Markdown documentation, Gradle multi-module Java/Micronaut project, shell verification via `rtk`.

**Execution Status:** Historical plan. Implementation was committed in `f58789b`; review follow-up fixes are recorded in later commits. Do not execute this file as a live checklist.

---

## File Structure

- Create: `docs/codebase/README.md`
  - Top-level index, reading order, ownership map, maintenance rules.
- Create: `docs/codebase/access-gateway/README.md`
  - TCP gateway and online route ownership memory.
- Create: `docs/codebase/api-service/README.md`
  - HTTP/session/social/history read-side memory.
- Create: `docs/codebase/message-service/README.md`
  - Message command, ACK, online delivery, offline fallback memory.
- Create: `docs/codebase/persistence-service/README.md`
  - MQ consume and durable truth memory.
- Create: `docs/codebase/shared/README.md`
  - Cross-service contract and shared module memory.
- Create: `docs/codebase/deployment/README.md`
  - Kubernetes, Docker, runtime topology, configuration memory.
- Create: `docs/codebase/compatibility-app/README.md`
  - Legacy compatibility shell memory.
- Create: `AGENTS.md`
  - Repository-level agent instructions for Chinese replies, RTK usage, codebase memory reading, codebase memory maintenance, and MultiAgent behavior.
- Read-only references:
  - `docs/superpowers/specs/2026-06-20-codebase-memory-design.md`
  - `README.md`
  - `docs/mochat-technical-documentation.md`
  - `docs/runbook.md`
  - `docs/architecture/decompose-im-into-core-services-skeleton.md`
  - `openspec/specs/*`
  - Relevant source and test paths per runtime boundary.

## Task 1: Parallel Runtime Boundary Analysis

**Files:**
- Read: `README.md`
- Read: `docs/mochat-technical-documentation.md`
- Read: `docs/runbook.md`
- Read: `docs/architecture/decompose-im-into-core-services-skeleton.md`
- Read: runtime-specific source/test files
- Do not modify files in this task.

- [x] **Step 1: Dispatch independent analysis subagents**

Dispatch subagents for these independent read-only tasks:

```text
1. access-gateway analysis:
   Read access-gateway-app, connection-module, common/session, common/directory, protocol internal gateway proto, access gateway tests.
   Return responsibilities, non-responsibilities, key source paths, key data/message flows, configuration, test entrypoints, and maintenance notes.

2. api-service analysis:
   Read api-service-app, logic-module/http, logic-module/service, logic-module/repository API/read-side parts, common/session, protocol internal api proto, api service tests.
   Return responsibilities, non-responsibilities, key source paths, key data/message flows, configuration, test entrypoints, and maintenance notes.

3. message-service analysis:
   Read message-service-app, logic-module/chat, message-module, infra-redis parts used for idempotency/offline/route coordination, protocol internal message proto, message service tests.
   Return responsibilities, non-responsibilities, key source paths, key data/message flows, configuration, test entrypoints, and maintenance notes.

4. persistence-service analysis:
   Read persistence-service-app, persistence-module, persistence migrations, persistence tests, message-module persistence contract.
   Return responsibilities, non-responsibilities, key source paths, key data/message flows, configuration, test entrypoints, and maintenance notes.

5. shared/deployment/compatibility analysis:
   Read common, protocol, service-runtime, infra-redis, deploy/kubernetes, Dockerfiles, application.yml files, docker-compose.yml, app.
   Return shared contract facts, deployment facts, compatibility shell facts, source paths, configuration, test entrypoints, and maintenance notes.
```

Expected: all subagents return structured analysis; no files modified.

- [x] **Step 2: Wait for all subagents using long timeout**

Use a long wait timeout (`timeout_ms=1000000` when the multi-agent API supports it). Do not interrupt unless the subagent clearly runs off-task, conflicts, introduces safety risk, or the user asks.

Expected: all analyses are available for consolidation.

## Task 2: Create `docs/codebase` Memory

**Files:**
- Create: `docs/codebase/README.md`
- Create: `docs/codebase/access-gateway/README.md`
- Create: `docs/codebase/api-service/README.md`
- Create: `docs/codebase/message-service/README.md`
- Create: `docs/codebase/persistence-service/README.md`
- Create: `docs/codebase/shared/README.md`
- Create: `docs/codebase/deployment/README.md`
- Create: `docs/codebase/compatibility-app/README.md`

- [x] **Step 1: Create directories**

Run:

```bash
rtk mkdir -p docs/codebase/access-gateway docs/codebase/api-service docs/codebase/message-service docs/codebase/persistence-service docs/codebase/shared docs/codebase/deployment docs/codebase/compatibility-app
```

Expected: directories exist.

- [x] **Step 2: Write the top-level README**

Create `docs/codebase/README.md` with:

```markdown
# MoChat Codebase Memory

MoChat 当前默认运行形态是四个 dedicated services 加共享 PostgreSQL、Redis 和 RocketMQ。读代码前先按任务归属阅读本目录的运行时边界记忆，再进入源码。

## 阅读顺序

1. 先读本文，确认任务属于哪个运行时边界。
2. 再读对应目录的 `README.md`。
3. 如果任务跨服务或涉及协议、配置、部署，继续读 `shared/` 或 `deployment/`。
4. 如果任务涉及 legacy `app`，读 `compatibility-app/`，不要把它当作默认生产入口。

## 边界索引

| 目录 | 何时阅读 | 主要代码路径 |
| --- | --- | --- |
| `access-gateway/` | TCP 长连接、bind、在线 route、heartbeat、duplicate login、drain、targeted delivery | `access-gateway-app`, `connection-module`, `common/session`, `common/directory` |
| `api-service/` | 登录、session authority、好友/群组、发送策略、history read-side、conversation state read-side | `api-service-app`, `logic-module/http`, `logic-module/service`, `logic-module/repository` |
| `message-service/` | 消息命令、幂等、MQ publish、sender ACK、在线投递、offline fallback | `message-service-app`, `logic-module/chat`, `message-module`, `infra-redis` |
| `persistence-service/` | MQ consume、消息事实持久化、conversation 推进、receipt/state、Flyway migration | `persistence-service-app`, `persistence-module` |
| `shared/` | 跨服务契约、protobuf、session/event/offline/id/seq/lock 抽象、runtime config | `common`, `protocol`, `service-runtime`, `message-module`, `infra-redis` |
| `deployment/` | Kubernetes、Dockerfile、环境变量、端口、kind 验证、native image | `deploy/kubernetes`, `docker-compose.yml`, service `Dockerfile`, service `application.yml` |
| `compatibility-app/` | legacy compatibility shell、回滚入口、旧单体装配 | `app` |

## 维护规则

- 代码变更影响服务职责、协议、配置、数据 ownership、测试入口、部署方式或关键交互语义时，必须同步更新对应 memory。
- 不确定归属时，先读 `shared/README.md`，再读 `docs/mochat-technical-documentation.md` 和相关 `openspec/specs`。
- 当参考文档和源码不一致时，以当前源码、Flyway migration、测试和稳定技术总览为准。
- `docs/ddl/phase1.sql` 是参考稿，不是数据库 truth source；数据库事实以 `persistence-module/src/main/resources/db/migration` 和 JDBC 仓储为准。
```

Expected: top-level README exists and points to all boundary directories.

- [x] **Step 3: Write each boundary README**

For each boundary README, include these sections:

```markdown
# <Boundary Name> Codebase Memory

## 职责

## 非职责

## 主要代码路径

## 核心数据流和交互

## 配置和运行入口

## 测试入口

## 变更时必须同步更新
```

Populate content from Task 1 analyses and accepted design. Keep statements concrete and tied to existing files.

Expected: all seven boundary READMEs exist and contain the required sections.

## Task 3: Create Root `AGENTS.md`

**Files:**
- Create: `AGENTS.md`

- [x] **Step 1: Write repository instructions**

Create `AGENTS.md` with:

```markdown
# AGENTS.md

## 语言

- 默认使用简体中文回复用户。

## 命令行

- 遵守 `~/.codex/RTK.md`。
- Shell 命令默认使用 `rtk` 前缀，例如 `rtk rg ...`、`rtk git status --short`。
- 搜索文件优先使用 `rg`、`fd`；需要 JSON 处理时优先使用 `jq`。

## Codebase Memory

- 分析代码前，先阅读 `docs/codebase/README.md`。
- 根据任务归属继续阅读相关边界目录，例如 `docs/codebase/api-service/README.md` 或 `docs/codebase/message-service/README.md`。
- 涉及协议、共享抽象、部署或 legacy 入口时，同时阅读 `docs/codebase/shared/README.md`、`docs/codebase/deployment/README.md` 或 `docs/codebase/compatibility-app/README.md`。
- 代码变更如果影响服务职责、协议、配置、数据 ownership、测试入口、部署方式或关键交互语义，必须同步更新对应 `docs/codebase/**/README.md`。
- 不确定归属时，先读 `docs/codebase/shared/README.md`、`docs/mochat-technical-documentation.md` 和相关 `openspec/specs`。

## MultiAgent

- 只在子任务彼此独立、不会互相写冲突时使用 MultiAgent。
- 等待子代理时使用长超时；不要因为默认短超时就打断子代理。
- 默认不要打断子代理；补充信息时使用非 interrupt 输入。
- 只有在用户明确要求、任务明显跑偏、发生冲突或存在安全风险时，才允许 interrupt。
```

Expected: `AGENTS.md` exists and includes codebase memory reading and maintenance rules.

## Task 4: Verify Scope and Documentation Quality

**Files:**
- Read: all files created in Tasks 2 and 3
- Modify only documentation files if verification finds gaps.

- [x] **Step 1: Check required files exist**

Run:

```bash
rtk fd -t f . docs/codebase
```

Expected: output includes exactly the eight `docs/codebase/**/README.md` files.

- [x] **Step 2: Check required sections exist**

Run:

```bash
rtk rg -n "^## (职责|非职责|主要代码路径|核心数据流和交互|配置和运行入口|测试入口|变更时必须同步更新)" docs/codebase
```

Expected: each boundary README has all seven section headers.

- [x] **Step 3: Check no placeholders**

Run:

```bash
rtk rg -n "TBD|TODO|待定|占位|placeholder" docs/codebase AGENTS.md
```

Expected: no matches.

- [x] **Step 4: Check diff scope**

Run:

```bash
rtk git diff --name-only
```

Expected: changed files are limited to `AGENTS.md`, `docs/codebase/**`, and this plan if not yet committed.

- [x] **Step 5: Commit implementation**

Run:

```bash
rtk git add AGENTS.md docs/codebase docs/superpowers/plans/2026-06-20-codebase-memory.md
rtk git commit -m "docs: add codebase memory"
```

Expected: commit succeeds.

## Task 5: Final Review

**Files:**
- Review: `AGENTS.md`
- Review: `docs/codebase/**/README.md`
- Review: diff from implementation commit.

- [x] **Step 1: Spec compliance review**

Verify the implementation satisfies:

- `docs/codebase` has eight README files.
- Each runtime boundary README documents responsibilities, non-responsibilities, source paths, flows, config/runtime entries, tests, and maintenance triggers.
- Root `AGENTS.md` requires reading and updating codebase memory.
- No business code changed.

Expected: no spec gaps.

- [x] **Step 2: Code quality review**

Review for:

- Concrete file paths instead of vague descriptions.
- No false claims about production overlays or unimplemented features.
- No unnecessary duplication that makes future maintenance difficult.
- Chinese text is clear and concise.

Expected: no critical or important issues.

- [x] **Step 3: Final status check**

Run:

```bash
rtk git status --short
```

Expected: clean worktree.
