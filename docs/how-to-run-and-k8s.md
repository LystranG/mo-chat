# mo-chat 运行与 Kubernetes 部署指南

本文档说明如何在本地运行 mo-chat，以及如何把它部署到 Kubernetes。

## 项目运行入口

mo-chat 是 Java 25 / Gradle 多模块 Micronaut 应用。主应用在 `app` 模块中，启动入口是：

```text
app/src/main/java/com/github/lystran/mochat/Application.java
```

本地 JVM 启动命令是：

```bash
./gradlew :app:run
```

应用启动后会同时启动：

- Micronaut HTTP 服务，默认端口 `8080`
- Netty 聊天 TCP 服务，默认端口 `9000`
- Flyway 数据库迁移，默认启动时执行
- Redis、PostgreSQL、RocketMQ、RustFS 等外部依赖客户端

## 本地前置依赖

需要准备：

- JDK 25，仓库 `mise.toml` 中配置为 `oracle-graalvm-25.0.1`
- Docker Compose 或 Podman Compose
- `jq`，用于运行示例探针
- OpenSSL，可选，仅在需要自定义 TCP TLS 证书时使用

如果使用 mise，可以先安装/切换 Java：

```bash
mise install
mise use
java --version
```

## 本地启动依赖服务

仓库根目录已有 `docker-compose.yml`，包含：

- PostgreSQL 17
- Redis 7.2
- RocketMQ 5.3.2 NameServer
- RocketMQ 5.3.2 Broker
- RustFS

启动依赖：

```bash
docker compose up -d
```

或使用 Podman：

```bash
podman compose up -d
```

查看状态：

```bash
docker compose ps
```

停止依赖：

```bash
docker compose down
```

默认暴露端口：

```text
PostgreSQL: 5432
Redis: 6379
RocketMQ NameServer: 9876
RocketMQ Broker: 10909, 10911, 10912
RustFS API: 9002
RustFS Console: 9001
```

## 本地启动应用

推荐使用以下命令：

```bash
MOCHAT_TCP_IO_URING_PREFERRED=false \
RUSTFS_ENDPOINT=http://localhost:9002 \
./gradlew :app:run
```

说明：

- `MOCHAT_TCP_IO_URING_PREFERRED=false` 可以避免本地非 Linux 或缺少原生传输能力时的排查干扰。
- `RUSTFS_ENDPOINT=http://localhost:9002` 对齐 `docker-compose.yml` 的宿主机端口映射。Compose 中 RustFS 容器内部是 `9000`，宿主机映射为 `9002`。

默认本地配置：

```text
HTTP: 0.0.0.0:8080
聊天 TCP: 0.0.0.0:9000
PostgreSQL: jdbc:postgresql://localhost:5432/mochat
PostgreSQL 用户: mochat
PostgreSQL 密码: mochat
Redis: redis://localhost:6379
RocketMQ NameServer: localhost:9876
RustFS Access Key: rustfsadmin
RustFS Secret Key: rustfsadmin
```

不要随手启用 `MICRONAUT_ENVIRONMENTS=local`。当前 `application-local.yml` 中 PostgreSQL 默认账号是 `postgres/123456`，和 `docker-compose.yml` 的 `mochat/mochat` 不一致。直接使用默认 `application.yml` 更匹配当前 Compose 配置。

## 本地验证

检查端口：

```bash
ss -ltn | rg ':(8080|9000)\b'
```

登录并获取 `sessionId`：

```bash
session_id=$(curl -fsS -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"runbook-probe","publicKey":"runbook-probe-key"}' | jq -r '.sessionId')
```

调用好友列表接口：

```bash
curl -fsS "http://127.0.0.1:8080/friends?sessionId=${session_id}"
```

## 构建与测试

运行 `app` 模块测试：

```bash
./gradlew :app:test
```

运行全量测试：

```bash
./gradlew test
```

普通 JVM 构建仍可用于快速编译校验：

```bash
./gradlew :app:build
```

mo-chat 的交付目标是 GraalVM Native Image。正式部署镜像应基于 native executable，而不是 JVM 分发包。

构建 native executable：

```bash
./gradlew :app:nativeCompile
```

成功后产物位于：

```text
app/build/native/nativeCompile/mo-chat
```

Native Image 构建要求当前 GraalVM/JDK 中存在 `native-image`。如果没有，构建脚本会跳过 `:app:nativeCompile`；此时不能把 `BUILD SUCCESSFUL` 当成已经生成了 native 可执行文件。真实验证需要看到 native image 生成日志，并确认 `app/build/native/nativeCompile/mo-chat` 存在。

在 macOS 上直接执行 `./gradlew :app:nativeCompile` 会生成 macOS native binary，不能直接放进 Linux/Kubernetes 容器运行。Kubernetes 镜像必须使用 Linux native binary，建议在 Linux CI、Linux VM，或 Linux builder 容器中构建。

## 制作 Native Image 容器镜像

当前仓库没有内置 Dockerfile。部署到 Kubernetes 时，镜像应复制 Linux 版 native executable，并使用轻量 Linux 运行时镜像。

### Linux 环境构建

如果你在 Linux 环境中构建：

```bash
./gradlew :app:nativeCompile
```

创建 Dockerfile，例如：

```dockerfile
FROM debian:bookworm-slim
WORKDIR /opt/mo-chat
COPY app/build/native/nativeCompile/mo-chat ./mo-chat
EXPOSE 8080 9000
ENTRYPOINT ["/opt/mo-chat/mo-chat"]
```

构建并推送镜像：

```bash
docker build -t registry.example.com/mo-chat:1.0-SNAPSHOT .
docker push registry.example.com/mo-chat:1.0-SNAPSHOT
```

### macOS 本地部署到 Colima/kind

如果你在 macOS 上开发，不要把 macOS native binary 复制进 Linux 镜像。推荐做法是在 Linux builder 容器或 CI 中构建 Linux native binary，然后再制作镜像。

一种可复用方式是准备一个多阶段 Dockerfile，在 Linux builder 阶段执行 `:app:nativeCompile`，最终镜像只保留 native binary。示例：

```dockerfile
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:nativeCompile --no-daemon

FROM debian:bookworm-slim
WORKDIR /opt/mo-chat
COPY --from=builder /workspace/app/build/native/nativeCompile/mo-chat ./mo-chat
EXPOSE 8080 9000
ENTRYPOINT ["/opt/mo-chat/mo-chat"]
```

构建本地镜像：

```bash
docker build -t mo-chat:1.0-SNAPSHOT .
```

Colima 的 k3s 通常和 Colima Docker runtime 共享镜像环境，可以直接使用 `mo-chat:1.0-SNAPSHOT` 并设置 `imagePullPolicy=IfNotPresent`。kind 需要额外执行：

```bash
kind load docker-image mo-chat:1.0-SNAPSHOT --name mochat
```

## Kubernetes 部署方案

仓库已有 Helm Chart：

```text
deploy/helm/mo-chat-observability
```

推荐部署方式是：

- PostgreSQL、Redis、RocketMQ、RustFS 使用仓库根目录的 `docker-compose.yml` 部署。
- mo-chat 应用、Service、ConfigMap、Secret、可观测组件使用 Helm 部署到 Kubernetes。

这个 Helm Chart 会部署：

- mo-chat Deployment
- mo-chat Service
- ConfigMap
- Secret
- 指向 Docker Compose 依赖的 Kubernetes ExternalName Service
- 可选的 kube-prometheus-stack
- 可选的 Loki
- 可选的 Tempo
- 可选的 ServiceMonitor 和 OpenTelemetry Java Agent 配置

这个 Helm Chart 不部署 PostgreSQL、Redis、RocketMQ、RustFS 本体。部署前先启动 Compose 依赖：

```bash
docker compose up -d
```

或使用 Podman：

```bash
podman compose up -d
```

Chart 默认会创建以下 Kubernetes Service 名，供 mo-chat 在集群内访问外部 Compose 依赖：

```text
postgres:5432
redis:6379
rocketmq-namesrv:9876
rustfs:9002
```

这些 Service 默认是 `ExternalName`，指向：

```text
host.docker.internal
```

这适合 Docker Desktop、部分 kind/minikube 环境。若你的 Kubernetes 集群无法解析或访问 `host.docker.internal`，需要把 `externalDependencies.host` 改成集群节点能访问到的宿主机地址，或者改用集群内部真实服务地址。

默认依赖配置在：

```text
deploy/helm/mo-chat-observability/values.yaml
```

关键默认值：

```yaml
mochat:
  env:
    redisUri: redis://redis:6379
    postgresUrl: jdbc:postgresql://postgres:5432/mochat
    postgresUsername: mochat
    postgresPassword: mochat
    rocketmqNameServer: rocketmq-namesrv:9876
    rustfsEndpoint: http://rustfs:9002

externalDependencies:
  enabled: true
  host: host.docker.internal
```

生产环境不要使用默认密码，应通过自定义 values 或外部 Secret 管理敏感信息。

如果生产环境已有独立 PostgreSQL、Redis、RocketMQ、RustFS，可以关闭这些外部依赖别名，并直接配置真实地址：

```bash
helm upgrade mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --set externalDependencies.enabled=false \
  --set mochat.env.postgresUrl=jdbc:postgresql://prod-postgres.example.com:5432/mochat \
  --set mochat.env.redisUri=redis://prod-redis.example.com:6379 \
  --set mochat.env.rocketmqNameServer=prod-rocketmq-namesrv.example.com:9876 \
  --set mochat.env.rustfsEndpoint=https://prod-s3-compatible.example.com
```

## 安装 Helm 依赖

添加 Helm 仓库：

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add grafana-community https://grafana-community.github.io/helm-charts
helm repo update
```

更新 Chart 依赖：

```bash
helm dependency update deploy/helm/mo-chat-observability
```

渲染检查：

```bash
helm lint deploy/helm/mo-chat-observability
helm template mochat deploy/helm/mo-chat-observability --namespace mochat
```

## 部署到 Kubernetes

先确保 Compose 依赖已经启动：

```bash
docker compose up -d
```

使用已经构建好的 native image 镜像安装：

```bash
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=1.0-SNAPSHOT \
  --set mochat.image.pullPolicy=IfNotPresent
```

如果是在 Colima k3s 上使用本地镜像，可以直接引用本地 tag，并把外部依赖 host 指向 Colima VM 地址。先查看地址：

```bash
colima status
```

然后部署，例如：

```bash
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=mo-chat \
  --set mochat.image.tag=1.0-SNAPSHOT \
  --set mochat.image.pullPolicy=IfNotPresent \
  --set externalDependencies.host=192.168.64.2
```

如果 Kubernetes 不能访问 `host.docker.internal`，安装时指定 Pod 能访问到的宿主机或 VM 地址：

```bash
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=1.0-SNAPSHOT \
  --set mochat.image.pullPolicy=IfNotPresent \
  --set externalDependencies.host=192.168.65.2
```

如果只想先部署应用，不安装 Prometheus、Loki、Tempo：

```bash
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=1.0-SNAPSHOT \
  --set mochat.image.pullPolicy=IfNotPresent \
  --set kube-prometheus-stack.enabled=false \
  --set loki.enabled=false \
  --set tempo.enabled=false \
  --set observability.otel.enabled=false \
  --set observability.prometheus.serviceMonitor.enabled=false
```

升级：

```bash
helm upgrade mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=1.0-SNAPSHOT \
  --set mochat.image.pullPolicy=IfNotPresent
```

卸载：

```bash
helm uninstall mochat --namespace mochat
```

## Kubernetes 验证

查看工作负载：

```bash
kubectl -n mochat get pods
kubectl -n mochat get svc
```

确认外部依赖别名已经创建：

```bash
kubectl -n mochat get svc postgres redis rocketmq-namesrv rustfs
```

查看日志：

```bash
kubectl -n mochat logs \
  -l app.kubernetes.io/name=mo-chat,app.kubernetes.io/component=chat-service \
  -c mo-chat
```

端口转发 HTTP 服务：

```bash
mochat_service=$(kubectl -n mochat get svc \
  -l app.kubernetes.io/name=mo-chat,app.kubernetes.io/component=chat-service \
  -o jsonpath='{.items[0].metadata.name}')

kubectl -n mochat port-forward "svc/${mochat_service}" 8080:8080
```

检查健康端点：

```bash
curl -fsS http://127.0.0.1:8080/health
```

检查 Prometheus 指标端点：

```bash
curl -fsS http://127.0.0.1:8080/prometheus
```

Kubernetes 下 Chart 会设置：

```text
MICRONAUT_ENVIRONMENTS=k8s
```

因此会加载：

```text
app/src/main/resources/application-k8s.yml
```

该配置启用 `/health` 和 `/prometheus` 管理端点。

## 暴露服务

Chart 默认 Service 类型是 `ClusterIP`：

```yaml
mochat:
  service:
    type: ClusterIP
    httpPort: 8080
    tcpPort: 9000
```

如果需要从集群外访问，可以改成 `LoadBalancer`：

```bash
helm upgrade mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --set mochat.service.type=LoadBalancer
```

也可以保持 `ClusterIP`，在集群入口层分别为 HTTP `8080` 和聊天 TCP `9000` 配置 Ingress/Gateway/LoadBalancer。HTTP Ingress 只适合 HTTP 端口；聊天 TCP 端口需要使用支持 TCP 转发的入口方案。

## 常见问题

### 应用启动时报 PostgreSQL 连接失败

检查 `MOCHAT_POSTGRES_URL`、`MOCHAT_POSTGRES_USERNAME`、`MOCHAT_POSTGRES_PASSWORD` 是否和实际数据库一致。

本地默认应为：

```text
jdbc:postgresql://localhost:5432/mochat
mochat
mochat
```

Kubernetes 默认应能解析 Service：

```text
postgres.mochat.svc
```

或同 namespace 下的短名：

```text
postgres
```

### 应用启动时报 Redis 连接失败

检查 `MOCHAT_REDIS_URI`。

本地默认：

```text
redis://localhost:6379
```

Kubernetes 默认：

```text
redis://redis:6379
```

如果 Redis 实际跑在 Docker Compose 中，确认 `externalDependencies.host` 指向的宿主机地址能从 Kubernetes Pod 访问。

### RocketMQ 消费者启动失败

检查 NameServer 地址：

```text
MOCHAT_ROCKETMQ_NAME_SERVER
```

如果只想临时启动 HTTP 服务排查其他问题，可以关闭消费者：

```bash
MOCHAT_ROCKETMQ_CONSUMER_ENABLED=false ./gradlew :app:run
```

Kubernetes 中可通过 Helm 设置：

```bash
--set mochat.env.rocketmqConsumerEnabled=false
```

### RustFS 媒体接口失败

本地 Compose 中 RustFS 容器内部端口是 `9000`，宿主机端口是 `9002`。本地运行应用时应设置：

```bash
RUSTFS_ENDPOINT=http://localhost:9002
```

Kubernetes 中默认通过 ExternalName Service 访问 Compose 暴露端口：

```text
http://rustfs:9002
```

### TCP TLS 配置

聊天 TCP 默认启用 TLS，并支持自签名证书：

```text
MOCHAT_TLS_ENABLED=true
MOCHAT_TLS_SELF_SIGNED=true
```

如果要使用自己的证书，需要同时配置证书链和私钥：

```text
MOCHAT_TLS_CERT_PATH
MOCHAT_TLS_KEY_PATH
```

只配置其中一个会启动失败。
