# Local / Dev 环境实现交接文档

## 新会话目标

在新会话中实现 MoChat 的环境拆分：

- `local`：本机直接运行五个 Gradle 进程。
- `dev`：部署到本地 k3s 的 Helm 配置。
- `prod`：本轮只预留命名和文档结构，不实现生产配置。

新会话必须使用 `superpowers:subagent-driven-development` 执行实现计划。不要在读取计划后手工批量实现；应按计划逐任务派 fresh subagent，并在每个任务后做两阶段审查。

## 必读文件

新会话开始后先读这些文件：

1. `docs/codebase/README.md`
2. `docs/codebase/deployment/README.md`
3. `docs/codebase/shared/README.md`
4. `docs/superpowers/specs/2026-06-22-local-dev-environments-design.md`
5. `docs/superpowers/plans/2026-06-22-local-dev-environments.md`
6. `/Users/lystran/.codex/plugins/cache/openai-curated/superpowers/202e9242/skills/subagent-driven-development/SKILL.md`
7. `/Users/lystran/.codex/plugins/cache/openai-curated/superpowers/202e9242/skills/test-driven-development/SKILL.md`
8. `/Users/lystran/.codex/RTK.md`

项目要求默认使用简体中文回复。Shell 命令默认使用 `rtk` 前缀。

## 已确认需求

- 用户明确确认 `local` 是直接跑五个 Gradle 进程。
- 用户明确确认 `dev` 是部署在本地 k3s 中的一套配置。
- 用户选择 local 同时需要：
  - Micronaut local profile 配置。
  - 一键启动脚本。
- 用户确认 `call-service` 在 local 下必须提供 LiveKit 配置；缺少 `MOCHAT_LIVEKIT_*` 应启动前失败。
- 用户要求环境变量放在根目录 `.env`，先预留，后续人工填写。例如 LiveKit 配置。
- 真实 `.env` 不应提交；应提交 `.env.example`。
- 用户要求在新会话实现，并要求本交接文档不要缺少信息。

## 已产出内容

### 设计文档

文件：

- `docs/superpowers/specs/2026-06-22-local-dev-environments-design.md`

已提交：

- `b6b5731 docs: 设计local和dev环境拆分`

### 实现计划

文件：

- `docs/superpowers/plans/2026-06-22-local-dev-environments.md`

当前计划文件已创建但尚未提交。新会话应先检查 `git status --short`，确认该文件存在。

## 当前工作区状态

当前会话结束前状态：

```text
 M docs/codebase/deployment/README.md
 M docs/runbook.md
?? docs/superpowers/plans/2026-06-22-local-dev-environments.md
?? docs/superpowers/handoffs/2026-06-22-local-dev-environments-handoff.md
```

说明：

- `docs/codebase/deployment/README.md` 和 `docs/runbook.md` 是既有未提交修改，应视为用户或先前工作区改动。不要随意覆盖或回滚。
- 计划文件和本交接文档是本轮新增交接产物。
- 实现时如果修改到已有未提交文件，必须先读当前内容，再在其基础上编辑。

## 执行方式要求

新会话使用 `superpowers:subagent-driven-development`。

流程要求：

1. 主代理读取完整计划文件。
2. 把计划任务拆成 Todo。
3. 每个任务派一个 fresh implementer subagent。
4. 不要让子代理自己读取计划文件；主代理应把对应任务的完整文本和必要上下文提供给子代理。
5. 每个任务完成后，先派 spec compliance reviewer。
6. 规格审查通过后，再派 code quality reviewer。
7. 任一 reviewer 发现问题，交回同一 implementer 修复，并重新审查。
8. 不要并行派多个实现子代理，因为计划任务会写同一批配置、文档和测试，容易冲突。
9. 不要在任务之间停下来询问是否继续；除非 BLOCKED、计划错误或用户新指令要求暂停。

等待子代理时遵守项目 AGENTS 指令：

- MultiAgent 只在子任务独立且不会写冲突时使用。
- 等待子代理用长超时。
- 不要随意 interrupt 子代理。
- 如果补充信息，用非 interrupt 输入。

## 实现计划摘要

完整计划在 `docs/superpowers/plans/2026-06-22-local-dev-environments.md`，不要只依赖摘要。摘要如下：

1. 添加 local profile 配置测试。
2. 添加五个服务的 `application-local.yml`。
3. 添加 `.env.example`、`.gitignore` 规则和启动脚本契约测试。
4. 实现 `scripts/run-local.sh`。
5. 添加 `deploy/helm/mochat/values-dev.yaml` 和 Helm contract 测试。
6. 更新 README、runbook、deployment memory 和服务 memory。
7. 运行完整验证。

## 关键设计约束

### local

- 使用 `MICRONAUT_ENVIRONMENTS=local`。
- 本地基础设施来自根目录 `docker-compose.yml`。
- 服务间地址默认使用 `127.0.0.1`：
  - api-service gRPC `19091`
  - message-service gRPC `19092`
  - access-gateway gRPC `19093`
  - access-gateway TCP `9000`
  - access-gateway admin HTTP `18080`
  - call-service HTTP/WebSocket `8090`
- 默认 local 只启动一个 gateway：
  - identity `gateway-a`
  - message-service static target `gateway-a -> 127.0.0.1:19093`
- `scripts/run-local.sh` 应支持：
  - `start`
  - `stop`
  - `status`
  - `restart`
  - `print-commands`
- 脚本读取根目录 `.env`，也支持 `MOCHAT_ENV_FILE` 指定测试用 env 文件。
- 脚本启动前必须校验：
  - `.env` 存在。
  - `MOCHAT_LIVEKIT_URL` 非空。
  - `MOCHAT_LIVEKIT_API_KEY` 非空。
  - `MOCHAT_LIVEKIT_API_SECRET` 非空。
- 日志写 `.local/logs/<service>.log`。
- PID 写 `.local/pids/<service>.pid`。
- `.local/` 已在 `.gitignore` 中忽略。

### dev

- 使用 Helm chart `deploy/helm/mochat`。
- 新增 canonical k3s values：`deploy/helm/mochat/values-dev.yaml`。
- `global.environment=dev`。
- `global.projectId=mochat-dev`。
- 服务发现继续使用 Kubernetes Service/headless Service：
  - `api-service:19091`
  - `message-service:19092`
  - `access-gateway-headless`
- LiveKit、PostgreSQL 凭据、gateway TLS 不提交真实值。

### prod

- 只在文档中说明保留。
- 不新增 `values-prod.yaml`。
- 不实现生产 Secret 管理。

## 重要现有文件和测试入口

服务配置：

- `api-service-app/src/main/resources/application.yml`
- `message-service-app/src/main/resources/application.yml`
- `access-gateway-app/src/main/resources/application.yml`
- `persistence-service-app/src/main/resources/application.yml`
- `call-service-app/src/main/resources/application.yml`

配置测试：

- `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java`
- `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceApplicationContextTest.java`
- `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`
- `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`

Helm 测试：

- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`

当前 `call-service-app` 没有 `src/test` 目录，计划会创建：

- `call-service-app/src/test/java/com/github/lystran/mochat/callservice/CallServiceApplicationContextTest.java`

## 容易踩坑

- 不要把真实 `.env` 提交。
- 根 `.gitignore` 需要忽略 `.env`，但不要忽略 `.env.example`。
- `call-service` 的 `CallTokenService` 当前在实际发 token 时会检查 LiveKit 是否配置；本轮要求是在 local 脚本启动前强制检查，而不是改业务代码。
- Helm 当前有 `values-local.yaml`，但本轮要把 canonical k3s dev 配置放到 `values-dev.yaml`。文档里要减少 `values-local.yaml` 的歧义。
- `docs/runbook.md` 和 `docs/codebase/deployment/README.md` 当前已有未提交改动，实现时先读再改，不要覆盖。
- 计划中 `LocalRunScriptContractTest` 的 Java 代码包含对脚本输出的断言；如果实际脚本输出格式调整，测试也要保持同一语义。
- `rtk rg` 返回 exit code 1 表示没匹配，不一定是失败。
- 运行 Gradle、Helm 命令时按项目要求用 `rtk` 前缀。
- 如果 Gradle 命令因为沙箱或网络失败，按系统要求重新以 escalated 权限请求。

## 建议的新会话开场步骤

1. 读取本交接文档。
2. 读取 `superpowers:subagent-driven-development` skill。
3. 读取 `superpowers:test-driven-development` skill。
4. 读取完整实现计划。
5. 运行：

```bash
rtk git status --short
```

6. 按计划建立 Todo。
7. 从 Task 1 开始派 fresh implementer subagent。

## 最终验收命令

计划中的最终验收命令包括：

```bash
rtk ./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest
rtk ./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceApplicationContextTest
rtk ./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest
rtk ./gradlew :persistence-service-app:test --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest
rtk ./gradlew :call-service-app:test --tests com.github.lystran.mochat.callservice.CallServiceApplicationContextTest
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.local.LocalRunScriptContractTest
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

脚本 dry-run：

```bash
rtk env MOCHAT_ENV_FILE=/tmp/mochat-local.env sh -c 'printf "%s\n" "MOCHAT_LIVEKIT_URL=ws://livekit.local" "MOCHAT_LIVEKIT_API_KEY=local-key" "MOCHAT_LIVEKIT_API_SECRET=local-secret" > /tmp/mochat-local.env && scripts/run-local.sh print-commands'
```

Helm 渲染：

```bash
rtk helm template mochat deploy/helm/mochat -f deploy/helm/mochat/values-dev.yaml
```

预期：全部通过，且 `scripts/run-local.sh print-commands` 输出五个带 `MICRONAUT_ENVIRONMENTS=local` 的 Gradle run 命令。
