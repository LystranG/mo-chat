## ADDED Requirements

### Requirement: Messaging and persistence module separation
The codebase MUST separate connection ingress, API/session authority, message orchestration, and persistence workflow into independently deployable and independently scalable services that interact through explicit gRPC or MQ contracts instead of single-process internal composition. Kubernetes deployment boundaries MUST preserve this separation so scaling one service does not require collocating or embedding another service's runtime responsibilities.

#### Scenario: message accepted in message-service
- **WHEN** `message-service` accepts a message for downstream handling
- **THEN** durable persistence is handed off through MQ to `persistence-service` rather than through direct in-process data access

#### Scenario: message and persistence services scale independently
- **WHEN** an operator scales `message-service` replicas in Kubernetes
- **THEN** persistence ownership remains exclusively in `persistence-service` and no in-process persistence path is introduced

### Requirement: Cloud-native evolution abstractions
Core runtime dependencies that may change in distributed deployment, including session resolution, online route ownership, gateway delivery, and user-channel mapping, MUST be abstracted behind replaceable interfaces or transport adapters. These abstractions MUST support Kubernetes-native discovery through Service DNS and StatefulSet-derived gateway identity without changing message business rules.

#### Scenario: gateway delivery implementation swap
- **WHEN** deployment mode requires replacing the implementation used to resolve or deliver to an online user
- **THEN** the system uses an alternative adapter without changing message business rules

#### Scenario: gateway delivery resolves through Kubernetes-native discovery
- **WHEN** deployment mode is Kubernetes-native
- **THEN** the transport adapter resolves peer services through Kubernetes DNS and stable gateway identity instead of static runtime target maps

### Requirement: Internal service contracts use gRPC
Synchronous communication between `access-gateway`, `api-service`, and `message-service` MUST use explicit internal gRPC contracts.

#### Scenario: gateway resolves session through service contract
- **WHEN** an `access-gateway` instance needs to validate a session during bind
- **THEN** it uses an internal gRPC session contract provided by `api-service`

### Requirement: Shared infrastructure has explicit service ownership
When multiple services share PostgreSQL, Redis, or MQ infrastructure, each table, key family, and queue responsibility MUST have a single owning service for writes and lifecycle rules. These ownership rules MUST remain unchanged when the application workloads are deployed on Kubernetes while PostgreSQL, Redis, and MQ stay outside the cluster.

#### Scenario: service writes owned coordination data
- **WHEN** the system updates online route state for a user
- **THEN** only the owning `access-gateway` flow writes the authoritative route record for that user

#### Scenario: external infrastructure does not blur service ownership
- **WHEN** Kubernetes-hosted services connect to shared PostgreSQL, Redis, or MQ endpoints outside the cluster
- **THEN** each service still writes only the data and coordination families it owns by contract

### Requirement: GraalVM native image compatibility
The build and packaging pipeline MUST support GraalVM native image packaging for every deployable microservice entrypoint: `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app`. Each deployable service MUST have an explicit Dockerfile that packages its service-specific native binary into a runnable container image. Shared library modules MUST remain internal build dependencies and MUST NOT be treated as standalone deployable container targets.

#### Scenario: dedicated service native build run
- **WHEN** the native build profile is executed for any deployable service entry module
- **THEN** that service produces its own native binary artifact with the runtime metadata required for that entrypoint

#### Scenario: dedicated service container image build
- **WHEN** an operator builds the image for one of the deployable service entry modules
- **THEN** the repository provides a service-owned Dockerfile that packages that service's native binary into a runnable container image

#### Scenario: containerized service keeps existing runtime contract
- **WHEN** a deployable service is started from its native-image-based container
- **THEN** it uses the same documented environment-variable configuration surface and listener contract as the dedicated runtime instead of requiring a separate container-only configuration model

### Requirement: Token-bucket rate limiting handler
The Netty pipeline MUST include a configurable token-bucket rate limiting handler for inbound client messages. Rate limiting MUST be enforced per channel (per user session), and timer-driven refills SHOULD use Netty `HashedWheelTimer`.

#### Scenario: rate limit exceeded
- **WHEN** a client exceeds configured token consumption rate or bucket capacity
- **THEN** server throttles or rejects excess messages according to configured policy
