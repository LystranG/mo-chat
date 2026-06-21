# Codebase Memory 设计

## 背景

MoChat 当前默认运行形态是四个 dedicated services 加共享基础设施：

- `access-gateway`
- `api-service`
- `message-service`
- `persistence-service`

`app` 仍保留为 compatibility shell，但不再是默认开发和运行入口。仓库已有 `README.md`、`docs/mochat-technical-documentation.md`、`docs/runbook.md`、`docs/architecture/*` 和 `openspec/specs/*`，但缺少面向代码阅读和后续维护的 codebase memory。

本次目标是在 `docs/codebase` 下建立按运行时边界组织的代码记忆，并在根级 `AGENTS.md` 中补充约束：分析代码前必须阅读相关 codebase memory；代码变更影响边界事实时必须同步更新 memory。

## 组织方式

采用按运行时边界组织的目录结构，而不是按 Gradle 模块直接平铺。原因是当前项目的核心事实是服务 ownership 和跨服务交互语义，运行时边界比模块名更适合指导代码阅读。

目标结构：

```text
docs/codebase/
  README.md
  access-gateway/
    README.md
  api-service/
    README.md
  message-service/
    README.md
  persistence-service/
    README.md
  shared/
    README.md
  deployment/
    README.md
  compatibility-app/
    README.md
```

## 各目录职责

### `docs/codebase/README.md`

总入口，说明：

- 推荐阅读顺序。
- 每个目录对应的运行时边界。
- 每个边界覆盖的主要 Gradle 模块。
- 维护规则：代码变更影响职责、协议、配置、数据 ownership、测试入口或部署方式时，必须更新对应 memory。
- 与现有文档的关系：稳定事实优先参考 `docs/mochat-technical-documentation.md`，运行和部署细节参考 `docs/runbook.md`，正式规格参考 `openspec/specs`。

### `docs/codebase/access-gateway/README.md`

覆盖长连接入口和在线连接 ownership：

- 主要代码路径：`access-gateway-app`、`connection-module`、`common/session`、`common/directory`、相关 internal gRPC proto。
- 记录 TCP bind、session authority 校验、Redis route 写入和续租、heartbeat、duplicate login、drain、targeted delivery。
- 明确它不拥有消息 durable truth、history read-side 或业务社交关系。

### `docs/codebase/api-service/README.md`

覆盖账户、会话权威和读侧 HTTP：

- 主要代码路径：`api-service-app`、`logic-module/http`、`logic-module/service`、`logic-module/repository` 中 API/read-side 相关部分、`common/session`、相关 internal gRPC proto。
- 记录登录、session authority、好友/群组/发送策略校验、history query、conversation state read-side、登录后离线重放触发。
- 明确 history 可见性以 persistence commit 为准。

### `docs/codebase/message-service/README.md`

覆盖同步消息接受和在线投递编排：

- 主要代码路径：`message-service-app`、`logic-module/chat`、`message-module`、`infra-redis` 中幂等和 offline queue 相关部分、相关 internal gRPC proto。
- 记录 message ingest、幂等、`msgId`/`seq` 分配、RocketMQ publish、sender ACK、route 解析、targeted delivery、route refresh once、offline fallback。
- 明确 sender ACK 不代表 recipient 已收、DB 已提交或 history 已可见。

### `docs/codebase/persistence-service/README.md`

覆盖异步 durable truth：

- 主要代码路径：`persistence-service-app`、`persistence-module`、`persistence-module/src/main/resources/db/migration`、`message-module` 中 persistence contract。
- 记录 MQ consume、事务持久化、`messages` 和 `conversations` 写入、receipt/state 推进、post-commit group cache update、duplicate consume idempotency。
- 明确 PostgreSQL durable truth 以 Flyway migration 和 JDBC 仓储代码为准。

### `docs/codebase/shared/README.md`

覆盖跨服务契约和共享基础设施抽象：

- 主要代码路径：`common`、`protocol`、`service-runtime`、`infra-redis`、`message-module`。
- 记录 TCP/protobuf 协议、internal gRPC proto、session/event/offline/id/seq/lock 抽象、runtime configuration、Redis adapter。
- 明确共享层只保留协议和契约，不应重新演化为隐式单体装配层。

### `docs/codebase/deployment/README.md`

覆盖部署和运行时拓扑：

- 主要代码路径：`deploy/kubernetes`、各服务 `Dockerfile`、各服务 `application.yml`、`docker-compose.yml`、根 Gradle 构建。
- 记录 Kubernetes base/kind overlay、ConfigMap/Secret 约定、服务端口、环境变量、native image、kind 验证入口。
- 明确生产 overlay 当前不存在时不能凭文档推断已交付。

### `docs/codebase/compatibility-app/README.md`

覆盖 legacy compatibility shell：

- 主要代码路径：`app`。
- 记录 `app` 作为 compatibility shell 的定位、默认不代表 dedicated topology、显式开关恢复 legacy path 的约束。
- 防止后续阅读代码时把 `app` 误判为默认生产入口。

## 并行分析策略

使用 MultiAgent 时只并行分配互不写冲突的分析任务：

- `access-gateway` 边界分析。
- `api-service` 边界分析。
- `message-service` 边界分析。
- `persistence-service` 边界分析。
- `shared/deployment/compatibility-app` 分析。

子代理只负责读取代码和返回结构化分析，不直接写 `docs/codebase`。最终由主代理汇总并写入文档，避免多个代理同时编辑同一文件或目录。

等待子代理时使用长超时，不随意 interrupt。只有任务跑偏、冲突、安全风险或用户明确要求时才中断。

## `AGENTS.md` 更新

根级 `AGENTS.md` 需要新增或创建，包含：

- 以简体中文回复用户。
- 遵守 `~/.codex/RTK.md`，shell 命令使用 `rtk` 前缀。
- 分析代码前先阅读 `docs/codebase/README.md` 和任务相关边界目录。
- 代码变更影响服务职责、协议、配置、数据 ownership、测试入口、部署方式或重要交互语义时，同步更新对应 `docs/codebase/**/README.md`。
- 不确定归属时，先读 `docs/codebase/shared/README.md`、`docs/mochat-technical-documentation.md` 和相关 `openspec/specs`。
- 使用 MultiAgent 时，子任务必须独立、互不写冲突，并使用长等待时间。

## 验收标准

- `docs/codebase` 目录存在并包含上述 8 个 README 文件。
- 每个运行时边界 README 都说明职责、非职责、核心代码路径、关键数据/消息流、配置和测试入口。
- 根级 `AGENTS.md` 存在，并明确 codebase memory 的阅读和维护要求。
- 文档内容与当前仓库事实一致，不引入不存在的生产 overlay、未落地功能或错误 ownership。
- 不修改业务代码。
- 完成后检查 `git diff`，确认变更只包含文档和 `AGENTS.md`。

## 风险与处理

- 风险：运行时边界和 Gradle 模块不是一一对应，读者可能漏看共享模块。
  - 处理：每个 README 明确列出相关 Gradle 模块和代码路径。
- 风险：已有 docs 与代码事实可能存在轻微滞后。
  - 处理：以当前源码、Flyway migration、测试和稳定技术总览为主，必要时标注“参考文档”而不是把旧文档当作 truth source。
- 风险：多个代理同时写文件导致冲突。
  - 处理：子代理只分析不写文件，主代理统一落盘。
