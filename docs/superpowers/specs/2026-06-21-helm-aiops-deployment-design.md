# Helm 与 AIOps 接入部署设计

## 背景

MoChat 当前默认运行形态是 dedicated services 加共享基础设施。已有 Kubernetes/kind 资源覆盖 `api-service`、`message-service`、`persistence-service`、`access-gateway`，但新增的 `call-service` 还没有 Dockerfile、Kubernetes workload、Service 或验证入口。

`zym-docs/aiops接入指南.md` 描述的 `mo-chat-aiops` 是外部 AIOps 服务。本项目不部署 AIOps，也不管理 AIOps 对 Kubernetes 的操作权限。MoChat 作为被监控系统，只需要提供可发现、可采集、可关联的运行时信号。

## 目标

- 使用 Helm 简化 MoChat 五个业务服务的 Kubernetes 部署。
- 将 `call-service` 纳入 Kubernetes 部署管理。
- 基础设施 PostgreSQL、Redis、RocketMQ 继续使用 Docker Compose。
- Prometheus、Loki、Tempo、Alertmanager 也使用 Docker Compose 做本地联调观测栈。
- AIOps 作为外部服务，通过 Prometheus、Loki、Tempo、Alertmanager 和 Kubernetes API 接入。
- 默认部署命令使用 Docker CLI，不把 Podman 作为 runbook 默认命令。
- 将 `docs/runbook.md` 整体维护为简体中文表达。

## 非目标

- 不在 MoChat Helm chart 中部署 PostgreSQL、Redis、RocketMQ。
- 不在 MoChat Helm chart 中部署 Prometheus、Loki、Tempo、Alertmanager。
- 不在本项目中部署 AIOps 服务。
- 不在本项目中创建 AIOps 的 Kubernetes 操作权限或自愈策略。
- 不为了 OpenTelemetry Java Agent 改成 JVM-first 部署。
- 不删除现有 `deploy/kubernetes/base` 和 `deploy/kubernetes/overlays/kind`。

## 总体架构

部署边界分三层：

1. `deploy/helm/mochat` 管理 MoChat 被监控业务系统。
2. Docker Compose 管理本地基础设施和本地观测栈。
3. 外部 AIOps 读取观测栈数据源，并自行访问 Kubernetes API。

数据流：

```text
mo-chat Pods
  -> health / metrics / stdout logs / optional trace env
  -> Prometheus / Loki / Tempo / Alertmanager
  -> external mo-chat-aiops
```

告警流：

```text
Prometheus rules -> Alertmanager -> AIOps /api/v1/alerts/webhook
```

Kubernetes 自愈或 Pod 操作由外部 AIOps 自己配置，本项目只保证 workload、Service、label、namespace 和日志/指标关联清晰。

## Helm Chart 设计

新增 `deploy/helm/mochat`：

```text
deploy/helm/mochat/
  Chart.yaml
  values.yaml
  values.schema.json
  values-local.yaml
  README.md
  templates/
    _helpers.tpl
    namespace.yaml
    configmap-runtime.yaml
    configmap-observability.yaml
    secret-external-dependencies.yaml
    secret-access-gateway-tls.yaml
    secret-livekit.yaml
    serviceaccount.yaml
    api-service.yaml
    message-service.yaml
    persistence-service.yaml
    access-gateway.yaml
    call-service.yaml
    NOTES.txt
```

`values.yaml` 分组：

- `global`: namespace、imageRegistry、imagePullPolicy、commonLabels、projectId、environment、clusterDomain。
- `externalDependencies`: PostgreSQL、Redis、RocketMQ 地址与凭据引用。
- `observability`: metrics、Prometheus annotation、OTEL 预留环境变量、日志关联标签。
- `apiService`、`messageService`、`persistenceService`、`accessGateway`、`callService`: 各服务 image、replicaCount、resources、ports、env、probes。
- `secrets`: 支持本地测试 Secret，也支持引用已有 Secret。生产环境推荐引用已有 Secret。
- `gatewayExposure`: 先覆盖 `access-gateway-tcp` NodePort；HTTP Ingress 后续再扩展。

Chart 不声明基础设施或观测栈 dependencies。所有外部依赖通过 values 注入地址或 Secret 引用。

## call-service Kubernetes 化

`call-service` 纳入 Helm chart，并新增 `call-service-app/Dockerfile`。

默认部署约束：

- `Deployment/call-service`
- `Service/call-service`
- HTTP/WebSocket 端口 `8090`
- `replicaCount: 1`
- `MOCHAT_CALL_SERVICE_HTTP_HOST=0.0.0.0`
- `MOCHAT_CALL_SERVICE_HTTP_PORT=8090`
- 复用 PostgreSQL、Redis、RocketMQ 外部依赖配置。
- LiveKit 通过 Secret 注入：
  - `MOCHAT_LIVEKIT_URL`
  - `MOCHAT_LIVEKIT_API_KEY`
  - `MOCHAT_LIVEKIT_API_SECRET`

`call-service` 当前活跃房间状态在 JVM 内存中，不能默认无状态横向扩容。文档和 values 注释必须明确：若要 `replicaCount > 1`，需要先设计 sticky session 或外部化房间状态。

镜像策略 native-first：

- `api-service`、`message-service`、`access-gateway` 保持现有 native 镜像方向。
- `persistence-service` 暂时保持现有 JVM 镜像方向，除非 native 构建稳定。
- `call-service` 优先尝试 native Dockerfile；如果依赖导致 native 不稳定，可降级 JVM 镜像，但必须在 chart values 和 runbook 中显式标注。

## AIOps 观测接入面

### Kubernetes 关联标签

五个服务统一使用：

```text
app.kubernetes.io/name=<service>
app.kubernetes.io/part-of=mochat
app.kubernetes.io/instance=<release>
mochat.lystran.io/project-id=mochat-local
mochat.lystran.io/environment=local
```

这些标签用于 Prometheus target、Loki 日志、AIOps 服务拓扑和 K8s 资源关联。

### 健康检查

`access-gateway` 保留现有 drain-aware readiness/liveness/preStop 语义。

其他服务优先补齐 Micronaut management health endpoint，并在 Helm probes 中使用。若某个服务暂时没有 HTTP 管理端口，chart 必须允许关闭 probes，实施计划中再补齐最小 management 配置。

### Metrics

优先通过 Micronaut management + Micrometer Prometheus 暴露 Prometheus 可抓取端点。native 部署不要求完整 JVM 指标；只要提供服务健康、HTTP/gRPC 基础指标或后续业务指标即可。

Prometheus scrape annotation 由 values 控制：

```yaml
prometheus.io/scrape: "true"
prometheus.io/path: "/prometheus"
prometheus.io/port: "<http-or-management-port>"
```

### Logs

日志通过 stdout/stderr 输出，由 Loki/Promtail 或其他采集器收集。第一阶段不强制业务代码改 JSON 日志；优先保证 Kubernetes labels 能关联服务、环境和 project。

### Traces 与 OpenTelemetry

不把 OpenTelemetry Java Agent 作为默认能力，因为 Java Agent 不能直接挂到 GraalVM native image 二进制上。Helm 只预留 `observability.otel.*` values 和环境变量开关：

```text
OTEL_SERVICE_NAME=<service>
OTEL_RESOURCE_ATTRIBUTES=service.namespace=mochat,deployment.environment=local,mochat.project_id=mochat-local
OTEL_EXPORTER_OTLP_ENDPOINT=<tempo-or-collector>
```

native trace 作为后续增强，需单独评估 Micronaut/OpenTelemetry SDK 或 GraalVM native 支持成本。

## Docker Compose 观测栈

保留现有 `docker-compose.yml` 管理 PostgreSQL、Redis、RocketMQ。

新增：

```text
deploy/observability/
  docker-compose.yml
  README.md
  prometheus/prometheus.yml
  prometheus/rules/mochat-alerts.yml
  alertmanager/alertmanager.yml
  loki/loki.yml
  promtail/promtail.yml
  tempo/tempo.yml
  aiops/projects.yaml.example
```

默认命令使用 Docker：

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
```

Prometheus 本地联调可以通过 kind/kubernetes service discovery、NodePort 或 port-forward 访问 mo-chat metrics。实施时选择最稳定的一种写入配置。

如果观测栈在 Docker Compose 中运行，而 mo-chat 在 Kubernetes 中运行，Promtail 直接采 Pod 日志会有本地环境限制。第一阶段文档必须明确本地日志采集路径；必要时提供 Kubernetes 内 Promtail 补充 manifest 或说明仅验证 Prometheus/Alertmanager 链路。

## AIOps 联调

`deploy/observability/aiops/projects.yaml.example` 指向：

- Prometheus: `http://<host>:9090`
- Loki: `http://<host>:3100`
- Tempo: `http://<host>:3200`
- Kubernetes namespace: `mochat`
- `project_id`: `mochat-local`

Alertmanager webhook 示例：

```text
POST <AIOPS_URL>/api/v1/alerts/webhook
Authorization: Bearer <token>
X-Project-Id: mochat-local
```

本项目不保存 AIOps token，只提供占位示例和配置说明。

## 验证策略

### Helm 渲染与契约测试

新增或扩展 `service-runtime` manifest contract 测试，覆盖：

- `helm template` 能生成五个服务资源。
- `call-service` 有 Deployment、Service、LiveKit Secret 引用、端口 `8090`。
- 五个服务具备标准 Kubernetes labels 和 AIOps project labels。
- Prometheus scrape annotation、health/probe 配置符合 values。
- `call-service.replicaCount` 默认 `1`。

### 本地 smoke test

Runbook 覆盖：

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
docker build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
docker build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
docker build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
docker build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
docker build -f call-service-app/Dockerfile -t localhost/mochat/call-service:dev .
helm upgrade --install mochat deploy/helm/mochat --namespace mochat --create-namespace -f deploy/helm/mochat/values-local.yaml
kubectl -n mochat get pods,svc
```

验证 Prometheus target、Alertmanager 测试告警和 AIOps `projects.yaml.example` 数据源地址。

## 文档更新

必须更新：

- `docs/codebase/deployment/README.md`
- `docs/runbook.md`
- `deploy/helm/mochat/README.md`
- `deploy/observability/README.md`
- `zym-docs/mochat-aiops-deployment.md`

`docs/runbook.md` 改为简体中文主文档，命令默认使用 Docker。

## 迁移与回滚

现有 kustomize/kind 资源保留，不在第一阶段删除。Helm 成为推荐部署路径；kustomize 作为历史验证与回滚参考。

如果 Helm 部署不稳定，可继续使用现有 `deploy/kubernetes/overlays/kind` 验证路径，或回退到 runbook 中的静态地址拓扑。
