# MoChat k3s 观测栈

这个目录提供部署到 k3s 集群内部的演示级观测栈，和上层
`deploy/observability/docker-compose.yml` 的本机 Compose 观测栈彼此独立。

包含组件：

- Prometheus
- Alertmanager
- Loki
- Promtail
- Tempo

## 部署

```bash
kubectl apply -k deploy/observability/kubernetes
```

所有资源默认部署到 `mochat-observability` namespace。存储使用 `PersistentVolumeClaim`,由 k3s 的 local-path-provisioner 自动置备：

| 组件 | PVC 名称 | 容量 |
|------|---------|------|
| Prometheus | `prometheus-data` | 2Gi |
| Alertmanager | `alertmanager-data` | 256Mi |
| Loki | `loki-data` | 5Gi |
| Tempo | `tempo-data` | 1Gi |

Pod 重启后数据不会丢失。如需清理数据，删除对应 PVC 即可：`kubectl -n mochat-observability delete pvc <name>`。

## MoChat 接入

Prometheus 会在 `mochat` namespace 中发现带有下面 annotation 的 Pod 或 Service：

```yaml
prometheus.io/scrape: "true"
prometheus.io/path: "/prometheus"
prometheus.io/port: "8080"
```

Helm chart 已预留这些 annotation，启用前仍需确认目标服务实际暴露对应 metrics path。

Tempo 的 OTLP Service 地址：

```text
http://tempo.mochat-observability.svc.cluster.local:4318
tempo.mochat-observability.svc.cluster.local:4317
```

MoChat Helm values 可把 `observability.otel.endpoint` 指向 Tempo，例如：

```yaml
observability:
  otel:
    enabled: true
    endpoint: http://tempo.mochat-observability.svc.cluster.local:4318
```

## 日志采集假设

Promtail 以 DaemonSet 运行，挂载每个节点的 `/var/log/pods` 并用 CRI pipeline 解析日志，默认匹配 `/var/log/pods/*<pod-uid>/<container>/*.log`。
这个路径适用于常见 k3s/containerd 节点。某些 Colima/Docker runtime 组合可能只暴露目录、不暴露实际日志文件；此时 Promtail Pod 仍会运行，但不会采集到 MoChat Pod 日志，需要把 `promtail.yaml` 中的 hostPath 和 `__path__` 调整为实际节点日志路径，或改用 Grafana Alloy 的 Kubernetes API 日志采集方式。

## Alertmanager webhook

Alertmanager 保留本机 Compose 观测栈的 AIOps webhook 地址语义：

```text
http://host.docker.internal:8000/api/v1/alerts/webhook
```

Kubernetes manifest 只配置 Bearer token；Alertmanager 原生 webhook 不支持给单个 receiver 直接配置自定义 `X-Project-Id` header，项目标识应由 alert labels 或外部 webhook 网关处理。

默认 Secret `alertmanager-aiops-token` 只包含占位 token。接入真实 AIOps 时，先在
`mochat-observability` namespace 覆盖同名 Secret，或修改 `alertmanager.yaml` 中的
receiver 配置。
