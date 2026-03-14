## ADDED Requirements

### Requirement: Netty transport initialization with reactor split
The server MUST initialize separate boss and worker Reactor thread groups for connection accept and IO processing, and MUST prefer Linux `io_uring` transport when available (target environment kernel: 6.19.5).

#### Scenario: io_uring is available on host
- **WHEN** the server starts on a Linux host with compatible `io_uring` support
- **THEN** the server initializes Netty with the configured native `io_uring` event loop groups

#### Scenario: io_uring is unavailable on host
- **WHEN** the server starts on an environment without compatible `io_uring`
- **THEN** the server falls back to NIO transport without changing upper-layer protocol behavior

#### Scenario: io_uring initialization fails at runtime
- **WHEN** `io_uring` is detected but Netty fails to initialize or bind using `io_uring`
- **THEN** the server falls back to Netty system default transport and continues startup

### Requirement: TLS 1.3 is mandatory for chat TCP connections
The server MUST require TLS 1.3 handshake for all chat TCP channels and MUST reject non-TLS connections. The default runtime configuration MUST enable TLS and use a generated self-signed certificate when explicit certificate paths are not provided and `mochat.tls.self-signed=true`. Setting `mochat.tls.enabled=false` is invalid and startup MUST fail fast with a clear error. `mochat.tls.certificate-path` and `mochat.tls.private-key-path` MUST be configured together; supplying only one is invalid and startup MUST fail fast with a clear error.

#### Scenario: default startup uses generated self-signed TLS certificate
- **WHEN** the server starts with default transport configuration and no explicit TLS certificate files
- **THEN** the chat TCP listener still boots with TLS 1.3 using a generated self-signed certificate

#### Scenario: explicit TLS certificate chain and private key are both configured
- **WHEN** `mochat.tls.certificate-path` and `mochat.tls.private-key-path` are both supplied at startup and refer to readable, valid, matching certificate-chain and private-key materials
- **THEN** the chat TCP listener boots successfully with TLS 1.3, prefers the configured certificate pair, and does not generate or use a self-signed certificate

#### Scenario: explicit TLS certificate files are both omitted
- **WHEN** `mochat.tls.certificate-path` and `mochat.tls.private-key-path` are both empty at startup
- **THEN** startup succeeds only if `mochat.tls.self-signed=true`, and otherwise fails fast with a clear error

#### Scenario: only one explicit TLS file path is configured
- **WHEN** exactly one of `mochat.tls.certificate-path` or `mochat.tls.private-key-path` is supplied at startup
- **THEN** startup fails fast with a clear error that both TLS file paths must be configured together

#### Scenario: startup config disables TLS
- **WHEN** `mochat.tls.enabled=false` is supplied at startup
- **THEN** startup fails fast with a clear error that TLS is mandatory for chat TCP connections

#### Scenario: client connects without TLS
- **WHEN** a client attempts to establish a plain TCP channel
- **THEN** the server closes the channel and reports a transport security failure

### Requirement: Heartbeat timeout cleanup
The server MUST send heartbeat probes every configured interval, MUST renew the active gateway route lease only for bound connections that still own the current route, and MUST release channel resources when heartbeat ACK is missing within timeout or when route ownership is no longer current.

#### Scenario: heartbeat ACK timeout closes owned connection
- **WHEN** heartbeat response is not received within the configured timeout window for a bound connection that still owns the active route
- **THEN** the gateway marks the user offline, releases route ownership, and closes the channel

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
An `access-gateway` instance in drain mode MUST reject new bind ownership while continuing to serve already bound connections until the configured grace period ends. In Kubernetes-native deployment, entering drain mode MUST also transition the gateway out of readiness for new ingress before pod termination proceeds.

#### Scenario: new bind hits draining gateway
- **WHEN** a client attempts to bind on a gateway instance that is already in drain mode
- **THEN** the gateway rejects the bind ownership attempt so the client reconnects to another gateway instance

#### Scenario: gateway becomes unready before Kubernetes termination
- **WHEN** a Kubernetes-hosted gateway pod begins rollout or scale-in termination
- **THEN** the gateway stops reporting readiness for new ingress before its termination grace period is used to drain existing bound connections
