# Deployment Codebase Memory

## 职责

`deployment` 边界定义当前默认 dedicated services 的运行、镜像、Kubernetes 资源、本地 kind 验证和共享基础设施约定：

- 默认源码服务：`api-service`、`message-service`、`access-gateway`、`persistence-service`、`call-service`
- 旧 `deploy/kubernetes` kustomize/kind manifest 仍只覆盖 `api-service`、`message-service`、`access-gateway`、`persistence-service`
- 新 Helm chart `deploy/helm/mochat` 覆盖五个 dedicated services，包含 `call-service`
- 共享基础设施：PostgreSQL、Redis、RocketMQ
- 本地 Compose 观测栈和 k3s 内部观测栈：Prometheus、Loki、Promtail、Tempo、Alertmanager
- 外部音视频基础设施：LiveKit
- 外部 AIOps 接入示例
- Kubernetes base 和 kind overlay
- Dockerfile 和镜像构建方式
- runtime ConfigMap / Secret / 环境变量
- 本地 kind 验证脚本

## 环境语义

- `local`：直接运行五个 Gradle 进程；推荐入口是 `scripts/run-local.sh`；IDEA/手动 Gradle 入口主要依赖各 app 的 `application-local.yml` 字面量本机默认值，脚本入口仍会加载根目录 `.env`。
- `dev`：本地 k3s Helm 部署；推荐 Helm chart 是 `deploy/helm/mochat`。k3d 可直接使用 `values-dev.yaml`；Colima/k3s 需叠加 `values-local.yaml`，把外部依赖地址切到 `host.docker.internal`。服务发现使用 Kubernetes Service/headless Service。
- `prod`：当前只预留命名，尚未交付生产 values 或 overlay。

## 非职责

- 当前不包含生产 overlay；`docs/runbook.md` 明确没有 `deploy/kubernetes/overlays/prod`。
- 不做 database-per-service。
- 不做 schema rollback 或数据层回滚。
- 不把 PostgreSQL、Redis、RocketMQ 放进 Kubernetes manifest 管理；它们当前是集群外依赖。
- 当前不部署 LiveKit 服务本体；Helm 只创建或引用 `mochat-livekit` Secret，向 `call-service` 注入 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。
- 不在本项目内实现 AIOps 服务；本项目只提供被监控系统端点、标签和配置示例。
- 不用 `app` 作为默认部署入口。

## 主要代码路径

- Kubernetes base：`deploy/kubernetes/base/shared-runtime.yaml`、`runtime-secrets.yaml`、`api-service.yaml`、`message-service.yaml`、`persistence-service.yaml`、`access-gateway.yaml`、`kustomization.yaml`
- kind overlay：`deploy/kubernetes/overlays/kind/kustomization.yaml`、`prepare-local-inputs.sh`、`verify-minimal-topology.sh`、`verify-routing-and-drain.sh`
- Helm chart：`deploy/helm/mochat/**`
- 本地 Compose 观测栈：`deploy/observability/docker-compose.yml`、`deploy/observability/{prometheus,loki,promtail,tempo,alertmanager}/**`
- k3s 内部观测栈：`deploy/observability/kubernetes/**`
- 本地基础设施：`docker-compose.yml`
- Dockerfile：`access-gateway-app/Dockerfile`、`api-service-app/Dockerfile`、`message-service-app/Dockerfile`、`persistence-service-app/Dockerfile`
- call-service 镜像：`call-service-app/Dockerfile`
- 服务配置：`access-gateway-app/src/main/resources/application.yml`、`api-service-app/src/main/resources/application.yml`、`message-service-app/src/main/resources/application.yml`、`persistence-service-app/src/main/resources/application.yml`、`call-service-app/src/main/resources/application.yml`
- Kubernetes manifest 测试：`service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/**`

## 核心数据流和交互

- `api-service`：Deployment + ClusterIP Service，HTTP `8080`，gRPC `19091`。
- `message-service`：Deployment + ClusterIP Service，gRPC `19092`。
- `persistence-service`：Deployment，无 Service，消费 RocketMQ 并写 PostgreSQL。
- `access-gateway`：StatefulSet，`access-gateway-headless` 用于 Pod DNS/gRPC，`access-gateway-tcp` NodePort 暴露 TCP `9000`。
- `call-service`：Micronaut HTTP/WebSocket app，默认 HTTP `8090`；`call-service-app/Dockerfile` 当前使用 `installDist` + JRE 镜像，Helm chart 已模板化 Deployment + Service，默认单副本。默认 Service 是 `ClusterIP`；Colima/k3s `values-local.yaml` 会改为 NodePort `32090` 供外部演示客户端访问。
- `mochat-runtime-config` 放集群内发现：
  - `MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091`
  - `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092`
  - `MOCHAT_GATEWAY_HEADLESS_SERVICE=access-gateway-headless`
- `mochat-external-dependencies` 放外部依赖地址：
  - `MOCHAT_REDIS_URI`
  - `MOCHAT_POSTGRES_URL`
  - `MOCHAT_ROCKETMQ_NAME_SERVER`
  - `MOCHAT_ROCKETMQ_TOPIC`
- `mochat-external-dependency-secrets` 放 PostgreSQL 凭据。
- `access-gateway-tls` Secret 挂载到 `/var/run/mochat/tls`。
- `MOCHAT_RUNTIME_POD_NAME` / `MOCHAT_RUNTIME_POD_NAMESPACE` 通过 Kubernetes fieldRef 注入。

当前注意点：

- 推荐部署路径新增 `deploy/helm/mochat`，用于部署 `api-service`、`message-service`、`persistence-service`、`access-gateway`、`call-service`。
- 本地 Compose 观测栈保留在 `deploy/observability/docker-compose.yml`，主要用于本机 `local` 进程联调。
- k3s 内部观测栈新增 `deploy/observability/kubernetes`，使用 plain YAML + kustomization 管理 `mochat-observability` namespace 内的 Prometheus、Alertmanager、Loki、Promtail、Tempo，供集群内 MoChat Pod 访问。
- 命令文档默认使用 Docker CLI；Podman 不再是 runbook 推荐主路径的默认命令。
- AIOps 是外部服务，本项目只提供 metrics、logs、trace 预留、Alertmanager webhook 示例、Kubernetes labels 和 `projects.yaml.example`。
- `deploy/kubernetes/base` 和 `deploy/kubernetes/overlays/kind` 保留为旧版 Podman-based kustomize/kind 验证路径；脚本当前仍调用 Podman，不是 Helm 推荐路径的一部分。
- `persistence-service-app/Dockerfile` 和 `call-service-app/Dockerfile` 使用 `:installDist` + JRE，不是 native image；`access-gateway`、`api-service`、`message-service` Dockerfile 使用 `nativeCompile` + distroless。
- 五个服务 Dockerfile 都使用 BuildKit `RUN --mount=type=cache,target=/workspace/.gradle-cache` 和 `GRADLE_USER_HOME=/workspace/.gradle-cache` 缓存 Gradle wrapper、distribution 和依赖下载；推荐继续使用 `docker buildx build`。
- 旧 kind 脚本未覆盖 call-service；Helm 路径 `deploy/helm/mochat` 覆盖 call-service。
- `deploy/kubernetes/base/persistence-service.yaml` 没有 Service，这与 runbook 一致；后续若加探针 sidecar 或入站 API 会改变边界。
- `api-service.yaml` 同时从 `mochat-runtime-config` 引入并显式设置 `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS`，存在重复配置。
- `docker-compose.yml` 的 compose name 是 `mochat`，kind 脚本默认依赖 `MOCHAT_KIND_COMPOSE_PROJECT:-mochat`。
- `docker-compose.yml` 的 RocketMQ broker 通过 `docker/rocketmq/broker.conf` 显式设置 `brokerIP1=host.docker.internal`。宿主机 native 进程和本地 k3s Pod 都会先连 `host.docker.internal:9876` 的 NameServer，再按 NameServer 返回的 broker route 连接 `host.docker.internal:10911`；不要让 broker 自动注册容器内网 IP。
- `call-service-app/src/main/resources/application.yml` 不含 LiveKit URL/API key/API secret 默认值；部署时必须通过 Secret 注入 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。根目录 `.env` 不会被 k3s 自动读取，本地 Helm 演示需要先 `source .env`，再通过 `--set-string livekit.*` 创建 `mochat-livekit` Secret，或预建同名 Secret。
- `values-dev.yaml` 不创建 Namespace；推荐通过 Helm CLI `--create-namespace` 创建 namespace，避免 chart 内 `Namespace` 与 Helm CLI 创建的 namespace ownership 冲突。Colima/k3s 本地演示命令应同时使用 `-f deploy/helm/mochat/values-dev.yaml -f deploy/helm/mochat/values-local.yaml --set accessGateway.replicaCount=1`。
- `observability.prometheus.scrape` 默认关闭；开启后为 `api-service`、`access-gateway`、`call-service` 渲染 Prometheus annotations。这三个 HTTP 服务已通过 Micronaut management/micrometer 暴露 `/prometheus`；`message-service` 和 `persistence-service` 暂无 HTTP metrics endpoint。
- `observability.otel.enabled` 默认关闭；开启后只注入 OTEL 环境变量，应用侧尚未接入 tracing exporter 或 Java Agent，不能把它写成已完整上报 trace。

## 配置和运行入口

本地基础设施：

启动：

```bash
docker compose up -d
cp deploy/observability/alertmanager/secrets/aiops-token.example deploy/observability/alertmanager/secrets/aiops-token
docker compose -f deploy/observability/docker-compose.yml up -d
```

停止：

```bash
docker compose -f deploy/observability/docker-compose.yml down
docker compose down
```

k3s 内部观测栈：

```bash
kubectl apply -k deploy/observability/kubernetes
```

本地 dedicated services：

```bash
scripts/run-local.sh start
scripts/run-local.sh status
scripts/run-local.sh stop
```

本地 k3s/Colima Helm 演示环境：

```bash
scripts/run-local-k8s.sh start
scripts/run-local-k8s.sh status
scripts/run-local-k8s.sh stop
scripts/run-local-k8s.sh restart
scripts/run-local-k8s.sh verify-native
```

`run-local-k8s.sh start` 默认构建 JVM 镜像、确保 access-gateway TLS、执行 Helm upgrade，并部署 `deploy/observability/kubernetes`。需要宿主机 native 编译时使用 `scripts/run-local-k8s.sh --native start`，它保持 `scripts/build-local-images.sh` 的默认语义。需要部署到本地 k3s 的 Linux native 容器镜像时使用 `scripts/run-local-k8s.sh --native-docker start`，它会调用 `scripts/build-local-images.sh --docker-compile`，默认使用 `dev-native` tag，并在 Helm upgrade 后重启 Pod。部署后可用 `scripts/run-local-k8s.sh verify-native` 检查 `api-service`、`message-service`、`access-gateway` 是否仍在 JVM 上运行；`call-service` 和 `persistence-service` 当前仍预期是 JVM。`stop` 默认卸载 MoChat Helm release 并删除 k3s 观测栈，不管理根目录 Docker Compose 基础设施。可用 `--skip-compile` 跳过 Gradle 编译和 Docker 打包，也可用 `MOCHAT_K8S_BUILD_IMAGES=0` 达到同样效果；用 `MOCHAT_K8S_OBSERVABILITY=0` 跳过观测栈操作。

如果手动运行单个 Gradle 进程，必须显式设置 `MICRONAUT_ENVIRONMENTS=local`；`application-local.yml` 已内置本机 Redis、PostgreSQL、RocketMQ、gRPC 和 LiveKit 占位默认值：

```bash
MICRONAUT_ENVIRONMENTS=local ./gradlew :api-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :message-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :persistence-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :access-gateway-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :call-service-app:run
```

旧 Podman-based Kubernetes/kind fallback：

下面入口是旧 kustomize/kind 验证路径，不是 Helm 推荐部署路径；脚本当前仍按 Podman-based kind 环境维护。

```bash
bash deploy/kubernetes/overlays/kind/prepare-local-inputs.sh
bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh
```

镜像构建：

```bash
scripts/build-local-images.sh
```

默认构建并加载 `localhost/mochat/{access-gateway,api-service,message-service,persistence-service,call-service}:dev`。可通过 `IMAGE_REGISTRY`、`IMAGE_NAMESPACE`、`IMAGE_TAG`、`DOCKER_BUILDER` 覆盖默认值。

`scripts/build-local-images.sh` 默认使用 `LOCAL_IMAGE_MODE=native-host`，即保持宿主机 native 编译语义；native 模式只覆盖 `access-gateway`、`api-service`、`message-service`，`call-service` 和 `persistence-service` 仍使用 JVM 分发包。需要生成本地 k3s 可运行的 Linux native 容器镜像时，使用 `scripts/build-local-images.sh --docker-compile`，它会切到 `LOCAL_IMAGE_MODE=native-container`，用 Linux GraalVM builder 容器编译 native 产物并打包镜像。`LOCAL_IMAGE_MODE=jvm` 可改为宿主机 `installDist` + JRE 镜像。

如果只需要本地 k3s 快速演示，不想触发 native-image 编译，使用 JVM 镜像入口：

```bash
scripts/build-local-jvm-images.sh
```

该入口固定使用 `LOCAL_IMAGE_MODE=jvm`，只在宿主机执行五个 app 的 `installDist`，再复制 JVM 分发包进 JRE 镜像。

Helm 部署：

```bash
mkdir -p .local/helm/access-gateway-tls
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -subj "/CN=localhost" \
  -keyout .local/helm/access-gateway-tls/tls.key \
  -out .local/helm/access-gateway-tls/tls.crt

helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-dev.yaml \
  -f deploy/helm/mochat/values-local.yaml \
  --set accessGateway.replicaCount=1 \
  --set-file accessGatewayTls.certificate=.local/helm/access-gateway-tls/tls.crt \
  --set-file accessGatewayTls.privateKey=.local/helm/access-gateway-tls/tls.key
```

`accessGatewayTls.certificate` / `accessGatewayTls.privateKey` 是 access-gateway TLS 启动必需配置，不能让 chart 创建空 `access-gateway-tls` Secret。使用预建 TLS Secret 时设置 `accessGatewayTls.create=false` 和 `accessGatewayTls.secretName=access-gateway-tls`。

通话功能还需要 `mochat-livekit` Secret 或 `livekit.url`、`livekit.apiKey`、`livekit.apiSecret` values；只验证非通话链路时可以暂时保留 LiveKit 空值。使用预建 LiveKit Secret 时设置 `livekit.createSecret=false` 和 `livekit.secretName=mochat-livekit`。根目录 `.env` 只对本机 shell 有效，不会自动进入 Kubernetes。

旧 kind 脚本未覆盖 call-service；Helm 路径覆盖 call-service。不要把旧 kind runbook 写成已验证 call-service，除非先补齐对应脚本和测试。

## 测试入口

- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/KubernetesWorkloadManifestContractTest.java`
- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/KubernetesAccessGatewayManifestsTest.java`
- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/KubernetesKindOverlayAssetsTest.java`
- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/KubernetesKindOverlayContractTest.java`
- `deploy/kubernetes/overlays/kind/verify-minimal-topology.sh`
- `deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh`

## 变更时必须同步更新

- 修改 Kubernetes workload 类型、Service 类型、端口、探针、Secret/ConfigMap 名称。
- 新增 prod overlay 或改变 kind overlay 入口。
- 修改 Dockerfile 构建方式、镜像名、native/JVM 运行方式。
- 修改默认端口或 `MOCHAT_*` 环境变量。
- 给 `call-service` 新增 Dockerfile、Kubernetes workload、Service、Secret、探针或 kind 验证入口。
- 修改 LiveKit Secret 管理方式或 `MOCHAT_LIVEKIT_*` 环境变量。
- 把 PostgreSQL/Redis/RocketMQ 从集群外改为集群内管理。
- 改变 gateway StatefulSet/headless Service/Pod DNS identity 约定。
- 改变回滚方式或 `app` 是否为默认入口。
