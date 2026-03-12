## ADDED Requirements

### Requirement: Messaging and persistence module separation
The codebase MUST separate connection ingress, API/session authority, message orchestration, and persistence workflow into independently deployable services that interact through explicit gRPC or MQ contracts instead of single-process internal composition.

#### Scenario: message accepted in message-service
- **WHEN** `message-service` accepts a message for downstream handling
- **THEN** durable persistence is handed off through MQ to `persistence-service` rather than through direct in-process data access

### Requirement: Cloud-native evolution abstractions
Core runtime dependencies that may change in distributed deployment, including session resolution, online route ownership, gateway delivery, and user-channel mapping, MUST be abstracted behind replaceable interfaces or transport adapters.

#### Scenario: gateway delivery implementation swap
- **WHEN** deployment mode requires replacing the implementation used to resolve or deliver to an online user
- **THEN** the system uses an alternative adapter without changing message business rules

### Requirement: Internal service contracts use gRPC
Synchronous communication between `access-gateway`, `api-service`, and `message-service` MUST use explicit internal gRPC contracts.

#### Scenario: gateway resolves session through service contract
- **WHEN** an `access-gateway` instance needs to validate a session during bind
- **THEN** it uses an internal gRPC session contract provided by `api-service`

### Requirement: Shared infrastructure has explicit service ownership
When multiple services share PostgreSQL, Redis, or MQ infrastructure, each table, key family, and queue responsibility MUST have a single owning service for writes and lifecycle rules.

#### Scenario: service writes owned coordination data
- **WHEN** the system updates online route state for a user
- **THEN** only the owning `access-gateway` flow writes the authoritative route record for that user

### Requirement: GraalVM native image compatibility
The build pipeline MUST support GraalVM native image packaging for the service.

#### Scenario: native image build run
- **WHEN** native build profile is executed
- **THEN** the project produces native binary artifact with required runtime metadata

### Requirement: Token-bucket rate limiting handler
The Netty pipeline MUST include a configurable token-bucket rate limiting handler for inbound client messages. Rate limiting MUST be enforced per channel (per user session), and timer-driven refills SHOULD use Netty `HashedWheelTimer`.

#### Scenario: rate limit exceeded
- **WHEN** a client exceeds configured token consumption rate or bucket capacity
- **THEN** server throttles or rejects excess messages according to configured policy
