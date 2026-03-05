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

### Verified outcome (2026-03-04)

- `podman compose up -d` started all required services: Postgres, Redis, RocketMQ NameServer, RocketMQ Broker.
- `podman compose ps` showed all four services in `Up` state.
- `ss -ltn | rg '5432|6379|9876|10909|10911|10912'` confirmed listeners for all expected dependency ports.
- `podman logs ddd-demo-rocketmq-broker | rg 'boot success'` confirmed broker startup succeeded.
- `docker-compose.yml` runs broker with image defaults (no host-mounted `broker.conf` or `store` paths) because those bind mounts triggered startup instability in this environment.

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
curl -fsS http://127.0.0.1:8080/health
```

### Verified outcome (2026-03-04)

- `./gradlew :app:test`: `BUILD SUCCESSFUL`
- `./gradlew :app:build`: `BUILD SUCCESSFUL`
- `./gradlew test`: `BUILD SUCCESSFUL` (all module tests up-to-date)
- `./gradlew :app:run`: `BUILD SUCCESSFUL`, then exits quickly with `No embedded container found. Running as CLI application`
- `./gradlew :app:nativeCompile`: `BUILD SUCCESSFUL` (`UP-TO-DATE` in this environment)
- `ss -ltn | rg ':(8080|9000)\b'`: no app listener ports found.
- `curl -fsS http://127.0.0.1:8080/health`: failed with `Could not connect to server`.

Interpretation:

- Current `app` module is still CLI-mode bootstrap (no embedded HTTP server bean).
- HTTP endpoint and Netty TCP listener verification are therefore **blocked by current implementation scope**, not by environment.

## Config keys overview

Current keys present in repository configuration:

- `micronaut.application.name` (`app/src/main/resources/application.yml`): `mochat`
- `POSTGRES_USER` (`docker-compose.yml`): `mochat`
- `POSTGRES_PASSWORD` (`docker-compose.yml`): `mochat`
- `NAMESRV_ADDR` (`docker-compose.yml`, broker container): `rocketmq-namesrv:9876`

Current exposed service ports:

- PostgreSQL: `5432`
- Redis: `6379`
- RocketMQ NameServer: `9876`
- RocketMQ Broker: `10909`, `10911`, `10912`

Note: the Phase 1 design expects additional runtime keys for Netty/TLS/Redis/RocketMQ/Postgres/io_uring/heartbeat/workerId, but those keys are not fully wired into app runtime configuration yet.

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
