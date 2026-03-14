## 1. 运行时发现与配置抽象

- [x] 1.1 盘点 `access-gateway`、`message-service`、`api-service`、`persistence-service` 当前依赖的静态地址配置，明确哪些配置在 Kubernetes 形态下要改为 Service DNS、Pod DNS 或 Pod 元数据派生
- [x] 1.2 为 gateway owner 寻址和 peer 寻址提炼统一的运行时抽象，支持“本地静态 target map”和“Kubernetes DNS 推导”两种实现模式
- [x] 1.3 调整服务配置模型，区分 ConfigMap、Secret、Pod 身份派生值和集群外基础设施地址，避免继续把 Kubernetes 运行时 identity 写死在手工环境变量里

## 2. Gateway 身份与一致性路由

- [x] 2.1 改造 `access-gateway` 的 `gatewayPod` 标识生成逻辑，使其在 Kubernetes 中默认来自 StatefulSet Pod 身份，并保持现有本地运行模式兼容
- [x] 2.2 改造 `message-service` 的 owner-addressed delivery 逻辑，使其根据 Redis route 中的 `gatewayPod` 通过 headless Service Pod DNS 解析目标 gateway，而不是依赖静态 `gateway-targets`
- [x] 2.3 改造 `access-gateway` 的 peer coordination / kick-old-connection 寻址逻辑，使其在 Kubernetes 中通过稳定 Pod DNS 寻址，并保留 `sessionVersion`、`routeEpoch`、lease、offline fallback 的既有一致性语义

## 3. Kubernetes 工作负载与接入资源

- [x] 3.1 为 `api-service`、`message-service` 定义 `Deployment` + `ClusterIP Service`，并为 `persistence-service` 定义基础 `Deployment`、ConfigMap/Secret 注入和外部依赖连接方式；当前阶段 `persistence-service` 不暴露独立 Service
- [x] 3.2 为 `access-gateway` 定义 `StatefulSet`、headless Service 和外部 TCP 接入 Service，明确 gRPC 内部访问与客户端 TCP 入口的分离方式
- [x] 3.3 为 `access-gateway` 增加 Kubernetes 语义下的 readiness、preStop、termination grace period 和 drain 配置，使 rollout / scale-in 先停新 bind 再清退存量连接

## 4. 集群外基础设施与部署约束

- [x] 4.1 规范 PostgreSQL、Redis、RocketMQ 作为集群外依赖的接入配置，明确 Kubernetes 内工作负载如何读取和使用这些外部 endpoint
- [x] 4.2 明确 TLS、认证凭据和其他敏感配置的 Secret 组织方式，并确保容器镜像和 manifest 不内嵌这些敏感值
- [x] 4.3 确定首版 Kubernetes 打包形式并落库，当前 worktree 以 `deploy/kubernetes/base` + `deploy/kubernetes/overlays/kind` 作为仓库内标准交付格式

## 5. 本地验证与运行文档

- [x] 5.1 在现有 `kind` 环境补齐最小可运行拓扑验证，使用 `bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh` 覆盖 Service DNS、gateway StatefulSet identity、外部 TCP 暴露和 cluster-external 基础设施连通性
- [x] 5.2 补充 gateway scale-out、drain、rollout 和 owner-addressed delivery 的验证用例，使用 `GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh` 与聚焦测试证明 Kubernetes 形态下不会回退现有路由 fencing 与 offline fallback 语义
- [x] 5.3 更新 README / runbook / deployment 文档，使其与当前 `deploy/kubernetes` 资产、`kind` 验证路径、ConfigMap/Secret 约定、外部基础设施前提以及回滚到静态寻址模式的办法保持一致
