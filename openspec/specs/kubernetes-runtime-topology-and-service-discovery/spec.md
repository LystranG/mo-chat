## Purpose

Define the Kubernetes-native runtime topology, service discovery model, external infrastructure dependencies, and configuration ownership rules for the four-service MoChat deployment.

## ADDED Requirements

### Requirement: Kubernetes workload topology matches service runtime roles
The system MUST deploy `api-service`, `message-service`, and `persistence-service` as independently scalable Kubernetes workloads for stateless/internal service processing. The system MUST deploy `access-gateway` as a stateful Kubernetes workload so each gateway replica keeps stable runtime identity for routing and ownership semantics.

#### Scenario: stateless internal services scale independently
- **WHEN** an operator scales `api-service`, `message-service`, or `persistence-service` on Kubernetes
- **THEN** the service scales through its own workload replicas without requiring in-process composition with other service entrypoints

#### Scenario: gateway replicas keep stable identity
- **WHEN** Kubernetes starts or reschedules an `access-gateway` replica
- **THEN** that replica exposes a stable logical gateway identity that can be used for route ownership and targeted delivery

### Requirement: Kubernetes-native service discovery replaces static runtime target maps
In Kubernetes-native deployment, synchronous service-to-service communication MUST resolve through Kubernetes DNS and Service primitives rather than manually maintained runtime target maps. Owner-addressed gateway delivery MUST resolve the target gateway from Kubernetes pod-addressable DNS derived from the owning gateway identity.

#### Scenario: internal services resolve through Service DNS
- **WHEN** `api-service`, `message-service`, or `persistence-service` connects to another internal service in Kubernetes
- **THEN** the caller resolves the dependency through the destination Service DNS name instead of a manually curated address map

#### Scenario: targeted delivery resolves owner gateway through pod DNS
- **WHEN** `message-service` needs to deliver to a recipient whose active route is owned by a specific gateway replica
- **THEN** it resolves the owning `access-gateway` instance through pod-addressable DNS derived from that gateway's stable identity

### Requirement: Shared infrastructure remains external to the Kubernetes cluster
The Kubernetes deployment contract MUST treat PostgreSQL, Redis, and RocketMQ as cluster-external dependencies. The runtime MUST connect to those systems through explicit external endpoints and MUST NOT require in-cluster instances of those systems for the core microservice topology to function.

#### Scenario: workload starts with external dependency endpoints
- **WHEN** a service pod starts in Kubernetes
- **THEN** it reads PostgreSQL, Redis, and RocketMQ connectivity from deployment configuration that points to external infrastructure endpoints

#### Scenario: microservice rollout leaves shared infrastructure placement unchanged
- **WHEN** an operator deploys or rolls out the Kubernetes-native MoChat services
- **THEN** the deployment changes only the application workloads and does not require moving PostgreSQL, Redis, or RocketMQ into the cluster

### Requirement: Kubernetes config primitives own in-cluster configuration distribution
The Kubernetes-native deployment MUST distribute non-secret runtime configuration through ConfigMaps and secret material through Secrets. Pod-local identity values required by runtime routing behavior MUST be derived from Kubernetes runtime metadata rather than handwritten per-replica configuration.

#### Scenario: gateway identity comes from pod metadata
- **WHEN** an `access-gateway` pod starts in Kubernetes
- **THEN** the gateway derives its runtime gateway identity from Kubernetes-assigned pod metadata instead of a manually assigned peer alias

#### Scenario: credentials and TLS material come from Secrets
- **WHEN** a workload needs external service credentials or TLS material
- **THEN** the deployment provides that material through Kubernetes Secrets rather than embedding it in the container image
