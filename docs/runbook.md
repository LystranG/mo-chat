# MoChat 本地部署 Runbook

这份文档只保留当前推荐路径：本机 Docker Compose 提供 PostgreSQL、Redis、RocketMQ，业务服务用 Helm 部署到本地 Kubernetes。旧 `deploy/kubernetes/overlays/kind` 仍可作为 legacy 验证入口，但不再作为默认部署流程。

## 运行形态

- `local`：直接在本机运行五个 Gradle 进程，入口是 `scripts/run-local.sh`。
- `dev`：部署到本地 k3s/Colima，入口是 Helm chart `deploy/helm/mochat`。
- `prod`：只预留命名，仓库内尚未交付生产 values 或 overlay。

## Colima/k3s 快速部署

### 1. 启动基础设施

```bash
docker compose up -d
```

可选启动本机 Compose 观测栈；它主要服务 `local` 进程联调，不会自动发现 k3s 里的 Pod：

```bash
cp deploy/observability/alertmanager/secrets/aiops-token.example deploy/observability/alertmanager/secrets/aiops-token
docker compose -f deploy/observability/docker-compose.yml up -d
```

k3s 内部观测栈在第 5 步后单独部署。

### 2. 构建本地镜像

演示优先用 JVM 镜像，避免 native-image 编译耗时：

```bash
scripts/build-local-jvm-images.sh
```

默认生成并加载：

```text
localhost/mochat/access-gateway:dev
localhost/mochat/api-service:dev
localhost/mochat/message-service:dev
localhost/mochat/persistence-service:dev
localhost/mochat/call-service:dev
```

如果需要 native 镜像，使用：

```bash
scripts/build-local-images.sh
```

`build-local-images.sh` 默认通过 Linux GraalVM builder 容器编译 native 产物，再复制进最终镜像。可用 `NATIVE_CONTAINER_MEMORY=12g` 调大 builder 内存。

### 3. 准备 access-gateway TLS

```bash
mkdir -p .local/helm/access-gateway-tls
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -subj "/CN=localhost" \
  -keyout .local/helm/access-gateway-tls/tls.key \
  -out .local/helm/access-gateway-tls/tls.crt
```

### 4. Helm 部署

Colima/k3s 使用 `values-local.yaml` 覆盖宿主机访问地址，并把 `call-service` 以 NodePort 暴露到 `32090`。演示环境先保持单个 `access-gateway` 副本。

通话演示需要 LiveKit 配置。根目录 `.env` 不会被 k3s 自动读取，部署前先把它加载到当前 shell，再通过 Helm values 创建 `mochat-livekit` Secret：

```bash
set -a
source .env
set +a

helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-dev.yaml \
  -f deploy/helm/mochat/values-local.yaml \
  --set accessGateway.replicaCount=1 \
  --set-string livekit.url="$MOCHAT_LIVEKIT_URL" \
  --set-string livekit.apiKey="$MOCHAT_LIVEKIT_API_KEY" \
  --set-string livekit.apiSecret="$MOCHAT_LIVEKIT_API_SECRET" \
  --set-file accessGatewayTls.certificate=.local/helm/access-gateway-tls/tls.crt \
  --set-file accessGatewayTls.privateKey=.local/helm/access-gateway-tls/tls.key
```

如果只验证非通话链路，可以暂时省略三个 `livekit.*` 参数；这时 `call-service` 能启动，但真正请求 LiveKit token 时会失败。

`values-local.yaml` 会把外部依赖配置为：

```text
Redis:       redis://host.docker.internal:6379
PostgreSQL:  jdbc:postgresql://host.docker.internal:5432/mochat
RocketMQ:    host.docker.internal:9876
```

`values-dev.yaml` 使用 `host.k3d.internal`，只适合 k3d。Colima/k3s 不要单独使用 `values-dev.yaml`，否则 Pod 会因为解析不到 `host.k3d.internal` 而崩溃。

### 5. 检查状态

```bash
kubectl -n mochat get pods,svc
kubectl -n mochat rollout status deploy/api-service
kubectl -n mochat rollout status deploy/message-service
kubectl -n mochat rollout status deploy/persistence-service
kubectl -n mochat rollout status deploy/call-service
kubectl -n mochat rollout status statefulset/access-gateway
helm -n mochat status mochat
```

期望 Pod 都是 `1/1 Running`：

```text
access-gateway-0
api-service-*
message-service-*
persistence-service-*
call-service-*
```

### 6. 部署 k3s 内部观测栈

Compose 观测栈保留给本机 `local` 进程联调。MoChat 部署在 k3s 时，推荐把观测栈也部署进 k3s，Prometheus 才能通过 Kubernetes discovery 发现 Pod，Promtail 才能采集 Pod 日志。

```bash
kubectl apply -k deploy/observability/kubernetes
```

重新部署 MoChat 时打开 Prometheus annotation：

```bash
helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-dev.yaml \
  -f deploy/helm/mochat/values-local.yaml \
  --set accessGateway.replicaCount=1 \
  --set observability.prometheus.scrape=true \
  --set-string livekit.url="$MOCHAT_LIVEKIT_URL" \
  --set-string livekit.apiKey="$MOCHAT_LIVEKIT_API_KEY" \
  --set-string livekit.apiSecret="$MOCHAT_LIVEKIT_API_SECRET" \
  --set-file accessGatewayTls.certificate=.local/helm/access-gateway-tls/tls.crt \
  --set-file accessGatewayTls.privateKey=.local/helm/access-gateway-tls/tls.key
```

当前 `/prometheus` 已接入 `api-service`、`access-gateway`、`call-service` 这三个有 HTTP listener 的服务。`message-service` 和 `persistence-service` 暂不暴露 HTTP metrics endpoint。Tempo 已部署 OTLP 接收端，但 MoChat 应用还没有接入 tracing exporter 或 Java Agent；`observability.otel.enabled` 目前只注入 OTEL 环境变量，不代表 trace 已上报。

本机查看 Prometheus：

```bash
kubectl -n mochat-observability port-forward svc/prometheus 9090:9090
```

打开 `http://localhost:9090/targets`，应能看到 `mochat-annotated-pods` 或 `mochat-annotated-services` 下的 scrape target。

## 常见问题

### Pod 全部 CrashLoopBackOff

先看日志：

```bash
kubectl -n mochat get pods
kubectl -n mochat logs <pod-name> --previous --tail=120
kubectl -n mochat describe pod <pod-name>
```

如果日志里有：

```text
Unable to connect to host.k3d.internal/<unresolved>:6379
UnknownHostException: host.k3d.internal
```

说明用了 k3d 的 values。重新执行 Helm 命令并加上：

```bash
-f deploy/helm/mochat/values-local.yaml
```

### Secret not found

如果事件里出现：

```text
secret "access-gateway-tls" not found
secret "mochat-livekit" not found
```

不要把 `accessGatewayTls.create=false` 或 `livekit.createSecret=false` 用在没有预建 Secret 的本地环境。重新执行本文 Helm 命令，让 chart 创建 Secret，并通过 `--set-file` 注入 access-gateway TLS，通过 `--set-string livekit.*` 注入 LiveKit 配置。

### ImagePullBackOff

确认镜像在当前 Kubernetes runtime 可见：

```bash
docker images 'localhost/mochat/*'
kubectl -n mochat describe pod <pod-name>
```

Colima/k3s 通常可以直接使用当前 Colima Docker image store。kind/minikube/远端集群不一定能看到本机镜像，需要导入镜像或推送到集群可访问的 registry。

### native-image 太慢或失败

本地演示优先使用 JVM 镜像：

```bash
scripts/build-local-jvm-images.sh
```

需要 native 镜像时：

```bash
NATIVE_CONTAINER_MEMORY=12g scripts/build-local-images.sh
```

## 访问入口

本地 Colima/k3s 演示入口：

```text
IM TCP:              localhost:32000
Call HTTP/WebSocket: localhost:32090
```

对应 Kubernetes Service：

```bash
kubectl -n mochat get svc access-gateway-tcp call-service
```

`access-gateway-tcp` 默认映射 `9000:32000/TCP`，客户端 TCP 长连接访问 `localhost:32000`。`call-service` 在 `values-local.yaml` 下映射 `8090:32090/TCP`，HTTP 调用访问 `http://localhost:32090`，WebSocket 信令访问 `ws://localhost:32090/calls/ws/{sessionId}`。

如果客户端不在本机，而是从其他机器访问 Kubernetes 节点，需要把 `localhost` 换成可从客户端访问到的节点 IP 或负载均衡地址。Colima 的本地节点 IP 通常只适合本机演示，不一定对局域网其他机器开放。

## 远端或其他 Kubernetes

远端集群需要独立 values 文件，不要直接复用本地 values。至少覆盖：

- `global.imageRegistry`
- 各服务 image tag
- `externalDependencies.*`
- PostgreSQL Secret
- access-gateway TLS Secret 或 `--set-file`
- LiveKit Secret 或 values

远端镜像需要 `docker buildx build --push` 推送到集群可访问的 registry。

## legacy kind/kustomize

旧路径仍保留在：

```text
deploy/kubernetes/base
deploy/kubernetes/overlays/kind
```

它覆盖 `api-service`、`message-service`、`persistence-service` 和 `access-gateway`，不覆盖 `call-service`，也不是当前推荐 Helm 部署路径。
