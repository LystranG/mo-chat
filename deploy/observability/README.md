# MoChat 本地观测栈

这个目录提供本地联调用 Docker Compose 观测栈：

- Prometheus
- Loki
- Promtail
- Tempo
- Alertmanager

MoChat 业务服务不在这个 Compose 文件中运行；业务服务由本地进程、Kubernetes kustomize 或 `deploy/helm/mochat` 管理。

## 启停

启动前先准备 Alertmanager 挂载用的 token 文件。首次本地联调可以先保留占位 token；接入真实 AIOps 时，只修改无后缀的 `deploy/observability/alertmanager/secrets/aiops-token`，不要修改 `.example` 模板文件。

```bash
cp deploy/observability/alertmanager/secrets/aiops-token.example deploy/observability/alertmanager/secrets/aiops-token
```

启动：

```bash
docker compose -f deploy/observability/docker-compose.yml up -d
```

停止：

```bash
docker compose -f deploy/observability/docker-compose.yml down
```

如果本机安装的是 standalone Compose，也可以使用：

```bash
docker-compose -f deploy/observability/docker-compose.yml up -d
docker-compose -f deploy/observability/docker-compose.yml down
```

当前本地环境已验证 standalone `docker-compose` 可用；`docker compose` 是否可用取决于 Docker CLI 插件安装情况。

## AIOps 接入

外部 AIOps 项目配置可参考：

```text
deploy/observability/aiops/projects.yaml.example
```

默认数据源：

- Prometheus: `http://localhost:9090`
- Loki: `http://localhost:3100`
- Tempo: `http://localhost:3200`
- Alertmanager: `http://localhost:9093`

Alertmanager webhook 配置位于：

```text
deploy/observability/alertmanager/alertmanager.yml
```

当前配置已经向 AIOps webhook 发送项目 header：

```text
X-Project-Id: mochat-local
```

Bearer token 不应写入 `.example` 文件。把真实 token 写入被 gitignore 的 `deploy/observability/alertmanager/secrets/aiops-token`；`deploy/observability/alertmanager/secrets/aiops-token.example` 只作为模板保留。

## Prometheus

Prometheus 配置位于：

```text
deploy/observability/prometheus/prometheus.yml
deploy/observability/prometheus/rules/mochat-alerts.yml
```

本地 Helm 部署时，chart 预留 Prometheus annotations 和 `/prometheus` scrape 示例，但 `observability.prometheus.scrape` 默认关闭。启用前需要确认目标服务镜像实际暴露 `/prometheus`。

当前 `prometheus.yml` 中的 `mochat-port-forward` target 指向 `host.docker.internal:18080`，用于配合本机 port-forward 联调 access-gateway admin 端口：

```bash
kubectl -n mochat port-forward pod/access-gateway-0 18080:18080
```

如果 Prometheus 运行在 Docker Compose 网络里，仍需按本机网络和集群类型调整 scrape target。

## 日志和 Trace

Promtail 在 Docker Compose 中运行时，默认只能采集宿主机 `/var/log/*.log`。MoChat Pod 日志如果运行在 Kubernetes 中，生产或完整联调应改用 Kubernetes 内的日志采集器。

Tempo 默认暴露 OTLP gRPC `4317`、OTLP HTTP `4318` 和查询端口 `3200`。MoChat chart 预留 OTEL 环境变量，但默认 native-first 部署不保证第一阶段 trace 数据完整上报。
