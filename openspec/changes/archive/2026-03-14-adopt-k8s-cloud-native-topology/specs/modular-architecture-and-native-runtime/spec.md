## MODIFIED Requirements

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

### Requirement: Shared infrastructure has explicit service ownership
When multiple services share PostgreSQL, Redis, or MQ infrastructure, each table, key family, and queue responsibility MUST have a single owning service for writes and lifecycle rules. These ownership rules MUST remain unchanged when the application workloads are deployed on Kubernetes while PostgreSQL, Redis, and MQ stay outside the cluster.

#### Scenario: service writes owned coordination data
- **WHEN** the system updates online route state for a user
- **THEN** only the owning `access-gateway` flow writes the authoritative route record for that user

#### Scenario: external infrastructure does not blur service ownership
- **WHEN** Kubernetes-hosted services connect to shared PostgreSQL, Redis, or MQ endpoints outside the cluster
- **THEN** each service still writes only the data and coordination families it owns by contract
