## Why

The current project already has clear module boundaries, but it still runs as a single Micronaut process, which hides the distributed business problems that matter most for this learning project. This change is needed now to turn those module boundaries into a small set of independently deployable services so connection ownership, cross-pod routing, ACK semantics, offline fallback, and asynchronous persistence can be designed explicitly instead of being left implicit inside one runtime.

## What Changes

- Split the current modular monolith into four core deployable services: `access-gateway`, `api-service`, `message-service`, and `persistence-service`.
- Keep PostgreSQL, Redis, and MQ shared in phase 1, but introduce clear service ownership for schemas, tables, keys, and queue responsibilities instead of allowing every runtime component to act as a single process.
- Add an `access-gateway` routing model based on bind-time ownership, Redis online route records, single active connection per user, `sessionVersion`, and `routeEpoch` fencing.
- Move login/session resolution, social graph, group management, and history query ownership into `api-service`.
- Move message ingest, idempotency, `seq` and `msgId` allocation, sender ACK, online delivery orchestration, and offline fallback into `message-service`.
- Move MQ consume, transactional persistence, conversation state advancement, and cache update responsibilities into `persistence-service`.
- Introduce internal gRPC contracts between services while keeping the external TCP protocol and HTTP surface stable for phase 1.
- Define drain-first scaling semantics for `access-gateway`: expansion does not rebalance existing connections, and shrink/rollout uses connection draining plus reconnect instead of live migration.
- Keep the project intentionally conservative for phase 1: no KEDA, no service mesh, no multi-device concurrent presence, no connection hot migration, and no database split.
- **BREAKING**: Runtime topology and internal module interaction assumptions change from single-process in-memory composition to cross-service RPC, Redis-backed route ownership, and MQ-backed eventual persistence.

## Capabilities

### New Capabilities
- `gateway-routing-and-connection-ownership`: Bind-time gateway ownership, Redis online routing records, single-active connection replacement, route fencing, drain semantics, and cross-pod targeted delivery.

### Modified Capabilities
- `modular-architecture-and-native-runtime`: Change the runtime requirement from a modular single process to a small set of independently deployable services with explicit RPC and queue boundaries.
- `transport-and-connection-lifecycle`: Extend transport requirements to cover gateway binding, online route renewal, stale route rejection, duplicate login replacement, and pod drain behavior.
- `login-session-and-user-bootstrap`: Change session ownership and validation requirements so `api-service` becomes the session authority and gateways resolve sessions through internal RPC instead of direct local composition.
- `message-protocol-and-delivery-guarantees`: Refine message acceptance, ACK, online delivery, and offline fallback requirements for cross-pod routing and dedicated message orchestration service ownership.
- `mq-persistence-pipeline-and-idempotency`: Change persistence requirements so a dedicated `persistence-service` owns MQ consumption, transactional writes, retry behavior, and eventual consistency boundaries.

## Impact

- Affected code: `app`, `connection-module`, `logic-module`, `message-module`, `persistence-module`, `common`, `infra-redis`, and `protocol`.
- Affected internal APIs: new gRPC contracts for session resolution, social graph checks, gateway delivery, and message command flows.
- Affected systems: Micronaut runtime assembly, Netty connection lifecycle, Redis online route storage, MQ consume/persist workflow, deployment manifests, and service startup/shutdown semantics.
- Affected operations: deployment changes from one process to four services, gateway rollouts must support draining, and Redis route TTL plus offline fallback become part of normal failure handling.
