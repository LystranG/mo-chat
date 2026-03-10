## MODIFIED Requirements

### Requirement: Session storage and cache layering
`api-service` MUST issue sessions and MUST persist authoritative session records in Redis. Any local caches used by `api-service` or `access-gateway` instances MUST be treated as non-authoritative accelerators, and session authorization decisions MUST resolve to the Redis-backed session state exposed by `api-service`.

#### Scenario: gateway validates bind through session authority
- **WHEN** an `access-gateway` instance receives a bind request with a session ID
- **THEN** it validates the session through `api-service` and receives an authorization result derived from the authoritative Redis-backed session record

### Requirement: Session-bound chat authorization
All chat TCP requests MUST be processed only after the owning `access-gateway` instance has resolved and bound the connection through `api-service`. Chat requests on unbound, invalid, expired, or replaced sessions MUST be rejected.

#### Scenario: invalid session in bind request
- **WHEN** a client attempts to bind a chat connection with an unknown, expired, or invalidated session ID
- **THEN** the gateway rejects the bind and does not process later chat traffic on that connection

## ADDED Requirements

### Requirement: Session versions fence stale connections
Each active session used for gateway bind MUST carry a session version that can be used to reject stale connection ownership and stale targeted delivery attempts.

#### Scenario: stale session version is rejected
- **WHEN** a gateway or internal delivery flow presents a session version that no longer matches the authoritative session record for that user
- **THEN** the system rejects the stale bind or delivery as no longer authorized
