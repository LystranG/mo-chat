## ADDED Requirements

### Requirement: Passwordless user bootstrap login with immutable identity key
The HTTP login endpoint MUST accept a username and MUST NOT require a password. If the user does not exist, the request MUST include a base64-encoded X25519 identity public key; the server MUST validate and persist that key and MUST treat it as immutable.

#### Scenario: first-time login without public key
- **WHEN** a username that does not exist submits login request without a public key
- **THEN** the server rejects the request with validation failure

#### Scenario: first-time login with invalid public key encoding
- **WHEN** a username that does not exist submits login request with a public key that fails base64 decoding or is not 32 bytes
- **THEN** the server rejects the request with validation failure

#### Scenario: first-time login with valid public key
- **WHEN** a username that does not exist submits login request with a base64 public key that decodes to 32 bytes
- **THEN** the system creates the user, persists the identity public key, and returns a valid session ID

#### Scenario: existing user login with mismatched public key
- **WHEN** an existing user submits login request with a public key that differs from the persisted identity key
- **THEN** the server rejects the request and does not modify the persisted identity key

### Requirement: Session storage and cache layering
The system MUST persist session IDs in Redis and MUST maintain a Caffeine L2 cache for session lookups.

#### Scenario: session lookup on chat auth
- **WHEN** chat message authentication checks a session ID
- **THEN** the system validates against cache and Redis with consistent authorization result

### Requirement: Session-bound chat authorization
All chat TCP requests MUST carry a valid session ID and MUST be rejected when session validation fails.

#### Scenario: invalid session in chat request
- **WHEN** a chat request contains an unknown or expired session ID
- **THEN** the server responds with an `ERROR_RESPONSE` carrying `SESSION_INVALID (1000)` and does not process the message
