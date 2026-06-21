# Runtime Integration and User Persistence Design

## Goal

Push MoChat from a module collection to a minimally runnable integrated service while preserving existing module boundaries. This round includes app runtime assembly, PostgreSQL-backed `UserRepository`, and a real RocketMQ consume -> DB persist -> commit-then-ack skeleton.

## Confirmed Constraints

- Work happens in an isolated worktree so the dirty main worktree stays untouched.
- Do not modify the protected local files listed in the handoff.
- Do not commit any changes in this round.
- Prefer owner-module changes over broad refactors.

## Recommended Approach

Use `app` as the composition root and keep domain logic in its existing modules:

- `app` wires Micronaut runtime dependencies, configuration, lifecycle beans, and startup paths.
- `logic-module` gains a JDBC `UserRepository` implementation while keeping `UserService` semantics unchanged.
- `persistence-module` gains a thin RocketMQ consumer adapter that delegates transaction handling to the existing `MqConsumer`.
- Existing controller, connection, logic, Redis, and persistence classes are reused rather than redesigned.

This keeps the integration surface explicit, avoids collapsing module boundaries into `app`, and delivers the highest-value runnable skeleton first.

## Runtime Assembly Design

### App module responsibilities

The `app` module will:

- depend on `logic-module`, `connection-module`, `infra-redis`, and `persistence-module`
- enable an embedded Micronaut HTTP server so logic controllers are actually reachable
- define configuration for HTTP, Netty TCP, Redis, PostgreSQL, RocketMQ, TLS, and ID generation
- provide factories for shared runtime infrastructure:
  - `DataSource`
  - Flyway migration bootstrap
  - Redis sync and Pub/Sub connections
  - `EventBus`, `OfflineQueue`, `ConversationSeqGenerator`, `IdempotencyStore`
  - `UserChannelDirectory<Channel>`
  - `IdGenerator`
  - RocketMQ producer and consumer clients
- manage Netty server start/stop as an application lifecycle bean

### Netty startup path

`NettyChatServer` remains a plain runtime class. `app` adds a lifecycle bean that constructs it from config, starts it after the Micronaut context is ready, and stops it during shutdown.

### HTTP startup path

Once the app depends on logic-module and includes Micronaut HTTP server Netty runtime, existing controllers such as `AuthController`, `HistoryController`, and `ConversationController` will be loaded into the final application context.

## User Persistence Design

### Repository location

Add `JdbcUserRepository` inside `logic-module`, matching the existing pattern used by `JdbcHistoryRepository` and `JdbcConversationStateRepository`.

### Semantics

The repository must preserve current behavior:

- first-time login requires a valid 32-byte base64 public key
- existing users may omit `publicKey`
- if an existing user supplies `publicKey`, it must exactly match the persisted key
- persisted keys are immutable

### Concurrency strategy

Use PostgreSQL uniqueness on `users.username` as the source of truth:

- attempt `INSERT ... ON CONFLICT DO NOTHING RETURNING ...`
- if insert succeeds, return the new row
- if insert does not return a row, load the existing row and return it

This allows `UserService` to keep its current post-create equality check and still remain correct under concurrent first-login races.

### Fallback strategy

Keep `InMemoryUserRepository` only as a fallback bean when `DataSource` is absent.

## RocketMQ Persistence Loop Design

### Goal

Connect the real runtime path:

1. RocketMQ consumer receives message
2. payload is decoded into `PersistedMessage`
3. `MqConsumer.persistMessage(...)` runs the DB transaction
4. only after transaction success does the consumer acknowledge success to RocketMQ

### Scope

This round delivers the minimal real loop, not a final production-grade consumer:

- single-topic consumption for `mochat.messages`
- reuse existing string envelope contract to avoid extra protocol churn
- no large contract refactor or batch optimization yet

### Placement

The consumer adapter belongs to `persistence-module` because it owns the persistence workflow. `app` is responsible only for configuration and bean construction.

## Deferred Items

These remain explicitly out of the main path for this round unless time remains after the top goals pass verification:

- history cursor model unification (`msgId` vs `seq`)
- social graph and group management implementation
- stronger private-message validation rules beyond what already exists
- structured event contracts replacing string payloads

## Test Strategy

Follow TDD task-by-task:

1. write failing tests for app runtime assembly, JDBC user persistence, and RocketMQ consume/ack behavior
2. implement the minimum code to satisfy each failing test
3. re-run focused tests after each task
4. finish with `./gradlew test`

## Acceptance Markers For This Round

- `app` starts with embedded HTTP server and Netty lifecycle path wired
- runtime config covers Redis, PostgreSQL, RocketMQ, and TLS basics
- `JdbcUserRepository` replaces the in-memory implementation when `DataSource` exists
- RocketMQ persistence consumer acknowledges only after DB commit succeeds
- all tests pass without touching protected dirty files
