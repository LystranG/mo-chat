## MODIFIED Requirements

### Requirement: Heartbeat timeout cleanup
The server MUST send heartbeat probes every configured interval, MUST renew the active gateway route lease only for bound connections that still own the current route, and MUST release channel resources when heartbeat ACK is missing within timeout or when route ownership is no longer current.

#### Scenario: heartbeat ACK timeout closes owned connection
- **WHEN** heartbeat response is not received within the configured timeout window for a bound connection that still owns the active route
- **THEN** the gateway marks the user offline, releases route ownership, and closes the channel

## ADDED Requirements

### Requirement: Bind completes before chat traffic is accepted
An `access-gateway` instance MUST accept user chat traffic only after the connection has completed session bind successfully.

#### Scenario: unbound connection sends chat message
- **WHEN** a TCP connection that has not completed session bind sends a chat message
- **THEN** the gateway rejects the message and does not treat the connection as an online user route

### Requirement: Duplicate login replaces the older bound connection
When a newer successful bind takes ownership for a user, the previously bound gateway connection for that user MUST be treated as stale and MUST be closed when the older gateway observes the replaced route epoch.

#### Scenario: older gateway observes replaced route
- **WHEN** a user binds on a newer gateway instance and the older gateway later checks heartbeat or receives an explicit replacement signal
- **THEN** the older gateway closes its stale bound connection for that user

### Requirement: Draining gateways do not accept new ownership
An `access-gateway` instance in drain mode MUST reject new bind ownership while continuing to serve already bound connections until the configured grace period ends.

#### Scenario: new bind hits draining gateway
- **WHEN** a client attempts to bind on a gateway instance that is already in drain mode
- **THEN** the gateway rejects the bind ownership attempt so the client reconnects to another gateway instance
