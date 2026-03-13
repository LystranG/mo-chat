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

### Dedicated service native and Docker image builds

Repository-root native build commands:

```bash
./gradlew :access-gateway-app:nativeCompile
./gradlew :api-service-app:nativeCompile
./gradlew :message-service-app:nativeCompile
./gradlew :persistence-service-app:nativeCompile
```

Expected native outputs:

- `access-gateway-app/build/native/nativeCompile/access-gateway`
- `api-service-app/build/native/nativeCompile/api-service`
- `message-service-app/build/native/nativeCompile/message-service`
- `persistence-service-app/build/native/nativeCompile/persistence-service`

Repository-root Podman image builds:

```bash
podman build -f access-gateway-app/Dockerfile -t mochat/access-gateway:dev .
podman build -f api-service-app/Dockerfile -t mochat/api-service:dev .
podman build -f message-service-app/Dockerfile -t mochat/message-service:dev .
podman build -f persistence-service-app/Dockerfile -t mochat/persistence-service:dev .
```

The Dockerfiles must be built from repository root so the build context includes the root Gradle files and sibling shared modules.

Example container runs on a shared bridge network after PostgreSQL, Redis, and RocketMQ are reachable from that network:

```bash
podman network create mochat-net
```

```bash
podman run --rm --network mochat-net --name api-service \
  -p 8080:8080 -p 19091:19091 \
  -e MOCHAT_REDIS_URI=redis://redis:6379 \
  -e MOCHAT_POSTGRES_URL=jdbc:postgresql://postgres:5432/mochat \
  -e MOCHAT_POSTGRES_USERNAME=mochat \
  -e MOCHAT_POSTGRES_PASSWORD=mochat \
  mochat/api-service:dev
```

```bash
podman run --rm --network mochat-net --name message-service \
  -p 19092:19092 \
  -e MOCHAT_REDIS_URI=redis://redis:6379 \
  -e MOCHAT_ROCKETMQ_NAME_SERVER=rocketmq-namesrv:9876 \
  -e MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091 \
  -e JAVA_TOOL_OPTIONS='-Dmochat.message-service.route.gateway-targets.gateway-a=access-gateway-a:19093' \
  mochat/message-service:dev
```

```bash
podman run --rm --network mochat-net --name access-gateway-a \
  -p 9000:9000 -p 19093:19093 \
  -e MOCHAT_REDIS_URI=redis://redis:6379 \
  -e MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD=gateway-a \
  -e MOCHAT_API_SERVICE_GRPC_ADDRESS=api-service:19091 \
  -e MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=message-service:19092 \
  -e JAVA_TOOL_OPTIONS='-Dmochat.access-gateway.route.peer-targets.gateway-b=access-gateway-b:19094' \
  mochat/access-gateway:dev
```

```bash
podman run --rm --network mochat-net --name persistence-service \
  -e MOCHAT_REDIS_URI=redis://redis:6379 \
  -e MOCHAT_POSTGRES_URL=jdbc:postgresql://postgres:5432/mochat \
  -e MOCHAT_POSTGRES_USERNAME=mochat \
  -e MOCHAT_POSTGRES_PASSWORD=mochat \
  -e MOCHAT_ROCKETMQ_NAME_SERVER=rocketmq-namesrv:9876 \
  mochat/persistence-service:dev
```

Containerized runs keep the same runtime contract as the process-based dedicated topology: the same `MOCHAT_*` environment variables still apply, and route-target maps are still passed explicitly through `JAVA_TOOL_OPTIONS` until dynamic service discovery is introduced.

Fresh dedicated-service verification (2026-03-13):

- `command -v native-image && native-image --version` resolved `/home/lystran/.local/share/mise/installs/java/oracle-graalvm-25.0.1/bin/native-image` and reported Oracle GraalVM `25.0.1`.
- `./gradlew :access-gateway-app:nativeCompile -g .gradle --rerun-tasks --console=plain` completed with `BUILD SUCCESSFUL` and produced `access-gateway-app/build/native/nativeCompile/access-gateway`.
- `./gradlew :api-service-app:nativeCompile -g .gradle --rerun-tasks --console=plain` completed with `BUILD SUCCESSFUL` and produced `api-service-app/build/native/nativeCompile/api-service`.
- `./gradlew :message-service-app:nativeCompile -g .gradle --rerun-tasks --console=plain` completed with `BUILD SUCCESSFUL` and produced `message-service-app/build/native/nativeCompile/message-service`.
- `./gradlew :persistence-service-app:nativeCompile -g .gradle --rerun-tasks --console=plain` completed with `BUILD SUCCESSFUL` and produced `persistence-service-app/build/native/nativeCompile/persistence-service`.
- Shared GraalVM native support for this verification includes `resources.autodetect()` so `application.yml` is embedded, `service-runtime` native defaults for Netty under native runtime, and shared reflection metadata for Caffeine bounded caches used by `api-service` and `persistence-service`.
- `podman build -f access-gateway-app/Dockerfile -t mochat/access-gateway:dev .` completed with `Successfully tagged localhost/mochat/access-gateway:dev`.
- `podman build -f api-service-app/Dockerfile -t mochat/api-service:dev .` completed with `Successfully tagged localhost/mochat/api-service:dev`.
- `podman build -f message-service-app/Dockerfile -t mochat/message-service:dev .` completed with `Successfully tagged localhost/mochat/message-service:dev`.
- `podman build -f persistence-service-app/Dockerfile -t mochat/persistence-service:dev .` completed with `Successfully tagged localhost/mochat/persistence-service:dev`.
- The verified Dockerfile path used `ghcr.1ms.run/graalvm/native-image-community:25` for the builder stage and `gcr.1ms.run/distroless/cc` for the runtime stage across all four dedicated service images.
- `gradle/wrapper/gradle-wrapper.properties` now points to `https://mirrors.aliyun.com/gradle/distributions/v9.3.1/gradle-9.3.1-bin.zip`; this was required because containerized `./gradlew` still hit `services.gradle.org` before the change and failed with `javax.net.ssl.SSLHandshakeException`.
- `podman compose up -d` followed by `podman compose ps` brought PostgreSQL, Redis, RocketMQ NameServer, and RocketMQ Broker into `Up` state, and `for port in 5432 6379 9876 10909 10911 10912; do ...; done` reported `ok:<port>` for all six ports.
- Minimal smoke verification was run against the freshly built host native binaries, using the same native entrypoints that the Dockerfiles copy into the distroless images.

`access-gateway` smoke command:

```bash
MOCHAT_ACCESS_GATEWAY_RUNTIME_ENABLED=true \
MOCHAT_ACCESS_GATEWAY_TCP_ENABLED=false \
MOCHAT_ACCESS_GATEWAY_REDIS_ENABLED=false \
MOCHAT_ACCESS_GATEWAY_API_GRPC_ENABLED=true \
MOCHAT_ACCESS_GATEWAY_GRPC_PORT=49093 \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:59999 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:59998 \
  access-gateway-app/build/native/nativeCompile/access-gateway
```

- Result: `Startup completed in 35ms. Server Running: http://localhost:49093`, and `ss -ltn` reported listener `*:49093`.

`api-service` smoke command:

```bash
MOCHAT_API_SERVICE_POSTGRES_ENABLED=false \
MOCHAT_API_SERVICE_HTTP_PORT=48080 \
MOCHAT_API_SERVICE_GRPC_PORT=49191 \
MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS=127.0.0.1:59997 \
  api-service-app/build/native/nativeCompile/api-service
```

- Result: `Startup completed in 131ms. Server Running: http://0.0.0.0:48080`, and `ss -ltn` reported listeners `*:48080` and `*:49191`.

`message-service` smoke command:

```bash
MOCHAT_REDIS_URI=redis://127.0.0.1:6379 \
MOCHAT_MESSAGE_SERVICE_GRPC_PORT=49092 \
MOCHAT_MESSAGE_SERVICE_API_GRPC_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_GATEWAY_GRPC_ENABLED=false \
MOCHAT_MESSAGE_SERVICE_REDIS_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_MQ_ENABLED=true \
MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=false \
MOCHAT_API_SERVICE_GRPC_ADDRESS=127.0.0.1:59998 \
MOCHAT_ROCKETMQ_NAME_SERVER=127.0.0.1:9876 \
  message-service-app/build/native/nativeCompile/message-service
```

- Result: `Startup completed in 78ms. Server Running: http://localhost:49092`, and `ss -ltn` reported listener `*:49092`.
- `message-service-app` now supplies a default `MOCHAT_ROCKETMQ_PRODUCER_GROUP=mochat-message-producer`, so the smoke no longer needs a one-off producer-group override.

`persistence-service` smoke command:

```bash
MOCHAT_PERSISTENCE_SERVICE_QUEUE_CONSUMER_ENABLED=false \
MOCHAT_PERSISTENCE_SERVICE_FLYWAY_MIGRATE_ON_START=false \
MOCHAT_REDIS_URI=redis://127.0.0.1:6379 \
MOCHAT_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/mochat \
MOCHAT_POSTGRES_USERNAME=mochat \
MOCHAT_POSTGRES_PASSWORD=mochat \
MOCHAT_ROCKETMQ_NAME_SERVER=127.0.0.1:9876 \
  persistence-service-app/build/native/nativeCompile/persistence-service
```

- Result: process exited `0` and logged `No embedded container found. Running as CLI application`.

Smoke-test constraints confirmed by this run:

- `access-gateway` does not support `MOCHAT_ACCESS_GATEWAY_API_GRPC_ENABLED=false` in production wiring; the minimal valid smoke keeps the API gRPC stub enabled while turning off TCP and Redis.
- `message-service` does not support disabling Redis, MQ, and API gRPC together in production wiring; the minimal valid smoke still requires Redis, RocketMQ, and an API gRPC stub, while `gateway-grpc` can be turned off.
- To avoid the earlier TLS handshake failures seen inside containerized Gradle resolution, the repository now prefers `https://maven.aliyun.com/repository/gradle-plugin` for Gradle plugins, `https://maven.aliyun.com/repository/public` for Maven dependencies, and the Aliyun Gradle distribution mirror for the wrapper.

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
