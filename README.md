# MoChat

MoChat 是一个以 IM 场景为核心的 Java 后端项目。当前默认本地拓扑已经切换为四个 dedicated services 共享 PostgreSQL、Redis 和 RocketMQ；`app` 只作为 compatibility shell 保留，用于回滚或兼容模式，不再是默认启动入口。

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

精确的多进程启动命令、双 gateway 示例、TLS 覆盖、回滚步骤和排障说明见 [docs/runbook.md](docs/runbook.md)。

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

gateway 唯一性与路由：

- `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD`
- `MOCHAT_ACCESS_GATEWAY_GRPC_PORT`
- `MOCHAT_ACCESS_GATEWAY_TCP_PORT`
- `mochat.message-service.route.gateway-targets.*`
- `mochat.access-gateway.route.peer-targets.*`

兼容模式：

- `MOCHAT_LEGACY_PERSISTENCE_ENABLED`
- `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED`

说明：

- `message-service` 必须知道所有可投递 gateway 的 target map。
- 每个 `access-gateway` 实例都必须使用不同的 `gateway-pod` / gRPC / TCP 端口。
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
./gradlew :app:run
```

## Where To Read Next

- [docs/runbook.md](docs/runbook.md)
  - 完整启动、双 gateway 示例、TLS、自定义配置、回滚、排障
- [docs/architecture/decompose-im-into-core-services-skeleton.md](docs/architecture/decompose-im-into-core-services-skeleton.md)
  - dedicated services 架构边界
- [docs/mochat-technical-documentation.md](docs/mochat-technical-documentation.md)
  - 当前稳定技术事实总览
- [openspec/specs](openspec/specs)
  - 正式规格
- [openspec/changes/archive/2026-03-12-decompose-im-into-core-services](openspec/changes/archive/2026-03-12-decompose-im-into-core-services)
  - 本次服务拆分变更归档
