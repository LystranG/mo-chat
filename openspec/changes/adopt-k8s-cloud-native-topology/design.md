## Context

The repository already runs with four dedicated services by default: `access-gateway`, `api-service`, `message-service`, and `persistence-service`. It also already has per-service native-image-based container packaging. What is still missing is the actual cloud-native runtime contract: deployment still assumes manually managed peer addresses, explicit gateway target maps, and non-Kubernetes orchestration semantics.

The current architecture is therefore "containerized microservices" rather than "Kubernetes-native microservices". This matters most in the following places:

- `access-gateway` still relies on explicit peer target configuration for cross-gateway coordination.
- `message-service` still relies on explicit gateway target configuration for targeted delivery.
- Runtime identity, rollout, readiness, and scaling semantics are not yet defined in Kubernetes terms.
- PostgreSQL, Redis, and RocketMQ are still shared external infrastructure, which is acceptable for this phase but must be expressed explicitly in the deployment design.

Primary stakeholders:
- Developers who want the repository to operate as a real cloud-native microservice system instead of a locally orchestrated service split.
- Operators running the stack on Kubernetes and needing predictable deployment, service discovery, drain, and rollback behavior.
- Future implementation work that will replace static address maps with Kubernetes-native discovery.

Key constraints:
- Keep PostgreSQL, Redis, and RocketMQ outside Kubernetes in this change.
- Preserve the existing four-service ownership boundaries.
- Preserve current message ordering, ACK, offline fallback, and route-fencing semantics.
- Keep `access-gateway` as the TCP/TLS ingress tier with stable ownership identity.
- Do not introduce service mesh, KEDA, or database-per-service in this change.

## Goals / Non-Goals

**Goals:**
- Define the Kubernetes-native runtime topology for the four existing services.
- Replace manually maintained service/gateway target addressing with Kubernetes-native discovery.
- Preserve single-owner routing and distributed consistency semantics during horizontal scaling.
- Make `access-gateway` horizontally scalable without live connection migration.
- Define how external PostgreSQL, Redis, and RocketMQ are consumed from inside the cluster.
- Define Kubernetes-oriented rollout, readiness, drain, and configuration contracts.

**Non-Goals:**
- Deploy PostgreSQL, Redis, or RocketMQ into Kubernetes.
- Introduce strong consistency beyond the current fencing, lease, and MQ handoff model.
- Add multi-device presence, connection hot migration, or active connection rebalance.
- Introduce a dedicated config center outside Kubernetes primitives.
- Add service mesh, sidecars, or cluster-level auto-scaling policy design in this change.

## Decisions

### 1. Use Kubernetes DNS and Service primitives as the service registry

Decision:
- Use Kubernetes Services, headless Services, and Pod DNS as the only service registry/discovery mechanism for in-cluster communication.
- `api-service`, `message-service`, and `persistence-service` use stable Service DNS names.
- `access-gateway` peer and owner addressing use StatefulSet pod DNS derived from the gateway pod identity.

Rationale:
- Kubernetes already provides the control plane, registry, and name resolution needed for this topology.
- Introducing a separate registry such as Nacos or Consul would duplicate infrastructure without solving a problem Kubernetes DNS does not already solve in this phase.
- The project requirement explicitly wants the registry role to move to Kubernetes.

Alternatives considered:
- Dedicated registry middleware: rejected because it adds another highly available control-plane dependency and duplicates Kubernetes-native discovery.
- Static configuration maps of service addresses: rejected because they do not scale operationally and are the current limitation this change is meant to remove.

### 2. Model `access-gateway` as a StatefulSet with a headless Service

Decision:
- Deploy `access-gateway` as a `StatefulSet`.
- Back it with a headless Service so each pod has a stable DNS identity, such as `<pod>.<headless-service>.<namespace>.svc`.
- Keep a separate externally reachable TCP Service for client ingress.

Rationale:
- `access-gateway` owns long-lived TCP connections and must preserve stable identity for route ownership, targeted delivery, stale-route fencing, and drain workflows.
- StatefulSet identity maps directly to the existing `gatewayPod` concept already used in route records and ownership semantics.
- A headless Service gives pod-addressable DNS without random load-balancing when `message-service` must reach the exact owning gateway.

Alternatives considered:
- Deploy `access-gateway` as a normal Deployment: rejected because pod names and identities are not stable enough for owner-addressed routing semantics.
- Route targeted delivery through a shared ClusterIP Service: rejected because that would send delivery RPCs to arbitrary gateway replicas rather than the actual owner.

### 3. Keep `api-service`, `message-service`, and `persistence-service` as Deployments

Decision:
- Deploy `api-service`, `message-service`, and `persistence-service` as Deployments.
- Each service gets a ClusterIP Service for stable in-cluster access.
- Horizontal scaling is allowed, but ownership semantics remain explicit:
  - `api-service` remains the session authority.
  - `message-service` remains the synchronous accept/orchestration owner.
  - `persistence-service` remains the asynchronous durable write owner.

Rationale:
- These services do not require stable per-pod identity in the same way as `access-gateway`.
- Standard Deployment rollout and scaling semantics match their current responsibilities.
- The operational complexity should remain concentrated in the gateway tier, not spread across all services.

Alternatives considered:
- Make all services StatefulSets: rejected because most services do not require stable pod identity, and doing so would add operational overhead without architectural value.
- Keep all four services behind a single entry Deployment: rejected because it collapses the deployment boundaries that the project already established.

### 4. Replace static gateway target maps with DNS-derived owner addressing

Decision:
- Stop treating `message-service.route.gateway-targets.*` and `access-gateway.route.peer-targets.*` as the production source of truth.
- Persist `gatewayPod` as the logical gateway owner in Redis route records.
- Resolve the target gateway gRPC address from Kubernetes naming rules, using the pod identity and headless Service domain.

Rationale:
- The route record already carries logical ownership information, which is the correct abstraction boundary.
- Kubernetes can derive the network location of the owning gateway from the pod identity; a separate target map is redundant in-cluster.
- This removes one of the last manually maintained pieces preventing genuine cloud-native operation.

Alternatives considered:
- Continue passing target maps through environment variables: rejected because it reintroduces manual coordination and blocks elastic scaling.
- Store raw pod IP as the only route target: rejected because pod IP is less stable and less operator-friendly than DNS identity, and it couples runtime semantics too tightly to current pod allocation.

### 5. Preserve distributed consistency through ownership fencing, not global coordination

Decision:
- Keep the current consistency model based on `sessionVersion`, `routeEpoch`, Redis route lease renewal, and MQ handoff boundaries.
- Horizontal scale must not introduce new strong-consistency assumptions.
- `message-service` continues to deliver to the owner gateway using the expected route epoch, retries route resolution at most once, and falls back to offline delivery when ownership is absent or stale.

Rationale:
- The repository already has a coherent distributed consistency model centered on owner fencing and eventual persistence.
- Kubernetes rollout/scaling should integrate with that model rather than replace it with distributed locking or transactional coordination.
- This keeps the system scalable while preserving explainable failure handling.

Alternatives considered:
- Add distributed locks for gateway ownership transitions: rejected because it adds coordination latency and complexity without replacing the need for fencing.
- Require strong delivery consistency across Redis, MQ, and PostgreSQL: rejected because it conflicts with the current architecture and would force a much broader redesign.

### 6. Standardize gateway rollout on readiness + drain + termination grace period

Decision:
- A gateway pod entering rollout or scale-in must stop accepting new ownership before it stops serving existing connections.
- Kubernetes readiness must be tied to "can accept new binds" rather than merely "process is alive".
- Termination must include a drain window and graceful shutdown period before remaining connections are closed.

Rationale:
- This matches the existing gateway drain semantics already defined in the system behavior.
- Kubernetes rollout safety depends on readiness and termination semantics being aligned with connection ownership, not just process health.
- It provides a predictable operating model for both rolling updates and manual scale-in.

Alternatives considered:
- Kill gateway pods immediately on rollout: rejected because it turns every rollout into a forced disconnect spike and bypasses the designed drain semantics.
- Keep pods ready until final shutdown: rejected because new binds would continue landing on pods that are about to terminate.

### 7. Treat Kubernetes config primitives as the configuration center for in-cluster services

Decision:
- Use ConfigMaps for non-secret runtime configuration and Secrets for credentials, TLS materials, and external dependency authentication.
- Derive pod-local identity values such as gateway pod name from Kubernetes runtime metadata rather than hard-coding them.
- Do not introduce a separate dynamic configuration server in this change.

Rationale:
- Kubernetes-native deployment should use Kubernetes-native configuration distribution unless there is a clear unmet requirement.
- The current project needs stable configuration ownership more than runtime hot-reload sophistication.
- This keeps the operational model simple and aligned with the target platform.

Alternatives considered:
- Add an external config center immediately: rejected because it increases moving parts before the Kubernetes runtime contract is even stabilized.
- Keep all configuration in environment variables only: rejected because larger structured deployment values are easier to manage through ConfigMaps/Secrets and generated manifests.

### 8. Keep PostgreSQL, Redis, and RocketMQ as cluster-external dependencies

Decision:
- Kubernetes workloads connect to PostgreSQL, Redis, and RocketMQ as external endpoints.
- This design does not define in-cluster deployment, persistence classes, storage failover, or stateful operators for those systems.
- The deployment contract must clearly separate "workloads on Kubernetes" from "shared infrastructure outside Kubernetes".

Rationale:
- The user explicitly wants these systems to stay outside Kubernetes.
- This preserves the current learning focus on microservice topology, routing, and consistency rather than infrastructure replatforming.
- It also reduces the blast radius of the first Kubernetes adoption step.

Alternatives considered:
- Move all infrastructure into Kubernetes now: rejected because it turns one architectural change into several unrelated migrations.
- Partially move only Redis or MQ: rejected because it complicates network and operational assumptions without improving the primary design objective.

### 9. Accept transport fallback in Kubernetes; do not make `io_uring` a deployment gate

Decision:
- `access-gateway` continues to prefer Linux `io_uring` when available and fall back to epoll/NIO when unavailable.
- Kubernetes adoption does not require guaranteeing `io_uring` in every local or production environment.
- Production validation of `io_uring` support remains an environment capability concern, not a prerequisite for the Kubernetes runtime model itself.

Rationale:
- The codebase already implements transport fallback.
- Kubernetes, container runtime, seccomp profile, and local cluster providers may influence syscall availability.
- Blocking cloud-native migration on uniform `io_uring` guarantees would stall the more important service discovery and scaling work.

Alternatives considered:
- Require `io_uring` everywhere before adopting Kubernetes: rejected because it couples platform migration to a transport optimization that already has a supported fallback path.
- Disable `io_uring` entirely in Kubernetes: rejected because it would discard a useful optimization in environments that can support it correctly.

## Risks / Trade-offs

- [Gateway owner DNS derivation and runtime naming drift] -> Mitigation: define one canonical StatefulSet/headless-Service naming rule and derive addresses only from that contract.
- [Rolling update misconfiguration can still cause client disconnect spikes] -> Mitigation: tie readiness, pre-stop behavior, and termination grace period directly to drain semantics and verify them in local `kind` runs.
- [Kubernetes DNS replaces target maps but does not eliminate stale route windows] -> Mitigation: keep route lease renewal, `routeEpoch` fencing, and offline fallback as the authoritative safety net.
- [External PostgreSQL/Redis/RocketMQ increase network dependency between cluster and outside services] -> Mitigation: make external endpoints explicit in config, keep retry/failure behavior unchanged, and avoid coupling Kubernetes rollout to infrastructure migration.
- [StatefulSet increases operational complexity for the gateway tier] -> Mitigation: confine stable identity requirements to `access-gateway`; keep the rest of the services as ordinary Deployments.
- [ConfigMap/Secret based configuration is less dynamic than a dedicated config center] -> Mitigation: accept rollout-based config changes in this phase and defer hot-reload architecture until it is actually needed.
- [Single-node `kind` verification does not fully represent production networking] -> Mitigation: use `kind` only as the minimum local proof of topology, manifests, DNS, and drain behavior; treat production validation as a later deployment stage.

## Migration Plan

1. Define the new and modified OpenSpec requirements for Kubernetes runtime topology, service discovery, gateway identity, and rollout semantics.
2. Introduce Kubernetes manifest ownership for the four services, including workload kind, Services, ConfigMaps, Secrets, and namespace-level assumptions.
3. Change gateway identity handling so `gatewayPod` is derived from Kubernetes pod identity instead of local/manual target configuration.
4. Change owner-addressed delivery so `message-service` resolves gateway gRPC targets from Kubernetes DNS rather than explicit gateway target maps.
5. Add readiness, pre-stop, and termination behavior for `access-gateway` that implements drain-first rollout semantics.
6. Keep `api-service`, `message-service`, and `persistence-service` on stable Service DNS names and connect them to cluster-external PostgreSQL, Redis, and RocketMQ endpoints through configuration.
7. Verify the topology locally on `kind`, including:
   - Service discovery and DNS resolution
   - Gateway StatefulSet identity
   - TCP ingress exposure
   - Drain and rollout behavior
   - Targeted delivery after scale-out
8. Roll out incrementally so the previous static-address model can be retained as a fallback during transition if needed.

Rollback strategy:
- Revert Kubernetes deployment changes to the last known working static-address/container runtime configuration.
- Preserve Redis route schema, PostgreSQL data, and MQ topics so rollback does not require data migration.
- If DNS-derived owner routing proves unstable, temporarily restore explicit target configuration while keeping the service split intact.

## Open Questions

- Should `persistence-service` scale horizontally by default, or should the first Kubernetes version pin it to a conservative replica count until MQ consumer behavior is fully characterized?
- Should the external TCP ingress for `access-gateway` use `LoadBalancer`, `NodePort`, or environment-specific service templates in the first implementation?
- Do we want a dedicated local overlay, Helm chart, or plain manifests as the first repository-owned Kubernetes packaging format?
