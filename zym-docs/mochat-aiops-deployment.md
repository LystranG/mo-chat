# MoChat 接入外部 AIOps 的部署方式

MoChat 作为被监控系统部署，外部 AIOps 通过 Prometheus、Loki、Tempo、Alertmanager 和 Kubernetes API 接入。本文件描述本地联调边界和推荐启动顺序。

## 部署边界

- MoChat 五个服务使用 Helm 部署到 Kubernetes。
- PostgreSQL、Redis、RocketMQ 使用根目录 Docker Compose。
- Prometheus、Loki、Tempo、Alertmanager 使用 `deploy/observability/docker-compose.yml` 做本地联调。
- AIOps 服务不由本项目部署。
- AIOps 对 Kubernetes 的操作权限不由本项目创建。

## 启动流程

先准备 Alertmanager 挂载用的 token 文件：

```bash
cp deploy/observability/alertmanager/secrets/aiops-token.example deploy/observability/alertmanager/secrets/aiops-token
```

`.example` 只作模板。首次本地联调可以先保留占位 token；接入真实 AIOps 时，把真实 token 写入被 gitignore 的 `deploy/observability/alertmanager/secrets/aiops-token`，不要修改 `deploy/observability/alertmanager/secrets/aiops-token.example`。

再启动业务依赖和观测栈：

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
```

如果本机安装的是 standalone Compose，可以用 `docker-compose` 替代 `docker compose`。当前本地环境 standalone `docker-compose` 可用；`docker compose` 需要 Docker CLI 插件支持。

部署 MoChat：

```bash
mkdir -p .local/helm/access-gateway-tls
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -subj "/CN=localhost" \
  -keyout .local/helm/access-gateway-tls/tls.key \
  -out .local/helm/access-gateway-tls/tls.crt

helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-local.yaml \
  --set-file accessGatewayTls.certificate=.local/helm/access-gateway-tls/tls.crt \
  --set-file accessGatewayTls.privateKey=.local/helm/access-gateway-tls/tls.key
```

检查：

```bash
kubectl -n mochat get pods,svc
helm -n mochat status mochat
```

## AIOps 项目配置

参考：

```text
deploy/observability/aiops/projects.yaml.example
```

推荐 project id：

```text
mochat-local
```

MoChat Kubernetes namespace：

```text
mochat
```

本地默认数据源：

- Prometheus: `http://localhost:9090`
- Loki: `http://localhost:3100`
- Tempo: `http://localhost:3200`
- Kubernetes: `~/.kube/config`，namespace `mochat`

如果 AIOps 在容器中运行，请把 `localhost` 改成 `host.docker.internal` 或同 Docker 网络内可解析的服务名。

Prometheus 的 MoChat scrape 目前是本地联调示例。chart 默认 `observability.prometheus.scrape=false`；启用前需要确认目标服务实际暴露 `/prometheus`，并让 Docker Compose 中的 Prometheus 可以访问目标端口。当前示例 target 使用 `host.docker.internal:18080`，可配合：

```bash
kubectl -n mochat port-forward pod/access-gateway-0 18080:18080
```

## Alertmanager Webhook

Alertmanager webhook 指向：

```text
<AIOPS_URL>/api/v1/alerts/webhook
```

当前 `deploy/observability/alertmanager/alertmanager.yml` 已配置项目 header：

```text
X-Project-Id: mochat-local
```

请求认证使用 Bearer token。真实 token 写入被 gitignore 的实际文件：

```text
deploy/observability/alertmanager/secrets/aiops-token
```

Alertmanager 容器会把该文件挂载到：

```text
/etc/alertmanager/secrets/aiops-token
```

最终发送到 AIOps 的请求包含：

```text
Authorization: Bearer <token>
X-Project-Id: mochat-local
```

## native-first 和 call-service

MoChat 默认 native-first 部署，不默认使用 OpenTelemetry Java Agent。chart 会预留 OTEL 环境变量；native trace 作为后续增强，需要在目标运行环境单独验证。

`call-service` 的 `nativeCompile` 已通过，镜像构建路径可用。但 `call-service` 默认 `replicaCount: 1`，因为活跃通话房间当前保存在进程内内存中。需要多副本时，先设计粘性路由或把房间状态外部化。
