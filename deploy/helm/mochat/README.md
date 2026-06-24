# MoChat Helm 部署

这个 chart 只部署 MoChat 五个业务服务：

- `api-service`
- `message-service`
- `persistence-service`
- `access-gateway`
- `call-service`

PostgreSQL、Redis、RocketMQ、Prometheus、Loki、Tempo、Alertmanager 和外部 AIOps 不属于这个 chart。它们在本地联调时由根目录 `docker-compose.yml` 和 `deploy/observability/docker-compose.yml` 管理。

## 本地部署

先准备 Alertmanager 挂载用的 token 文件。首次本地联调可以先保留占位 token；接入真实 AIOps 时，只修改无后缀的 `deploy/observability/alertmanager/secrets/aiops-token`，不要修改 `.example` 模板文件。

```bash
cp deploy/observability/alertmanager/secrets/aiops-token.example deploy/observability/alertmanager/secrets/aiops-token
```

再启动基础设施和观测栈：

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
```

如果本机安装的是 standalone Compose，也可以把上述命令中的 `docker compose` 替换为 `docker-compose`。

构建本地镜像：

```bash
scripts/build-local-images.sh
```

默认构建并加载 `localhost/mochat/{access-gateway,api-service,message-service,persistence-service,call-service}:dev`。需要切换 tag 或镜像前缀时，可覆盖 `IMAGE_TAG`、`IMAGE_REGISTRY`、`IMAGE_NAMESPACE`。

脚本默认使用 `LOCAL_IMAGE_MODE=native-container`：先用 Linux GraalVM builder 容器挂载当前仓库，在 `/workspace` 内执行四个 native app 的 `nativeCompile` 和 `persistence-service-app:installDist`，再把这些 Linux 产物复制进最终镜像。这是 macOS + Colima/k3s 的默认 native 快速路径。`LOCAL_IMAGE_MODE=jvm` 可改为宿主机 `installDist` + JRE 镜像；`LOCAL_IMAGE_MODE=native-host` 只支持 Linux 宿主机直接执行 nativeCompile。

如果只需要本地 k3s 快速演示，不想触发 native-image 编译，使用 JVM 镜像入口：

```bash
scripts/build-local-jvm-images.sh
```

该入口固定使用 `LOCAL_IMAGE_MODE=jvm`，只在宿主机执行五个 app 的 `installDist`，再复制 JVM 分发包进 JRE 镜像。

准备 access-gateway TLS 证书。`accessGatewayTls.certificate` / `accessGatewayTls.privateKey` 是启动必需配置，本地联调可以使用自签名证书：

```bash
mkdir -p .local/helm/access-gateway-tls
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -subj "/CN=localhost" \
  -keyout .local/helm/access-gateway-tls/tls.key \
  -out .local/helm/access-gateway-tls/tls.crt
```

部署前先确认当前 `kubectl` context 指向目标 Kubernetes 集群，并且 Pod 能访问 `localhost/mochat/*:dev` 镜像。Docker Desktop Kubernetes 通常可以直接使用本机 Docker daemon 中的镜像；kind、minikube 或远端集群不能默认看到本机 Docker 镜像。kind 可使用 `kind load docker-image ...` 或本地 registry，远端集群需要把镜像推送到可访问 registry，并覆盖 `global.imageRegistry` 和各服务 image tag。完整镜像可见性和远端 values 说明见 `docs/runbook.md`。

非通话链路本地验证可以暂留空 LiveKit values。验证 `/calls/**` 通话 token 签发时，必须提供 `livekit.url` / `livekit.apiKey` / `livekit.apiSecret`，或使用预建 `mochat-livekit` Secret 并设置 `livekit.createSecret=false` 和 `livekit.secretName=mochat-livekit`：

```bash
--set livekit.url=https://livekit.example.com \
--set livekit.apiKey=REPLACE_WITH_API_KEY \
--set livekit.apiSecret=REPLACE_WITH_API_SECRET
```

部署到 Kubernetes：

```bash
helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-dev.yaml \
  --set-file accessGatewayTls.certificate=.local/helm/access-gateway-tls/tls.crt \
  --set-file accessGatewayTls.privateKey=.local/helm/access-gateway-tls/tls.key
```

`values.yaml` 和 `values-dev.yaml` 中 `global.createNamespace` 均为 `false`。推荐使用 Helm CLI 的 `--create-namespace` 创建 namespace，避免 chart 内 `Namespace` 与 Helm CLI 创建的 namespace ownership 冲突。
完整部署手册见 `docs/runbook.md`。

检查部署状态：

```bash
kubectl -n mochat get pods,svc
kubectl -n mochat rollout status statefulset/access-gateway
kubectl -n mochat rollout status deploy/call-service
helm -n mochat status mochat
```

## 默认值和外部依赖

本地 k3s/k3d dev 联调推荐使用 `deploy/helm/mochat/values-dev.yaml`。它会把 Redis、PostgreSQL 和 RocketMQ 指向 k3d 集群访问宿主机 Docker Compose 服务的默认地址：

- Redis: `redis://host.k3d.internal:6379`
- PostgreSQL: `jdbc:postgresql://host.k3d.internal:5432/mochat`
- RocketMQ NameServer: `host.k3d.internal:9876`

如果使用 Docker Desktop Kubernetes、kind、minikube 或远端集群，应按实际 Pod 可达地址覆盖 `externalDependencies.*`。

`values-local.yaml` 是旧兼容命名，不再作为 canonical 推荐路径；新增或更新文档时应引用 `values-dev.yaml`。

生产或共享环境应使用独立 values 文件覆盖外部依赖、镜像仓库、镜像 tag、资源限制和 Secret 管理方式，不要把真实凭据提交到 chart 默认 values。

## call-service 限制

`call-service` 默认 `replicaCount: 1`。它当前把活跃通话房间保存在进程内内存中，不能直接按无状态服务方式横向扩容。需要多副本时，先设计粘性路由或把房间状态外部化。

`call-service-app/Dockerfile` 已使用 native-first 构建路径，`nativeCompile` 已通过。这个结论只表示镜像构建路径可用，不改变当前单副本和进程内房间状态限制。

## OpenTelemetry

chart 预留了 OTEL 环境变量：

- `observability.otel.enabled`
- `observability.otel.endpoint`
- `observability.otel.resourceAttributes`

MoChat 默认 native-first 部署，不默认启用 OpenTelemetry Java Agent。native trace 不是第一阶段保证项；需要链路追踪时，应先在目标镜像和运行环境中验证 Micronaut native telemetry 行为。

## AIOps 标识

使用 `values-dev.yaml` 时，默认项目标识是 `mochat-dev`，来自 `global.projectId`。Helm 模板会把它写入 labels 和观测相关环境变量，便于 Prometheus 规则、Alertmanager 和外部 AIOps 按项目归集数据。

## Prometheus 指标

`observability.prometheus.scrape` 默认关闭。当前 chart 只预留 Prometheus annotations 和本地 scrape 示例；启用前需确认目标镜像实际暴露 `/prometheus`，并保证 Prometheus 可以访问对应端口。
