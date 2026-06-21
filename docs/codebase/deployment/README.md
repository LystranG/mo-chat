# Deployment Codebase Memory

## 职责

`deployment` 边界定义当前默认四服务拓扑的运行、镜像、Kubernetes 资源、本地 kind 验证和共享基础设施约定：

- 默认服务：`api-service`、`message-service`、`access-gateway`、`persistence-service`
- 共享基础设施：PostgreSQL、Redis、RocketMQ
- Kubernetes base 和 kind overlay
- Dockerfile 和镜像构建方式
- runtime ConfigMap / Secret / 环境变量
- 本地 kind 验证脚本

## 非职责

- 当前不包含生产 overlay；`docs/runbook.md` 明确没有 `deploy/kubernetes/overlays/prod`。
- 不做 database-per-service。
- 不做 schema rollback 或数据层回滚。
- 不把 PostgreSQL、Redis、RocketMQ 放进 Kubernetes manifest 管理；它们当前是集群外依赖。
- 不用 `app` 作为默认部署入口。

## 主要代码路径

- Kubernetes base：`deploy/kubernetes/base/shared-runtime.yaml`、`runtime-secrets.yaml`、`api-service.yaml`、`message-service.yaml`、`persistence-service.yaml`、`access-gateway.yaml`、`kustomization.yaml`
- kind overlay：`deploy/kubernetes/overlays/kind/kustomization.yaml`、`prepare-local-inputs.sh`、`verify-minimal-topology.sh`、`verify-routing-and-drain.sh`
- 本地基础设施：`docker-compose.yml`
- Dockerfile：`access-gateway-app/Dockerfile`、`api-service-app/Dockerfile`、`message-service-app/Dockerfile`、`persistence-service-app/Dockerfile`
- 服务配置：`access-gateway-app/src/main/resources/application.yml`、`api-service-app/src/main/resources/application.yml`、`message-service-app/src/main/resources/application.yml`、`persistence-service-app/src/main/resources/application.yml`
- Kubernetes manifest 测试：`service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/**`

## 核心数据流和交互

- `api-service`：Deployment + ClusterIP Service，HTTP `8080`，gRPC `19091`。
- `message-service`：Deployment + ClusterIP Service，gRPC `19092`。
- `persistence-service`：Deployment，无 Service，消费 RocketMQ 并写 PostgreSQL。
- `access-gateway`：StatefulSet，`access-gateway-headless` 用于 Pod DNS/gRPC，`access-gateway-tcp` NodePort 暴露 TCP `9000`。
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

- `persistence-service-app/Dockerfile` 使用 `:installDist` + JRE，不是 native image；其他三个 service Dockerfile 使用 `nativeCompile` + distroless。
- `deploy/kubernetes/base/persistence-service.yaml` 没有 Service，这与 runbook 一致；后续若加探针 sidecar 或入站 API 会改变边界。
- `api-service.yaml` 同时从 `mochat-runtime-config` 引入并显式设置 `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS`，存在重复配置。
- `docker-compose.yml` 的 compose name 是 `ddd-demo`，kind 脚本默认依赖 `MOCHAT_KIND_COMPOSE_PROJECT:-ddd-demo`。

## 配置和运行入口

本地基础设施：

```bash
podman compose up -d
podman compose down
```

本地四服务：

```bash
./gradlew :api-service-app:run
./gradlew :message-service-app:run
./gradlew :persistence-service-app:run
./gradlew :access-gateway-app:run
```

Kubernetes/kind：

```bash
bash deploy/kubernetes/overlays/kind/prepare-local-inputs.sh
bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh
```

镜像构建：

```bash
podman build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
podman build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
podman build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
podman build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
```

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
- 把 PostgreSQL/Redis/RocketMQ 从集群外改为集群内管理。
- 改变 gateway StatefulSet/headless Service/Pod DNS identity 约定。
- 改变回滚方式或 `app` 是否为默认入口。

