## ADDED Requirements

### Requirement: Private chat E2EE algorithm suite
Private chat text messages MUST use end-to-end encryption with X25519 key agreement and AES-GCM message encryption.

#### Scenario: private message accepted as E2EE payload
- **WHEN** a private chat message is accepted by the server
- **THEN** the server treats the message body as ciphertext payload and does not require plaintext content

### Requirement: Private chat message cryptographic fields
Private chat messages MUST include a per-message `nonce` of exactly 12 bytes and a `ciphertext` field. These fields MUST be transported as protobuf `bytes`.

#### Scenario: invalid nonce length
- **WHEN** a private chat message includes a nonce that is not exactly 12 bytes
- **THEN** the server rejects the message with validation failure

### Requirement: Ciphertext-at-rest for private messages
Private chat message bodies MUST be stored in database as ciphertext only (including the associated nonce). The server MUST NOT store plaintext user content for private messages.

#### Scenario: private message persisted
- **WHEN** persistence worker stores a private message
- **THEN** stored payload is ciphertext and not plaintext user content

### Requirement: Identity public key immutability
Identity public keys MUST be registered at user bootstrap and MUST be immutable for the lifetime of the account.

#### Scenario: attempted key change
- **WHEN** an existing user attempts to authenticate with a different identity public key
- **THEN** the server rejects the request and retains the original persisted key

### Requirement: Public key caching without TTL
The server MAY cache identity public keys in Redis for performance and MUST treat cached keys as permanent (no TTL-based expiry).

#### Scenario: key cache hit
- **WHEN** the server needs a user's public key and Redis cache has an entry
- **THEN** the server uses the cached key value
