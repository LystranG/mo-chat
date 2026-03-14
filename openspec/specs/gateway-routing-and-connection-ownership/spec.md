## Purpose

Define Redis-backed gateway ownership, targeted cross-pod delivery, stale-route fencing, and drain semantics for the dedicated `access-gateway` runtime.

## ADDED Requirements

### Requirement: Bind establishes gateway ownership
The system MUST treat a user as owned by an `access-gateway` instance only after the gateway successfully resolves the session and completes a bind. A successful bind MUST write a single active online route record for that user in Redis. In Kubernetes-native deployment, the owning gateway identity recorded in that route MUST be the stable runtime identity of the owning gateway replica rather than a manually assigned peer target alias.

#### Scenario: successful bind writes active route
- **WHEN** a client connects to an `access-gateway` instance and presents a valid session for bind
- **THEN** the gateway writes an online route record for that user containing the owning gateway identity, connection identity, session identity, session version, and route epoch

#### Scenario: route records stable gateway identity in Kubernetes
- **WHEN** a bind succeeds on an `access-gateway` replica running in Kubernetes
- **THEN** the persisted route identifies the owner by that gateway replica's stable runtime identity so later delivery can resolve the exact owner pod

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
`message-service` MUST resolve the recipient's active route from Redis and MUST deliver to the owning `access-gateway` instance through internal RPC using the expected route epoch. In Kubernetes-native deployment, the RPC target MUST be derived from the owning gateway's stable identity and pod-addressable DNS rather than from a manually maintained gateway target map.

#### Scenario: recipient is bound on another gateway
- **WHEN** a sender submits a message and the recipient's active route points to a different `access-gateway` instance
- **THEN** `message-service` sends a targeted internal delivery request to that specific gateway instance with the expected route epoch

#### Scenario: owner gateway is resolved through pod-addressable DNS
- **WHEN** `message-service` performs targeted delivery in Kubernetes for a recipient bound on another gateway
- **THEN** it derives the destination gateway RPC address from the owning gateway identity and the headless-Service pod DNS contract

### Requirement: Delivery fallback after route failure
If a targeted delivery attempt reports that the route is stale, the user is offline, or the channel write failed, `message-service` MUST perform at most one route refresh attempt and MUST enqueue the message to the offline queue when a valid route still cannot complete delivery.

#### Scenario: stale route falls back to offline queue
- **WHEN** `message-service` attempts targeted delivery and the owning gateway reports a stale route, and a refreshed route still does not yield a successful delivery
- **THEN** `message-service` enqueues the message to the offline queue instead of continuing synchronous delivery retries

### Requirement: Gateway drain semantics
An `access-gateway` instance entering drain mode MUST stop accepting new binds, MUST continue serving existing bound connections during a grace period, and MUST close remaining bound connections when the grace period ends so clients reconnect elsewhere. In Kubernetes-native deployment, a draining gateway MUST also stop participating in new ingress selection before pod termination completes.

#### Scenario: gateway drains before shutdown
- **WHEN** an `access-gateway` instance is marked for scale-in or rollout shutdown
- **THEN** the gateway rejects new bind ownership, serves existing bound connections during the configured grace period, and closes remaining bound connections after that period expires

#### Scenario: draining gateway stops receiving new ingress before termination
- **WHEN** a Kubernetes-hosted `access-gateway` pod enters rollout or scale-in termination
- **THEN** the pod is removed from new ingress selection before termination finishes draining existing owned connections
