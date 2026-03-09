## ADDED Requirements

### Requirement: Messaging and persistence module separation
The codebase MUST separate message transport/business flow from persistence workflow into independent modules that interact through explicit interfaces or events.

#### Scenario: message accepted in messaging module
- **WHEN** messaging module accepts a message for downstream handling
- **THEN** persistence module is invoked through interface/event contract rather than direct internal data access

### Requirement: Cloud-native evolution abstractions
Core runtime dependencies that may change in distributed deployment (e.g., user-channel mapping) MUST be abstracted behind replaceable manager interfaces.

#### Scenario: channel mapping implementation swap
- **WHEN** deployment mode requires replacing in-memory channel manager
- **THEN** system can use an alternative implementation without changing business handlers

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
