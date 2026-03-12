# Phase 1 Local Runbook

This runbook captures local startup, verification commands, and known issues for the current Phase 1 stack state.

## Prerequisites

- Podman with compose support (`podman compose`)
- JDK 25 available for Gradle builds
- OpenSSL (optional, for overriding the default self-signed TLS certificate)

## Dependency startup (Postgres, Redis, RocketMQ)

From repository root:

```bash
podman compose up -d
podman compose ps
for port in 5432 6379 9876 10909 10911 10912; do
  ss -ltn | rg -q ":${port}\\b" && echo "ok:${port}" || echo "missing:${port}"
done
```

Expected services:

- `postgres` on `5432`
- `redis` on `6379`
- `rocketmq-namesrv` on `9876`
- `rocketmq-broker` on `10909/10911/10912`

Stop dependencies:

```bash
podman compose down
```

## Dedicated service topology (Phase 1)

Phase 1 now treats the split runtime as the default local topology. Four dedicated services share the same PostgreSQL, Redis, and RocketMQ stack.

| Service | Default listeners | Owns | Depends on |
| --- | --- | --- | --- |
| `api-service` | HTTP `8080`, gRPC `19091` | login, session authority, social graph, history query | PostgreSQL, Redis, optional `message-service` gRPC for offline replay trigger |
| `message-service` | gRPC `19092` | message ingest, idempotency, sender ACK, online delivery orchestration, offline fallback | Redis, RocketMQ, `api-service` gRPC, per-gateway target map |
| `access-gateway` | TCP `9000`, gRPC `19093` per instance | TCP bind, heartbeat, online route ownership, targeted channel delivery | Redis, `api-service` gRPC, `message-service` gRPC, unique `gateway-pod` identity |
| `persistence-service` | no public HTTP/TCP listener | MQ consume, durable persistence, conversation advancement, post-commit cache work | PostgreSQL, Redis, RocketMQ |

Shared infrastructure and cross-service addressing:

- PostgreSQL via `MOCHAT_POSTGRES_URL`, `MOCHAT_POSTGRES_USERNAME`, `MOCHAT_POSTGRES_PASSWORD`
- Redis via `MOCHAT_REDIS_URI`
- RocketMQ via `MOCHAT_ROCKETMQ_NAME_SERVER`, `MOCHAT_ROCKETMQ_TOPIC`
- `access-gateway -> api-service`: `MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091`
- `access-gateway -> message-service`: `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092`
- `message-service -> api-service`: `MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091`
- `api-service -> message-service`: `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092`

Route-aware delivery and duplicate-login fencing:

- `message-service` resolves owners through `mochat.message-service.route.gateway-targets.*`
- each `access-gateway` instance must advertise a unique `MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD`
- cross-gateway replacement kicks require `mochat.access-gateway.route.peer-targets.*`

### Local multi-process startup

One local verification layout is:

- `api-service` on `127.0.0.1:8080` + `127.0.0.1:19091`
- `message-service` on `127.0.0.1:19092`
- `persistence-service` consuming MQ in the background
- `access-gateway-a` on TCP `9000`, gRPC `19093`, `gateway-pod=gateway-a`
- `access-gateway-b` on TCP `9001`, gRPC `19094`, `gateway-pod=gateway-b`

Start them from repository root in separate terminals after `podman compose up -d`:

```bash
./gradlew :api-service-app:run
```

```bash
JAVA_TOOL_OPTIONS='-Dgrpc.channels.api-service.address=127.0.0.1:19091 \
  -Dmochat.message-service.route.gateway-targets.gateway-a=127.0.0.1:19093 \
  -Dmochat.message-service.route.gateway-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :message-service-app:run
```

```bash
./gradlew :persistence-service-app:run
```

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-a \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19093 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9000 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-b=127.0.0.1:19094' \
  ./gradlew :access-gateway-app:run
```

```bash
MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-b \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=19094 \
MOCHAT_ACCESS_GATEWAY_TCP_PORT=9001 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:19091 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:19092 \
JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-a=127.0.0.1:19093' \
  ./gradlew :access-gateway-app:run
```

Quick listener probe:

```bash
for port in 8080 19091 19092 19093 19094 9000 9001; do
  ss -ltn | rg -q ":${port}\\b" && echo "ok:${port}" || echo "missing:${port}"
done
```

Notes:

- `message-service` must know every owning gateway target through `mochat.message-service.route.gateway-targets.*`.
- Each `access-gateway` instance must use a unique `mochat.access-gateway.route.gateway-pod`.
- Cross-gateway duplicate-login kick flow requires `mochat.access-gateway.route.peer-targets.*` on each gateway instance.
- `persistence-service` is intentionally background-only in this phase; no extra HTTP/gRPC port is documented for it yet.

### Rollback posture

Rollback stays at the runtime-entrypoint level rather than the data layer:

- Stop the dedicated `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app` processes.
- Keep PostgreSQL, Redis, and RocketMQ running; the split services and the legacy app use the same shared infrastructure.
- Restart the legacy shell with persistence compatibility re-enabled:

```bash
MOCHAT_LEGACY_PERSISTENCE_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=true \
  ./gradlew :app:run
```

- Route HTTP traffic back to the legacy app on `8080` and TCP traffic back to the legacy app on `9000`.
- Because external TCP/HTTP contracts and shared storage remain unchanged in Phase 1, this rollback does not require schema or payload migration.

### Verified outcome (2026-03-07)

- `podman compose up -d` started Postgres, Redis, RocketMQ NameServer, and RocketMQ Broker.
- `podman compose ps` showed all four dependency services in `Up` state.
- `for port in 5432 6379 9876 10909 10911 10912; ...; done` printed `ok:<port>` for all dependency ports.
- `podman logs ddd-demo-rocketmq-broker | rg 'boot success'` confirmed broker startup succeeded.
- Local app defaults were aligned with `docker-compose.yml`: PostgreSQL now defaults to `jdbc:postgresql://localhost:5432/mochat` with `mochat` / `mochat` credentials.

## Build, test, and compatibility-shell commands

From repository root:

```bash
./gradlew :app:test
./gradlew :app:build
./gradlew test
./gradlew :app:run
./gradlew :app:nativeCompile
```

Runtime probes:

```bash
ss -ltn | rg ':(8080|9000)\b'
session_id=$(curl -fsS -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"runbook-probe","publicKey":"runbook-probe-key"}' | jq -r '.sessionId')
curl -fsS "http://127.0.0.1:8080/friends?sessionId=${session_id}"
```

### Verified outcome (2026-03-07)

- `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew :persistence-module:test --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew test --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew :app:run` with local dependencies running opened both `8080` and `9000` listeners.
- 好友列表探针应以先调用 `POST /auth/login` 取得 `sessionId`，再请求 `GET /friends?sessionId=...` 为准；旧的无参 `GET /friends` 表述已不适用当前接口签名。

### Fresh native verification (2026-03-09)

- Fresh native verification on 2026-03-09: `command -v native-image` resolved `/home/lystran/.local/share/mise/installs/java/oracle-graalvm-25.0.1/bin/native-image`, and `native-image --version` reported Oracle GraalVM `25.0.1`.
- Fresh native verification on 2026-03-09: `./gradlew :app:nativeCompile -g .gradle` completed with `BUILD SUCCESSFUL`, emitted native image completion logs including the output directory, and produced `app/build/native/nativeCompile/mo-chat`.

Interpretation:

- `app` is now a compatibility shell rather than the default production topology.
- Startup still eagerly creates PostgreSQL, Redis, and RocketMQ clients, runs Flyway migrations by default, then starts HTTP and TCP listeners.
- Persistence-side consumers and inbound compatibility wiring only come back when `MOCHAT_LEGACY_PERSISTENCE_ENABLED=true` and related compatibility toggles are explicitly enabled.

## Config keys overview

Current runtime keys in `app/src/main/resources/application.yml`:

- HTTP server: `micronaut.server.host`, `micronaut.server.port`, `mochat.http.host`, `mochat.http.port`
- Netty TCP: `mochat.netty.tcp.enabled`, `mochat.netty.tcp.host`, `mochat.netty.tcp.port`, `mochat.netty.tcp.io-uring.preferred`, `mochat.netty.tcp.frame.max-length`, `mochat.netty.tcp.heartbeat.interval`, `mochat.netty.tcp.heartbeat.timeout`
- Flyway: `mochat.flyway.migrate-on-start`, `mochat.flyway.locations`
- Redis: `mochat.redis.uri`, `mochat.redis.topic-prefix`
- PostgreSQL: `mochat.postgres.url`, `mochat.postgres.username`, `mochat.postgres.password`
- RocketMQ: `mochat.rocketmq.name-server`, `mochat.rocketmq.producer-group`, `mochat.rocketmq.consumer.enabled`, `mochat.rocketmq.consumer-group`, `mochat.rocketmq.topic`
- TLS and IDs: `mochat.tls.enabled`, `mochat.tls.self-signed`, `mochat.tls.certificate-path`, `mochat.tls.private-key-path`, `mochat.id.worker-id`

Default local values now line up with the compose stack:

- PostgreSQL: `jdbc:postgresql://localhost:5432/mochat`, user `mochat`, password `mochat`
- Redis: `redis://localhost:6379`
- RocketMQ NameServer: `localhost:9876`
- HTTP / TCP listeners: `8080` / `9000`
- TLS defaults: `mochat.tls.enabled=true`, `mochat.tls.self-signed=true`；当 `mochat.tls.certificate-path` 与 `mochat.tls.private-key-path` 都为空时，会生成自签名证书启动
- `MOCHAT_TLS_ENABLED=false` is no longer supported; startup fails fast because chat TCP TLS is mandatory

Current exposed service ports:

- PostgreSQL: `5432`
- Redis: `6379`
- RocketMQ NameServer: `9876`
- RocketMQ Broker: `10909`, `10911`, `10912`

## Testcontainers activation

- Gradle `Test` tasks now auto-export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` when `DOCKER_HOST` is unset and `/var/run/docker.sock` is absent.
- Verified in this rootless Podman environment: the following suites now run with `skipped="0"` instead of being skipped:
  - `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/RedisSeqGeneratorTest.java`
  - `logic-module/src/test/java/com/github/lystran/mochat/logic/service/JdbcUserRepositoryIntegrationTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/MigrationSmokeTest.java`
  - `persistence-module/src/test/java/com/github/lystran/mochat/persistence/TransactionalPersistenceTest.java`
- If neither a standard Docker socket nor a rootless Podman socket is available, those classes still use `@Testcontainers(disabledWithoutDocker = true)` and will be skipped as designed.

## TLS certificate generation

Default bootstrap keeps chat TCP on TLS 1.3 with `mochat.tls.enabled=true`. Setting `MOCHAT_TLS_ENABLED=false` is unsupported and now fails fast with a clear mandatory-TLS error.

TLS override rules are:

- `mochat.tls.certificate-path` 与 `mochat.tls.private-key-path` 必须成对配置；只配一边会在启动阶段直接 fail-fast。
- 两个路径都留空时，仅当 `mochat.tls.self-signed=true` 才会生成自签名证书启动；若同时关闭自签名，同样会 fail-fast。
- 显式提供一对有效的证书链与私钥时，运行时优先使用这组材料，而不会回退到自签名证书。

`NettyChatServer.buildTls13Context` expects a certificate chain file and private key file when you want to override the generated self-signed certificate. Generate local PEM files with:

```bash
mkdir -p certs/dev
openssl req -x509 -nodes -newkey rsa:2048 -sha256 -days 365 \
  -keyout certs/dev/server.key \
  -out certs/dev/server.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Use `certs/dev/server.crt` and `certs/dev/server.key` together when overriding the default generated self-signed TLS certificate.

## Transport fallback mode

- Default mode keeps `mochat.netty.tcp.io-uring.preferred=true` and lets the server prefer Linux `io_uring` when available.
- If native transport is unavailable or fails during bootstrap, `NettyChatServer` falls back to Netty system default transport so upper-layer protocol behavior stays unchanged.
- For deterministic local troubleshooting, set `MOCHAT_TCP_IO_URING_PREFERRED=false` to force the non-native path.
- Recommended verification commands:

```bash
./gradlew :connection-module:test --tests com.github.lystran.mochat.connection.NettyChatServerTest --rerun-tasks
MOCHAT_TCP_IO_URING_PREFERRED=false ./gradlew :app:run
```

## Native build fallback note

- Preferred command: `./gradlew :app:nativeCompile`
- Native verification precondition: `native-image --version` must succeed before `:app:nativeCompile` can be treated as evidence that a real native binary was generated.
- Fresh verified result on 2026-03-09: `command -v native-image` resolved `/home/lystran/.local/share/mise/installs/java/oracle-graalvm-25.0.1/bin/native-image`; `native-image --version` reported Oracle GraalVM `25.0.1`; `./gradlew :app:nativeCompile -g .gradle` actually executed native image generation and produced `app/build/native/nativeCompile/mo-chat`.
- If `native-image` is unavailable in `javaLauncher`, `GRAALVM_HOME`, `JAVA_HOME`, or `java.home`, the build logic in `app/build.gradle.kts` skips native compilation.
- When `./gradlew :app:nativeCompile` shows `BUILD SUCCESSFUL` but also `Skipping :app:nativeCompile: native-image is unavailable ...`, or only ends as `UP-TO-DATE`, that is not fresh proof of native binary generation; real verification requires an actual execution that emits the generation logs and output path.
- Fallback path for local validation:

```bash
./gradlew :app:build
./gradlew :app:run
```

This verifies JVM build and startup path without requiring a GraalVM native toolchain.
