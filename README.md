# MoChat

MoChat 是一个以 IM 场景为核心的 Java 后端项目。当前默认本地拓扑已经切换为四个 dedicated services 共享 PostgreSQL、Redis 和 RocketMQ；`app` 只作为 compatibility shell 保留，用于回滚或兼容模式，不再是默认启动入口。

当前 worktree 的 Kubernetes 资产位于 `deploy/kubernetes/base` 与 `deploy/kubernetes/overlays/kind`。Kubernetes-first 的部署契约、本地 `kind` 验证路径、ConfigMap/Secret 约定以及回滚到静态寻址模式的办法见 [docs/runbook.md](docs/runbook.md)。

## Architecture At A Glance

默认本地拓扑：

- `api-service`: 登录、session authority、社交关系、历史查询
- `message-service`: 消息摄入、幂等、sender ACK、在线投递编排、离线回退
- `access-gateway`: TCP bind、心跳、在线路由 ownership、定点投递
- `persistence-service`: MQ 消费、持久化、会话推进、post-commit 更新

共享基础设施：

- PostgreSQL
- Redis
- RocketMQ

核心模块：

- `access-gateway-app`
- `api-service-app`
- `message-service-app`
- `persistence-service-app`
- `service-runtime`
- `common`
- `protocol`
- `connection-module`
- `logic-module`
- `message-module`
- `persistence-module`

## Prerequisites

- JDK 25
- Podman with compose support: `podman compose`
- OpenSSL
  - 仅当你要覆盖默认自签名 TLS 证书时需要

## Quick Start

1. 启动共享基础设施：

```bash
podman compose up -d
```

2. 在独立终端中启动核心服务：

```bash
./gradlew :api-service-app:run
./gradlew :message-service-app:run
./gradlew :persistence-service-app:run
./gradlew :access-gateway-app:run
```

3. 如果你要验证默认推荐的双 gateway 本地拓扑，再额外启动第二个 `access-gateway` 实例，并按 runbook 配置不同的 `gateway-pod`、gRPC 端口和 TCP 端口。

精确的多进程启动命令、Kubernetes 资源结构、`kind` 验证步骤、ConfigMap/Secret 约定、TLS 覆盖、回滚步骤和排障说明见 [docs/runbook.md](docs/runbook.md)。

## Default Ports

| Component | Default ports |
| --- | --- |
| `api-service` | HTTP `8080`, gRPC `19091` |
| `message-service` | gRPC `19092` |
| `access-gateway-a` | TCP `9000`, gRPC `19093` |
| `access-gateway-b` | TCP `9001`, gRPC `19094` |
| `persistence-service` | 无公开 HTTP/TCP listener |
| PostgreSQL | `5432` |
| Redis | `6379` |
| RocketMQ NameServer | `9876` |
| RocketMQ Broker | `10909`, `10911`, `10912` |

## Key Environment Variables

基础设施：

- `MOCHAT_REDIS_URI`
- `MOCHAT_POSTGRES_URL`
- `MOCHAT_POSTGRES_USERNAME`
- `MOCHAT_POSTGRES_PASSWORD`
- `MOCHAT_ROCKETMQ_NAME_SERVER`
- `MOCHAT_ROCKETMQ_TOPIC`

服务寻址：

- `MOCHAT_API_SERVICE_GRPC_ADDRESS`
- `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS`

Kubernetes 运行时身份与发现：

- `MOCHAT_RUNTIME_POD_NAME`
- `MOCHAT_RUNTIME_POD_NAMESPACE`
- `MOCHAT_GATEWAY_IDENTITY_MODE`
- `MOCHAT_GATEWAY_DISCOVERY_MODE`
- `MOCHAT_GATEWAY_HEADLESS_SERVICE`
- `MOCHAT_GATEWAY_DISCOVERY_NAMESPACE`
- `MOCHAT_GATEWAY_DISCOVERY_GRPC_PORT`

静态寻址回滚兼容项：

- `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD`
- `MOCHAT_ACCESS_GATEWAY_GRPC_PORT`
- `MOCHAT_ACCESS_GATEWAY_TCP_PORT`
- `mochat.message-service.route.gateway-targets.*`
- `mochat.access-gateway.route.peer-targets.*`

兼容模式：

- `MOCHAT_LEGACY_PERSISTENCE_ENABLED`
- `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED`

说明：

- Kubernetes 形态下，`access-gateway` 默认通过 `MOCHAT_RUNTIME_POD_NAME` / `MOCHAT_RUNTIME_POD_NAMESPACE` 取得 Pod 身份，`message-service` 与 `access-gateway` 通过 Service DNS / Pod DNS 寻址，这两组静态 target map 应保持为空。
- 当前本地静态寻址回滚模式下，`message-service` 仍然需要 `gateway-targets`，每个 `access-gateway` 仍然需要不同的 `gateway-pod` / gRPC / TCP 端口与 `peer-targets`。
- `app` 仅用于 compatibility shell，不是默认开发路径。

## Common Commands

```bash
podman compose up -d
podman compose down
./gradlew test
./gradlew :api-service-app:run
./gradlew :message-service-app:run
./gradlew :persistence-service-app:run
./gradlew :access-gateway-app:run
bash deploy/kubernetes/overlays/kind/prepare-local-inputs.sh
bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh
GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh
./gradlew :app:run
```

## Where To Read Next

- [docs/runbook.md](docs/runbook.md)
  - Kubernetes-first 部署契约、`kind` 验证、ConfigMap/Secret、静态寻址回滚、排障
- [docs/architecture/decompose-im-into-core-services-skeleton.md](docs/architecture/decompose-im-into-core-services-skeleton.md)
  - dedicated services 架构边界
- [docs/mochat-technical-documentation.md](docs/mochat-technical-documentation.md)
  - 当前稳定技术事实总览
- [openspec/specs](openspec/specs)
  - 正式规格
- [openspec/changes/archive/2026-03-12-decompose-im-into-core-services](openspec/changes/archive/2026-03-12-decompose-im-into-core-services)
  - 本次服务拆分变更归档
