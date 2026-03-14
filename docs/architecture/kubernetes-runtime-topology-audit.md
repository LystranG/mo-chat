# Kubernetes Runtime Topology Audit

This audit captures the current static addressing surfaces in the four-service runtime and the Kubernetes-native source each surface should migrate to during `adopt-k8s-cloud-native-topology`.

## Address and Identity Inventory

| Service | Current surface | Current role | Kubernetes source of truth |
| --- | --- | --- | --- |
| `access-gateway` | `grpc.channels.api-service.address` | call `api-service` gRPC | `Service DNS` |
| `access-gateway` | `grpc.channels.message-service.address` | call `message-service` gRPC | `Service DNS` |
| `access-gateway` | `mochat.access-gateway.route.gateway-pod` | persisted gateway owner identity | `Pod metadata` |
| `access-gateway` | `mochat.access-gateway.route.peer-targets.*` | cross-gateway duplicate-login kick target map | `Pod DNS` via headless Service |
| `message-service` | `grpc.channels.api-service.address` | call `api-service` gRPC | `Service DNS` |
| `message-service` | `mochat.message-service.route.gateway-targets.*` | owner-addressed delivery target map | `Pod DNS` via headless Service |
| `api-service` | `grpc.channels.message-service.address` | call `message-service` gRPC | `Service DNS` |
| `api-service` | `mochat.redis.uri` | shared cache/session endpoint | `Cluster-external endpoint` |
| `persistence-service` | `mochat.redis.uri` | cache/coordination endpoint | `Cluster-external endpoint` |
| `persistence-service` | `mochat.postgres.url` | durable store endpoint | `Cluster-external endpoint` |
| `persistence-service` | `mochat.rocketmq.name-server` | MQ endpoint | `Cluster-external endpoint` |
| `message-service` | `mochat.redis.uri` | route/offline queue endpoint | `Cluster-external endpoint` |
| `message-service` | `mochat.rocketmq.name-server` | outbound MQ endpoint | `Cluster-external endpoint` |
| `access-gateway` | `mochat.redis.uri` | online route lease endpoint | `Cluster-external endpoint` |

## Configuration Ownership Rules

- `ConfigMap-backed`: `mochat.runtime.gateway.discovery.*`, gRPC `grpc.channels.*`, feature toggles, queue/worker runtime switches.
- `Secret-backed`: TLS certificate/key paths, PostgreSQL credentials, RocketMQ credentials when introduced, any future auth material.
- `Pod-derived`: `mochat.runtime.pod.name`, `mochat.runtime.pod.namespace`, and gateway identity when running in `pod-metadata` mode.
- `Cluster-external endpoints`: `mochat.redis.*`, `mochat.postgres.*`, `mochat.rocketmq.*`.

## Runtime Abstractions Introduced In This Batch

- `GatewayIdentityProvider`: isolates how the local `gatewayPod` identity is determined.
- `GatewayAddressResolver`: isolates how a logical `gatewayPod` owner becomes a concrete gRPC target.
- `RuntimeTopologyConfiguration`: standardizes the shared runtime topology contract:
  - `mochat.runtime.gateway.identity-mode` / `identity-value`
  - `mochat.runtime.gateway.discovery-mode` / `static-targets`
  - `mochat.runtime.gateway.headless-service` / `namespace` / `cluster-domain` / `grpc-port`

## Kubernetes References

- StatefulSet stable identity and headless Service contract: <https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/>
- DNS for Services and Pods: <https://kubernetes.io/docs/concepts/services-networking/dns-pod-service/>
- Downward API pod metadata projection: <https://kubernetes.io/docs/tasks/inject-data-application/environment-variable-expose-pod-information/>
