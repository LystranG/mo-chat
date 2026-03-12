## ADDED Requirements

### Requirement: Bind establishes gateway ownership
The system MUST treat a user as owned by an `access-gateway` instance only after the gateway successfully resolves the session and completes a bind. A successful bind MUST write a single active online route record for that user in Redis.

#### Scenario: successful bind writes active route
- **WHEN** a client connects to an `access-gateway` instance and presents a valid session for bind
- **THEN** the gateway writes an online route record for that user containing the owning gateway identity, connection identity, session identity, session version, and route epoch

### Requirement: Single active route per user
The system MUST allow at most one active bound gateway route per user. A newer successful bind MUST replace the previous active route and MUST make the older route invalid for future delivery.

#### Scenario: newer bind replaces prior route
- **WHEN** a user who already has an active bound gateway route binds successfully on another gateway instance
- **THEN** the newer bind becomes the only active route for that user and the older route is marked stale by route epoch

### Requirement: Route lease renewal and stale self-termination
An active bound gateway connection MUST renew its route lease through heartbeat activity. A gateway MUST close its local bound connection when the persisted route no longer matches its own session version or route epoch.

#### Scenario: gateway detects stale route during renewal
- **WHEN** a gateway attempts to renew a route lease and finds that Redis holds a different session version or route epoch for the same user
- **THEN** the gateway closes the local bound connection and stops treating itself as the user's owner

### Requirement: Targeted cross-pod delivery
`message-service` MUST resolve the recipient's active route from Redis and MUST deliver to the owning `access-gateway` instance through internal RPC using the expected route epoch.

#### Scenario: recipient is bound on another gateway
- **WHEN** a sender submits a message and the recipient's active route points to a different `access-gateway` instance
- **THEN** `message-service` sends a targeted internal delivery request to that specific gateway instance with the expected route epoch

### Requirement: Delivery fallback after route failure
If a targeted delivery attempt reports that the route is stale, the user is offline, or the channel write failed, `message-service` MUST perform at most one route refresh attempt and MUST enqueue the message to the offline queue when a valid route still cannot complete delivery.

#### Scenario: stale route falls back to offline queue
- **WHEN** `message-service` attempts targeted delivery and the owning gateway reports a stale route, and a refreshed route still does not yield a successful delivery
- **THEN** `message-service` enqueues the message to the offline queue instead of continuing synchronous delivery retries

### Requirement: Gateway drain semantics
An `access-gateway` instance entering drain mode MUST stop accepting new binds, MUST continue serving existing bound connections during a grace period, and MUST close remaining bound connections when the grace period ends so clients reconnect elsewhere.

#### Scenario: gateway drains before shutdown
- **WHEN** an `access-gateway` instance is marked for scale-in or rollout shutdown
- **THEN** the gateway rejects new bind ownership, serves existing bound connections during the configured grace period, and closes remaining bound connections after that period expires
