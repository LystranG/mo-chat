## Why

The repository has already completed service decomposition and container packaging, but the runtime still depends on manually managed addresses, static gateway target maps, and non-Kubernetes deployment assumptions. This change is needed now to make the four services behave as a genuinely cloud-native topology on Kubernetes, so they can scale independently, use Kubernetes-native service discovery, and preserve routing and ownership consistency during horizontal expansion without moving PostgreSQL, Redis, or RocketMQ into the cluster.

## What Changes

- Introduce a Kubernetes-native runtime topology for `api-service`, `message-service`, `persistence-service`, and `access-gateway`, with each service deployed and scaled independently.
- Use Kubernetes DNS and service primitives as the service registry/discovery mechanism instead of manually maintained runtime target maps for production deployment.
- Define `access-gateway` as the stateful TCP ingress tier so each gateway pod keeps stable identity during routing, targeted delivery, drain, and rollout operations.
- Define horizontal scaling rules for stateless services and stateful gateways so scaling events preserve single-owner routing, fencing, and offline fallback behavior instead of relying on manual coordination.
- Keep PostgreSQL, Redis, and RocketMQ as external shared infrastructure, and define how in-cluster services consume them as cluster-external dependencies rather than deploying those systems into Kubernetes.
- Standardize Kubernetes-oriented configuration ownership, including which values come from ConfigMap/Secret, which values are derived from Pod identity, and which runtime assumptions remain externalized.
- **BREAKING**: Production deployment assumptions change from manually configured peer/gateway target addressing to Kubernetes-native deployment, identity, and discovery contracts.

## Capabilities

### New Capabilities
- `kubernetes-runtime-topology-and-service-discovery`: Kubernetes workload shapes, DNS-based service discovery, cluster-external infrastructure dependencies, and configuration/rollout conventions for the four-service runtime.

### Modified Capabilities
- `modular-architecture-and-native-runtime`: Change the runtime requirement from merely split deployable services and native images to Kubernetes-first microservice deployment with independent scaling and explicit cloud-native runtime boundaries.
- `gateway-routing-and-connection-ownership`: Change owner-addressed delivery and peer coordination requirements so gateway identity, targeted delivery, and drain behavior work through Kubernetes StatefulSet identity and DNS-based discovery instead of static target maps.
- `transport-and-connection-lifecycle`: Extend gateway lifecycle requirements to cover Kubernetes pod readiness, termination, and drain behavior for TCP ingress during rollout and horizontal scaling.

## Impact

- Affected code and config: `access-gateway-app`, `api-service-app`, `message-service-app`, `persistence-service-app`, `service-runtime`, deployment manifests/charts, and service discovery related configuration surfaces.
- Affected systems: Kubernetes workloads and Services, in-cluster DNS/service discovery, external PostgreSQL/Redis/RocketMQ connectivity, and gateway rollout/scale procedures.
- Affected operations: service startup contracts, pod identity derivation, ConfigMap/Secret management, scale-out/scale-in procedures, and local verification paths based on `kind`.
