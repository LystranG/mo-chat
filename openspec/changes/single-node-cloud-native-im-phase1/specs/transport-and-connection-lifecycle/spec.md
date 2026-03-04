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
The server MUST require TLS 1.3 handshake for all chat TCP channels and MUST reject non-TLS connections.

#### Scenario: client connects without TLS
- **WHEN** a client attempts to establish a plain TCP channel
- **THEN** the server closes the channel and reports a transport security failure

### Requirement: Heartbeat timeout cleanup
The server MUST send heartbeat probes every configured interval and MUST release channel resources when heartbeat ACK is missing within timeout.

#### Scenario: heartbeat ACK timeout
- **WHEN** heartbeat response is not received within configured timeout window
- **THEN** the server marks the session offline and closes the channel
