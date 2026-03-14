## MODIFIED Requirements

### Requirement: Bind establishes gateway ownership
The system MUST treat a user as owned by an `access-gateway` instance only after the gateway successfully resolves the session and completes a bind. A successful bind MUST write a single active online route record for that user in Redis. In Kubernetes-native deployment, the owning gateway identity recorded in that route MUST be the stable runtime identity of the owning gateway replica rather than a manually assigned peer target alias.

#### Scenario: successful bind writes active route
- **WHEN** a client connects to an `access-gateway` instance and presents a valid session for bind
- **THEN** the gateway writes an online route record for that user containing the owning gateway identity, connection identity, session identity, session version, and route epoch

#### Scenario: route records stable gateway identity in Kubernetes
- **WHEN** a bind succeeds on an `access-gateway` replica running in Kubernetes
- **THEN** the persisted route identifies the owner by that gateway replica's stable runtime identity so later delivery can resolve the exact owner pod

### Requirement: Targeted cross-pod delivery
`message-service` MUST resolve the recipient's active route from Redis and MUST deliver to the owning `access-gateway` instance through internal RPC using the expected route epoch. In Kubernetes-native deployment, the RPC target MUST be derived from the owning gateway's stable identity and pod-addressable DNS rather than from a manually maintained gateway target map.

#### Scenario: recipient is bound on another gateway
- **WHEN** a sender submits a message and the recipient's active route points to a different `access-gateway` instance
- **THEN** `message-service` sends a targeted internal delivery request to that specific gateway instance with the expected route epoch

#### Scenario: owner gateway is resolved through pod-addressable DNS
- **WHEN** `message-service` performs targeted delivery in Kubernetes for a recipient bound on another gateway
- **THEN** it derives the destination gateway RPC address from the owning gateway identity and the headless-Service pod DNS contract

### Requirement: Gateway drain semantics
An `access-gateway` instance entering drain mode MUST stop accepting new binds, MUST continue serving existing bound connections during a grace period, and MUST close remaining bound connections when the grace period ends so clients reconnect elsewhere. In Kubernetes-native deployment, a draining gateway MUST also stop participating in new ingress selection before pod termination completes.

#### Scenario: gateway drains before shutdown
- **WHEN** an `access-gateway` instance is marked for scale-in or rollout shutdown
- **THEN** the gateway rejects new bind ownership, serves existing bound connections during the configured grace period, and closes remaining bound connections after that period expires

#### Scenario: draining gateway stops receiving new ingress before termination
- **WHEN** a Kubernetes-hosted `access-gateway` pod enters rollout or scale-in termination
- **THEN** the pod is removed from new ingress selection before termination finishes draining existing owned connections
