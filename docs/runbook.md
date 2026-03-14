# Phase 1 Kubernetes Deployment Runbook

This runbook now treats Kubernetes-native deployment as the primary deployment contract for the split MoChat topology: `api-service`, `message-service`, `persistence-service`, and `access-gateway`.

Current scope as of 2026-03-14:

- The repository already has per-service Dockerfiles, dedicated service runtimes, runtime topology abstractions, and repository-owned Kubernetes assets under `deploy/kubernetes/base` and `deploy/kubernetes/overlays/kind`.
- The currently shipped local verification entrypoints are `bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh` and `GRADLE_USER_HOME="$PWD/.gradle-user-home" SKIP_MINIMAL_TOPOLOGY=1 bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh`.
- This document records the current worktree resource structure, the verified local `kind` path, the ConfigMap/Secret conventions, the cluster-external infrastructure contract, and the rollback path back to the current static-address runtime.

## Deployment Goals

- `api-service`, `message-service`, and `persistence-service` run as independently scalable Kubernetes workloads.
- `access-gateway` runs as a StatefulSet so each gateway replica keeps a stable `gatewayPod` identity.
- In-cluster synchronous calls resolve through Kubernetes Service DNS or headless-Service Pod DNS.
- PostgreSQL, Redis, and RocketMQ remain outside the cluster in this phase.
- Static `gateway-targets` and `peer-targets` remain a compatibility fallback only; they are not the primary production contract in Kubernetes.

## Repository Delivery Format

The current repository-owned Kubernetes packaging format is `kustomize`:

- `deploy/kubernetes/base`
- `deploy/kubernetes/overlays/kind`

The current worktree does not yet ship a `deploy/kubernetes/overlays/prod` overlay. Do not infer a production overlay from this document alone.

## Current Kubernetes Resource Structure

| Runtime | Workload kind | Service shape | Primary ports | Discovery contract | Notes |
| --- | --- | --- | --- | --- | --- |
| `api-service` | `Deployment` | `ClusterIP Service` | HTTP `8080`, gRPC `19091` | `api-service:19091` inside namespace | Session authority, login, social graph, history query |
| `message-service` | `Deployment` | `ClusterIP Service` | gRPC `19092` | `message-service:19092` inside namespace | Message ingest, ACK orchestration, online delivery, offline fallback |
| `persistence-service` | `Deployment` | no public Service required yet | none | none today | MQ consumer / persistence worker; add a Service only after it exposes an inbound API or probe-only sidecar |
| `access-gateway` | `StatefulSet` | headless Service for pod DNS, separate TCP ingress Service | TCP `9000`, gRPC `19093` | `<pod>.access-gateway-headless.<namespace>.svc.cluster.local:19093` | Stable owner identity, targeted delivery, drain-first rollout |

Current repository-owned Kubernetes objects:

- `Namespace`: `mochat`
- `ConfigMap`: `mochat-runtime-config`
- `ConfigMap`: `mochat-external-dependencies`
- `Secret`: `mochat-external-dependency-secrets`
- `Secret`: `access-gateway-tls`
- `Deployment`: `api-service`, `message-service`, `persistence-service`
- `Service`: `api-service`, `message-service`
- `StatefulSet`: `access-gateway`
- `Service` with `clusterIP: None`: `access-gateway-headless`
- `Service` for client TCP ingress: `access-gateway-tcp`

### Access Gateway Naming Contract

`access-gateway` keeps stable identity through StatefulSet pod names:

- `access-gateway-0`
- `access-gateway-1`
- `access-gateway-2`

The canonical owner-addressed gRPC target is derived from the pod name and the headless Service:

```text
<gatewayPod>.access-gateway-headless.mochat.svc.cluster.local:19093
```

Examples:

- `access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:19093`
- `access-gateway-1.access-gateway-headless.mochat.svc.cluster.local:19093`

Redis online-route ownership in Kubernetes should therefore persist the pod identity itself, for example `gatewayPod=access-gateway-0`, instead of a handwritten alias such as `gateway-a`.

## Configuration Ownership Rules

Configuration is currently split into five buckets.

### 1. ConfigMap: in-cluster runtime discovery

`mochat-runtime-config` currently carries only non-secret, in-cluster discovery defaults:

| Key | Why it belongs in ConfigMap |
| --- | --- |
| `MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091` | stable in-cluster Service DNS |
| `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092` | stable in-cluster Service DNS |
| `MOCHAT_GATEWAY_HEADLESS_SERVICE=access-gateway-headless` | stable headless Service name for Pod DNS resolution |

### 2. ConfigMap: cluster-external dependency endpoints

`mochat-external-dependencies` carries the non-secret endpoints that are replaced by the `kind` overlay from `.local/external-dependencies.env`:

| Key | Why it belongs in ConfigMap |
| --- | --- |
| `MOCHAT_REDIS_URI=redis://<external-host>:6379` | external endpoint without embedded secret |
| `MOCHAT_POSTGRES_URL=jdbc:postgresql://<external-host>:5432/mochat` | external endpoint, not a credential by itself |
| `MOCHAT_ROCKETMQ_NAME_SERVER=<external-host>:9876` | external endpoint |
| `MOCHAT_ROCKETMQ_TOPIC=mochat.messages` | non-secret topic name |

### 3. Secret: credentials and TLS material

Sensitive values are currently split between `mochat-external-dependency-secrets` and `access-gateway-tls`:

| Key / material | Why it belongs in Secret |
| --- | --- |
| `MOCHAT_POSTGRES_USERNAME` | credential |
| `MOCHAT_POSTGRES_PASSWORD` | credential |
| `MOCHAT_REDIS_URI` when it embeds auth or TLS params | may contain password / auth material |
| RocketMQ username / password if enabled later | credential |
| gateway TLS certificate and private key | sensitive key material |

For gateway TLS, the current manifests mount `access-gateway-tls` to `/var/run/mochat/tls` and set:

- mount Secret volume to a fixed path such as `/var/run/mochat/tls`
- set `MOCHAT_ACCESS_GATEWAY_TLS_CERTIFICATE_PATH=/var/run/mochat/tls/tls.crt`
- set `MOCHAT_ACCESS_GATEWAY_TLS_PRIVATE_KEY_PATH=/var/run/mochat/tls/tls.key`

### 4. Pod metadata: identity derived from Kubernetes

The Kubernetes-native owner identity currently comes from pod metadata through `MOCHAT_RUNTIME_POD_NAME` / `MOCHAT_RUNTIME_POD_NAMESPACE`, not from a handwritten per-replica `gatewayPod`.

Current env injection:

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

`access-gateway-app/src/main/resources/application.yml` still keeps `mochat.access-gateway.route.gateway-pod` backed by `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD` / `${HOSTNAME}` as the local static fallback.

### 5. Compatibility-only static target maps

These keys are fallback-only and should remain empty in Kubernetes manifests:

- `mochat.message-service.route.gateway-targets.*`
- `mochat.access-gateway.route.peer-targets.*`

They are still required when rolling back to the current local static-address topology.

## Cluster-External Infrastructure Prerequisites

The first Kubernetes version keeps PostgreSQL, Redis, and RocketMQ outside the cluster. Before any `kind` or production rollout, confirm:

- Kubernetes nodes can reach PostgreSQL on `5432`
- Kubernetes nodes can reach Redis on `6379`
- Kubernetes nodes can reach RocketMQ NameServer on `9876`
- Kubernetes nodes can reach RocketMQ Broker on `10909`, `10911`, and `10912`
- DNS names or fixed IPs for those systems are stable enough to place in ConfigMap / Secret
- Firewall, security group, or local host networking allows traffic from cluster nodes to those endpoints
- Any credential or TLS material required by those systems is available as Kubernetes Secrets, not baked into container images

If `MOCHAT_REDIS_URI` contains password, username, or TLS options, treat the whole URI as secret material.

## Local `kind` Verification Path

This section defines the current local verification flow for the repository-owned `deploy/kubernetes/overlays/kind` overlay.

### Tooling Prerequisites

- `kind`
- `kubectl`
- `podman`
- `jq`
- `rg`
- `ss`
- `openssl` for TCP/TLS probe

### 1. Build service images

From repository root:

```bash
podman build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
podman build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
podman build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
podman build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
```

### 2. Create the `kind` cluster

Use a config that forwards host port `9000` to the gateway Service `nodePort: 32000`. The verification script defaults to `KIND_CLUSTER_NAME=kind-cluster` and `KUBECTL_CONTEXT=kind-kind-cluster`; override them explicitly if your cluster uses different names.

Example `kind-config.yaml`:

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

Then create the cluster:

```bash
kind create cluster --name kind-cluster --config kind-config.yaml
```

### 3. Prepare overlay-local inputs

The `kind` overlay expects local files under `deploy/kubernetes/overlays/kind/.local`. Generate them with:

```bash
bash deploy/kubernetes/overlays/kind/prepare-local-inputs.sh
```

The script uses `podman inspect` to resolve compose container IPs, writes `.local/external-dependencies.env`, `.local/external-dependency-secrets.env`, and issues a self-signed gateway TLS certificate under `.local/access-gateway-tls/`.

### 4. Primary minimal verification path

The currently verified 5.1 entrypoint is:

```bash
bash deploy/kubernetes/overlays/kind/verify-minimal-topology.sh
```

This script will:

- regenerate `.local` inputs
- save `localhost/mochat/*:dev` images and load them into `kind` through `kind load image-archive`
- `kubectl apply -k deploy/kubernetes/overlays/kind`
- restart the Deployments / StatefulSet and wait for rollout
- probe Service DNS, Pod DNS, cluster-external ports, and the gateway NodePort

The overlay currently expands to these repository-owned objects:

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

### 5. What the minimal script checks

```bash
kubectl -n mochat rollout status deploy/api-service
kubectl -n mochat rollout status deploy/message-service
kubectl -n mochat rollout status deploy/persistence-service
kubectl -n mochat rollout status statefulset/access-gateway
kubectl -n mochat get pods,svc,endpoints
```

### 6. Verify Service DNS and StatefulSet identity

```bash
kubectl -n mochat run mochat-kind-minimal-probe \
  --image=busybox:1.36 \
  --restart=Never \
  --command -- sh -c "
    nslookup api-service.mochat.svc.cluster.local
    nslookup access-gateway-0.access-gateway-headless.mochat.svc.cluster.local
  "
```

Verify that the StatefulSet wires runtime pod identity from metadata instead of a handwritten literal:

```bash
kubectl get statefulset access-gateway -n mochat \
  -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name=='MOCHAT_RUNTIME_POD_NAME')].valueFrom.fieldRef.fieldPath}"
```

### 7. Verify external TCP exposure

After `access-gateway-tcp` is ready and mapped through `kind`, probe the TLS listener from the host:

```bash
openssl s_client -connect 127.0.0.1:9000 -servername localhost </dev/null
```

Expected result:

- TCP connect succeeds
- TLS handshake reaches the gateway listener
- if a custom certificate Secret is mounted, the returned certificate matches that material

### 8. Verify cluster-external infrastructure connectivity

The minimal script checks cluster-external connectivity from a temporary busybox pod with `nc -vz -w 2` against PostgreSQL, Redis, RocketMQ NameServer, and RocketMQ Broker ports. The follow-up signal is application logs without connection failures:

```bash
kubectl -n mochat logs deploy/api-service --tail=50
kubectl -n mochat logs deploy/message-service --tail=50
kubectl -n mochat logs deploy/persistence-service --tail=50
kubectl -n mochat logs access-gateway-0 --tail=50
```

What should not appear:

- PostgreSQL authentication or socket errors
- Redis connection refused / auth failures
- RocketMQ name-server lookup or broker connection failures

### 9. Routing / drain verification path

The deeper 5.2 verification path is already scripted separately:

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" \
SKIP_MINIMAL_TOPOLOGY=1 \
  bash deploy/kubernetes/overlays/kind/verify-routing-and-drain.sh
```

Use the explicit `GRADLE_USER_HOME` override when running inside a filesystem sandbox so Gradle does not write to `~/.gradle`.

The script covers:

- gateway scale-out
- preStop + drain behavior
- readiness transition before termination
- owner-addressed delivery through Pod DNS
- offline fallback semantics under rollout or stale route

It also reruns these focused Gradle tests from repository root:

- `:message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.GatewayIngressLifecycleTest`
- `:access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayLifecycleEndpointTest`

Do not infer these guarantees from the runbook alone; the scripts and focused tests remain the evidence.

## Rollback to the Current Static-Address Topology

If the Kubernetes-native path is unstable, roll back at the runtime-entrypoint layer. Do not roll back Redis schema, PostgreSQL schema, or RocketMQ topics.

### 1. Stop Kubernetes workloads

```bash
kubectl delete -k deploy/kubernetes/overlays/kind
kind delete cluster --name kind-cluster
```

### 2. Restart shared infrastructure locally

```bash
podman compose up -d
podman compose ps
```

### 3. Restore static target-map based service startup

`api-service`:

```bash
./gradlew :api-service-app:run
```

`message-service`:

```bash
JAVA_TOOL_OPTIONS='-Dgrpc.channels.api-service.address=127.0.0.1:19091 \
  -Dmochat.message-service.route.gateway-targets.gateway-a=127.0.0.1:19093 \
  -Dmochat.message-service.route.gateway-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :message-service-app:run
```

`persistence-service`:

```bash
./gradlew :persistence-service-app:run
```

`access-gateway-a`:

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-a \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19093 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9000 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :access-gateway-app:run
```

`access-gateway-b`:

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-b \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19094 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9001 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-a=127.0.0.1:19093' \
  ./gradlew :access-gateway-app:run
```

Rollback expectations:

- `message-service` once again relies on `mochat.message-service.route.gateway-targets.*`
- `access-gateway` peer kick flow once again relies on `mochat.access-gateway.route.peer-targets.*`
- `gatewayPod` becomes a manual local identifier such as `gateway-a` / `gateway-b`

### 4. Deepest compatibility fallback

If the dedicated-service split itself must be bypassed, fall back to the legacy shell:

```bash
MOCHAT_LEGACY_PERSISTENCE_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=true \
  ./gradlew :app:run
```

That path is not the preferred runtime, but it remains the last-resort rollback posture because it preserves the same PostgreSQL, Redis, and RocketMQ infrastructure.
