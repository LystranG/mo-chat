# Phase 1 Local Runbook

This runbook captures local startup, verification commands, and known issues for the current Phase 1 stack state.

## Prerequisites

- Podman with compose support (`podman compose`)
- JDK 25 available for Gradle builds
- OpenSSL (for local TLS certificate generation)

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

### Verified outcome (2026-03-07)

- `podman compose up -d` started Postgres, Redis, RocketMQ NameServer, and RocketMQ Broker.
- `podman compose ps` showed all four dependency services in `Up` state.
- `for port in 5432 6379 9876 10909 10911 10912; ...; done` printed `ok:<port>` for all dependency ports.
- `podman logs ddd-demo-rocketmq-broker | rg 'boot success'` confirmed broker startup succeeded.
- Local app defaults were aligned with `docker-compose.yml`: PostgreSQL now defaults to `jdbc:postgresql://localhost:5432/mochat` with `mochat` / `mochat` credentials.

## Build, test, and run commands

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
curl -fsS http://127.0.0.1:8080/friends
```

### Verified outcome (2026-03-07)

- `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew :persistence-module:test --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew test --rerun-tasks`: `BUILD SUCCESSFUL`
- `./gradlew :app:run` with local dependencies running opened both `8080` and `9000` listeners.
- `curl -fsS http://127.0.0.1:8080/friends` returned the scaffold response payload from `FriendsController`.
- `./gradlew :app:nativeCompile` was not re-verified in this round.

Interpretation:

- `app` is no longer CLI-only bootstrap; it now starts the Micronaut HTTP server and the Netty TCP listener as a composed runtime.
- Startup now eagerly creates PostgreSQL, Redis, and RocketMQ clients, runs Flyway migrations by default, then starts HTTP and TCP listeners.
- The RocketMQ persistence consumer also starts on boot unless `mochat.rocketmq.consumer.enabled=false`.

## Config keys overview

Current runtime keys in `app/src/main/resources/application.yml`:

- HTTP server: `micronaut.server.host`, `micronaut.server.port`, `mochat.http.host`, `mochat.http.port`
- Netty TCP: `mochat.netty.tcp.enabled`, `mochat.netty.tcp.host`, `mochat.netty.tcp.port`, `mochat.netty.tcp.io-uring.preferred`, `mochat.netty.tcp.frame.max-length`, `mochat.netty.tcp.heartbeat.interval`, `mochat.netty.tcp.heartbeat.timeout`
- Flyway: `mochat.flyway.migrate-on-start`, `mochat.flyway.locations`
- Redis: `mochat.redis.uri`, `mochat.redis.topic-prefix`
- PostgreSQL: `mochat.postgres.url`, `mochat.postgres.username`, `mochat.postgres.password`
- RocketMQ: `mochat.rocketmq.name-server`, `mochat.rocketmq.producer-group`, `mochat.rocketmq.consumer.enabled`, `mochat.rocketmq.consumer-group`, `mochat.rocketmq.topic`
- TLS and IDs: `mochat.tls.enabled`, `mochat.tls.certificate-path`, `mochat.tls.private-key-path`, `mochat.id.worker-id`

Default local values now line up with the compose stack:

- PostgreSQL: `jdbc:postgresql://localhost:5432/mochat`, user `mochat`, password `mochat`
- Redis: `redis://localhost:6379`
- RocketMQ NameServer: `localhost:9876`
- HTTP / TCP listeners: `8080` / `9000`

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

`NettyChatServer.buildTls13Context` expects a certificate chain file and private key file. Generate local PEM files with:

```bash
mkdir -p certs/dev
openssl req -x509 -nodes -newkey rsa:2048 -sha256 -days 365 \
  -keyout certs/dev/server.key \
  -out certs/dev/server.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Use `certs/dev/server.crt` and `certs/dev/server.key` when wiring TLS bootstrap.

## Native build fallback note

- Preferred command: `./gradlew :app:nativeCompile`
- If `native-image` is unavailable in `javaLauncher`, `GRAALVM_HOME`, `JAVA_HOME`, or `java.home`, the build logic in `app/build.gradle.kts` skips native compilation.
- Fallback path for local validation:

```bash
./gradlew :app:build
./gradlew :app:run
```

This verifies JVM build and startup path without requiring a GraalVM native toolchain.
