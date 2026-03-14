# 第一阶段 Kubernetes 部署操作手册

这份 runbook 现在把 Kubernetes 原生部署视为拆分后 MoChat 拓扑的主要部署方式，涉及的服务包括 `api-service`、`message-service`、`persistence-service` 和 `access-gateway`。

截至 2026-03-14，当前范围如下：

- 仓库里已经有按服务拆分的 Dockerfile、独立的服务运行时、运行时拓扑抽象，以及仓库自带的 Kubernetes 资源，位置在 `deploy/kubernetes/base` 和 `deploy/kubernetes/overlays/kind`。
- 当前仓库已经提供的本地验证入口是 `bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh` 和 `GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh`。
- 本文档记录的是当前 worktree 里真实存在的资源结构、已经验证过的本地 `kind` 路径、ConfigMap / Secret 约定、集群外基础设施约束，以及回滚到当前静态地址运行时的做法。

## 部署目标

- `api-service`、`message-service` 和 `persistence-service` 作为可独立扩缩容的 Kubernetes 工作负载运行。
- `access-gateway` 使用 StatefulSet 运行，这样每个网关副本都能保留稳定的 `gatewayPod` 身份。
- 集群内的同步调用通过 Kubernetes Service DNS 或 headless Service 的 Pod DNS 解析。
- PostgreSQL、Redis 和 RocketMQ 在这一阶段仍然放在集群外。
- 静态的 `gateway-targets` 和 `peer-targets` 只保留为兼容兜底方案，不再是 Kubernetes 下的主要生产约定。

## 仓库当前交付的 Kubernetes 资源形式

当前仓库自带的 Kubernetes 打包方式是 `kustomize`：

- `deploy/kubernetes/base`
- `deploy/kubernetes/overlays/kind`

当前 worktree 里还没有 `deploy/kubernetes/overlays/prod` 这个 overlay。不要仅凭这份文档就推断仓库已经提供生产环境 overlay。

## 当前 Kubernetes 资源结构

| 运行时 | 工作负载类型 | Service 形态 | 主要端口 | 服务发现约定 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `api-service` | `Deployment` | `ClusterIP Service` | HTTP `8080`，gRPC `19091` | 命名空间内使用 `api-service:19091` | 负责 session 权威、登录、社交关系、历史消息查询 |
| `message-service` | `Deployment` | `ClusterIP Service` | gRPC `19092` | 命名空间内使用 `message-service:19092` | 负责消息写入、ACK 编排、在线投递和离线兜底 |
| `persistence-service` | `Deployment` | 目前还不需要公开 Service | 无 | 当前无 | MQ 消费 / 持久化工作进程；只有在它暴露入站 API，或需要仅承载探针的 sidecar 时，再补 Service |
| `access-gateway` | `StatefulSet` | 一个 headless Service 用于 Pod DNS，另一个独立 TCP ingress Service 用于外部接入 | TCP `9000`，gRPC `19093` | `<pod>.access-gateway-headless.<namespace>.svc.cluster.local:19093` | 提供稳定 owner 身份、定向投递和先 drain 再滚动发布 |

当前仓库里已有的 Kubernetes 对象：

- `Namespace`: `mochat`
- `ConfigMap`: `mochat-runtime-config`
- `ConfigMap`: `mochat-external-dependencies`
- `Secret`: `mochat-external-dependency-secrets`
- `Secret`: `access-gateway-tls`
- `Deployment`: `api-service`、`message-service`、`persistence-service`
- `Service`: `api-service`、`message-service`
- `StatefulSet`: `access-gateway`
- `clusterIP: None` 的 `Service`: `access-gateway-headless`
- 面向客户端 TCP 入口的 `Service`: `access-gateway-tcp`

### Access Gateway 命名约定

`access-gateway` 通过 StatefulSet 的 Pod 名字保持稳定身份：

- `access-gateway-0`
- `access-gateway-1`
- `access-gateway-2`

标准的 owner 定向 gRPC 目标地址，是由 Pod 名加 headless Service 推导出来的：

```text
<gatewayPod>.access-gateway-headless.mochat.svc.cluster.local:19093
```

例如：

- `access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:19093`
- `access-gateway-1.access-gateway-headless.mochat.svc.cluster.local:19093`

所以在 Kubernetes 里，Redis 里的在线路由 owner 应该直接保存 Pod 身份本身，比如 `gatewayPod=access-gateway-0`，而不是手写别名，例如 `gateway-a`。

## 配置归属规则

当前配置分成五类。

### 1. ConfigMap：集群内运行时发现

`mochat-runtime-config` 当前只放非敏感、用于集群内发现的默认值：

| Key | 为什么放在 ConfigMap |
| --- | --- |
| `MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091` | 稳定的集群内 Service DNS |
| `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092` | 稳定的集群内 Service DNS |
| `MOCHAT_GATEWAY_HEADLESS_SERVICE=access-gateway-headless` | 用于 Pod DNS 解析的稳定 headless Service 名称 |

### 2. ConfigMap：集群外依赖地址

`mochat-external-dependencies` 保存非敏感的外部依赖地址，这些值会被 `kind` overlay 用 `.local/external-dependencies.env` 覆盖：

| Key | 为什么放在 ConfigMap |
| --- | --- |
| `MOCHAT_REDIS_URI=redis://<external-host>:6379` | 外部地址本身不含密钥 |
| `MOCHAT_POSTGRES_URL=jdbc:postgresql://<external-host>:5432/mochat` | 外部地址本身不是凭据 |
| `MOCHAT_ROCKETMQ_NAME_SERVER=<external-host>:9876` | 外部地址 |
| `MOCHAT_ROCKETMQ_TOPIC=mochat.messages` | 非敏感 topic 名称 |

### 3. Secret：凭据和 TLS 材料

敏感值当前分散在 `mochat-external-dependency-secrets` 和 `access-gateway-tls` 里：

| Key / material | 为什么放在 Secret |
| --- | --- |
| `MOCHAT_POSTGRES_USERNAME` | 凭据 |
| `MOCHAT_POSTGRES_PASSWORD` | 凭据 |
| `MOCHAT_REDIS_URI`，当其中带有认证信息或 TLS 参数时 | 可能包含密码或认证材料 |
| 以后如果启用 RocketMQ 用户名 / 密码 | 凭据 |
| gateway TLS 证书和私钥 | 敏感密钥材料 |

对于 gateway TLS，当前 manifest 会把 `access-gateway-tls` 挂载到 `/var/run/mochat/tls`，并设置：

- 把 Secret volume 挂载到固定路径，例如 `/var/run/mochat/tls`
- 设置 `MOCHAT_ACCESS_GATEWAY_TLS_CERTIFICATE_PATH=/var/run/mochat/tls/tls.crt`
- 设置 `MOCHAT_ACCESS_GATEWAY_TLS_PRIVATE_KEY_PATH=/var/run/mochat/tls/tls.key`

### 4. Pod 元数据：身份由 Kubernetes 提供

当前 Kubernetes 原生 owner 身份来自 Pod 元数据中的 `MOCHAT_RUNTIME_POD_NAME` / `MOCHAT_RUNTIME_POD_NAMESPACE`，而不是手写的每副本 `gatewayPod`。

当前注入的环境变量如下：

```yaml
env:
  - name: MOCHAT_RUNTIME_POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: MOCHAT_RUNTIME_POD_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace
```

`access-gateway-app/src/main/resources/application.yml` 里仍然保留 `mochat.access-gateway.route.gateway-pod`，并通过 `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD` / `${HOSTNAME}` 作为本地静态模式下的兜底。

### 5. 仅为兼容保留的静态目标映射

下面这些 key 在 Kubernetes manifest 里应该保持为空，它们只用于兼容兜底：

- `mochat.message-service.route.gateway-targets.*`
- `mochat.access-gateway.route.peer-targets.*`

当回滚到当前本地静态地址拓扑时，仍然需要这些配置。

## 集群外基础设施前置条件

第一版 Kubernetes 部署仍然把 PostgreSQL、Redis 和 RocketMQ 放在集群外。在执行任何 `kind` 或生产部署之前，先确认以下几点：

- Kubernetes 节点可以访问 PostgreSQL 的 `5432`
- Kubernetes 节点可以访问 Redis 的 `6379`
- Kubernetes 节点可以访问 RocketMQ NameServer 的 `9876`
- Kubernetes 节点可以访问 RocketMQ Broker 的 `10909`、`10911` 和 `10912`
- 这些系统对应的 DNS 名称或固定 IP 足够稳定，可以写进 ConfigMap / Secret
- 防火墙、安全组或本地主机网络允许集群节点访问这些地址
- 这些系统需要的凭据或 TLS 材料都以 Kubernetes Secret 提供，而不是直接打进镜像

如果 `MOCHAT_REDIS_URI` 里包含密码、用户名或 TLS 选项，就把整个 URI 都按敏感信息处理。

## 本地 `kind` 验证路径

这一节说明的是当前仓库自带 `deploy/kubernetes/overlays/kind` overlay 的本地验证流程。

### 工具前置条件

- `kind`
- `kubectl`
- `podman`
- `jq`
- `rg`
- `ss`
- `openssl`，用于 TCP / TLS 探测

### 1. 构建服务镜像

在仓库根目录执行：

```bash
podman build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
podman build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
podman build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
podman build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
```

### 2. 创建 `kind` 集群

使用一个配置，把宿主机端口 `9000` 转发到 gateway Service 的 `nodePort: 32000`。验证脚本默认使用 `KIND_CLUSTER_NAME=kind-cluster` 和 `KUBECTL_CONTEXT=kind-kind-cluster`；如果你的集群名字不同，请显式覆盖。

示例 `kind-config.yaml`：

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 32000
        hostPort: 9000
        protocol: TCP
```

然后创建集群：

```bash
kind create cluster --name kind-cluster --config kind-config.yaml
```

### 3. 准备 overlay 本地输入

`kind` overlay 依赖 `deploy/kubernetes/overlays/kind/.local` 下面的本地文件。可以执行下面的脚本生成：

```bash
bash deploy/kubernetes/overlays/kind/prepare-local-inputs.sh
```

这个脚本会用 `podman inspect` 解析 compose 容器 IP，写出 `.local/external-dependencies.env`、`.local/external-dependency-secrets.env`，并在 `.local/access-gateway-tls/` 下生成一套自签名 gateway TLS 证书。

### 4. 主要的最小验证路径

当前已经验证过的 5.1 入口是：

```bash
bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh
```

这个脚本会：

- 重新生成 `.local` 输入文件
- 把 `localhost/mochat/*:dev` 镜像保存成归档，并通过 `kind load image-archive` 导入 `kind`
- 执行 `kubectl apply -k deploy/kubernetes/overlays/kind`
- 重启 Deployments / StatefulSet，并等待 rollout 完成
- 探测 Service DNS、Pod DNS、集群外端口，以及 gateway NodePort

当前 overlay 展开后包含的仓库自带对象有：

- `Namespace/mochat`
- `ConfigMap/mochat-runtime-config`
- `ConfigMap/mochat-external-dependencies`
- `Secret/mochat-external-dependency-secrets`
- `Secret/access-gateway-tls`
- `Deployment/api-service`
- `Deployment/message-service`
- `Deployment/persistence-service`
- `Service/api-service`
- `Service/message-service`
- `StatefulSet/access-gateway`
- `Service/access-gateway-headless`
- `Service/access-gateway-tcp`

### 5. 最小脚本会检查什么

```bash
kubectl -n mochat rollout status deploy/api-service
kubectl -n mochat rollout status deploy/message-service
kubectl -n mochat rollout status deploy/persistence-service
kubectl -n mochat rollout status statefulset/access-gateway
kubectl -n mochat get pods,svc,endpoints
```

### 6. 验证 Service DNS 和 StatefulSet 身份

```bash
kubectl -n mochat run mochat-kind-minimal-probe \
  --image=busybox:1.36 \
  --restart=Never \
  --command -- sh -c "
    nslookup api-service.mochat.svc.cluster.local
    nslookup access-gateway-0.access-gateway-headless.mochat.svc.cluster.local
  "
```

确认 StatefulSet 通过元数据注入运行时 Pod 身份，而不是写死一个字面量值：

```bash
kubectl get statefulset access-gateway -n mochat \
  -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name=='MOCHAT_RUNTIME_POD_NAME')].valueFrom.fieldRef.fieldPath}"
```

### 7. 验证对集群外暴露的 TCP 入口

当 `access-gateway-tcp` 就绪，并且已经通过 `kind` 映射到宿主机后，可以从宿主机探测 TLS 监听：

```bash
openssl s_client -connect 127.0.0.1:9000 -servername localhost </dev/null
```

预期结果：

- TCP 连接成功
- TLS 握手可以到达 gateway 监听器
- 如果挂载了自定义证书 Secret，返回的证书应与该 Secret 中的材料一致

### 8. 验证集群外基础设施连通性

最小脚本会从一个临时 busybox Pod 中，通过 `nc -vz -w 2` 检查 PostgreSQL、Redis、RocketMQ NameServer 和 RocketMQ Broker 端口的连通性。接着要看的信号是应用日志中没有连接失败：

```bash
kubectl -n mochat logs deploy/api-service --tail=50
kubectl -n mochat logs deploy/message-service --tail=50
kubectl -n mochat logs deploy/persistence-service --tail=50
kubectl -n mochat logs access-gateway-0 --tail=50
```

下面这些内容不应该出现：

- PostgreSQL 认证错误或 socket 错误
- Redis `connection refused` 或认证失败
- RocketMQ name-server 查询失败或 broker 连接失败

### 9. 路由 / drain 验证路径

更深入的 5.2 验证路径已经由单独脚本提供：

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
SKIP_MINIMAL_TOPOLOGY=1 \
  bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh
```

如果是在文件系统沙箱里运行，请显式设置 `GRADLE_USER_HOME`，避免 Gradle 写入 `~/.gradle`。

这个脚本覆盖的内容包括：

- gateway 扩容
- `preStop` + drain 行为
- 终止前的 readiness 切换
- 通过 Pod DNS 的 owner 定向投递
- rollout 或路由陈旧时的离线兜底语义

它还会在仓库根目录重新运行这些聚焦测试：

- `:message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.GatewayIngressLifecycleTest`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayLifecycleEndpointTest`

不要仅凭这份 runbook 就推断这些保证已经成立；真正的证据仍然是脚本和这些聚焦测试。

## 回滚到当前静态地址拓扑

如果 Kubernetes 原生路径不稳定，回滚时要从运行时入口层处理。不要回滚 Redis schema、PostgreSQL schema，也不要回滚 RocketMQ topic。

### 1. 停掉 Kubernetes 工作负载

```bash
kubectl delete -k deploy/kubernetes/overlays/kind
kind delete cluster --name kind-cluster
```

### 2. 在本地重启共享基础设施

```bash
podman compose up -d
podman compose ps
```

### 3. 恢复基于静态目标映射的服务启动方式

`api-service`：

```bash
./gradlew :api-service-app:run
```

`message-service`：

```bash
JAVA_TOOL_OPTIONS='-Dgrpc.channels.api-service.address=127.0.0.1:19091 \
  -Dmochat.message-service.route.gateway-targets.gateway-a=127.0.0.1:19093 \
  -Dmochat.message-service.route.gateway-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :message-service-app:run
```

`persistence-service`：

```bash
./gradlew :persistence-service-app:run
```

`access-gateway-a`：

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-a \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19093 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9000 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :access-gateway-app:run
```

`access-gateway-b`：

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-b \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19094 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9001 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-a=127.0.0.1:19093' \
  ./gradlew :access-gateway-app:run
```

回滚后应满足：

- `message-service` 再次依赖 `mochat.message-service.route.gateway-targets.*`
- `access-gateway` 的 peer kick 流程再次依赖 `mochat.access-gateway.route.peer-targets.*`
- `gatewayPod` 重新变回手工指定的本地标识，例如 `gateway-a` / `gateway-b`

### 4. 最深层兼容兜底

如果连“按独立服务拆分运行”这条路径也需要绕开，就回退到旧的单体壳层：

```bash
MOCHAT_LEGACY_PERSISTENCE_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=true \
  ./gradlew :app:run
```

这不是首选运行方式，但它仍然是最后一道兜底，因为它沿用的是同一套 PostgreSQL、Redis 和 RocketMQ 基础设施。
