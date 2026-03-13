## Context

The repository now treats four dedicated services as the default runtime topology: `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app`. These apps already own distinct entrypoints, ports, and configuration surfaces, but container packaging has not caught up with that runtime split.

Today, only the legacy `app` module contains explicit GraalVM native-image build wiring. The dedicated service apps are standard `application` modules without the shared native plugin, image naming, or native-image availability guard. There are also no service Dockerfiles yet, so container builds would either duplicate local shell steps manually or fall back to JVM-based images that do not match the repository's native-runtime direction.

This change is cross-cutting because each deployable service depends on the same multi-project Gradle build, shared library modules, and consistent runtime conventions, while still exposing different listeners:

- `access-gateway-app`: TCP `9000` + gRPC `19093`
- `api-service-app`: HTTP `8080` + gRPC `19091`
- `message-service-app`: gRPC `19092`
- `persistence-service-app`: background worker with no public HTTP/TCP listener in the current phase

The design therefore needs to define both a shared native build convention and a per-service Docker packaging contract.

## Goals / Non-Goals

**Goals:**
- Make every deployable service app buildable as a GraalVM native binary through a first-class Gradle path.
- Add explicit Dockerfiles for the four dedicated service apps, with one container contract per deployable service.
- Keep Docker builds aligned with the existing service topology, environment keys, and listener ports documented in the runbook.
- Avoid copy-pasted native build logic across service modules by introducing one reusable convention for deployable app modules.

**Non-Goals:**
- Do not package internal shared modules such as `common`, `protocol`, `logic-module`, or `persistence-module` as standalone containers.
- Do not redesign service boundaries, configuration semantics, or internal RPC contracts.
- Do not replace or remove the legacy `app` compatibility shell in this change.
- Do not introduce Kubernetes manifests, Compose service definitions for the dedicated apps, or image publishing automation in this artifact.

## Decisions

### 1. Use one module-local Dockerfile per deployable service, built with repository-root context

Each deployable app module will own its own Dockerfile:

- `access-gateway-app/Dockerfile`
- `api-service-app/Dockerfile`
- `message-service-app/Dockerfile`
- `persistence-service-app/Dockerfile`

The Dockerfiles will be built from the repository root context, for example:

```bash
docker build -f access-gateway-app/Dockerfile .
```

This keeps ownership attached to the actual deployable module while still allowing the build to access:

- root Gradle files (`settings.gradle.kts`, root `build.gradle.kts`, wrapper)
- sibling shared modules used by the app module
- shared docs or scripts that may be needed by the build

Each Dockerfile will use a multi-stage flow:

1. Builder stage with GraalVM/native-image tooling and Gradle wrapper
2. Native compilation for the target service module only
3. Minimal runtime stage that copies in the generated native binary and executes it directly

Rationale:
- One Dockerfile per service makes service-specific ports, entrypoint names, and image tags explicit.
- Repository-root context is mandatory because every service app depends on sibling modules and shared build configuration.
- Multi-stage build keeps the final runtime image aligned with the user's native-image requirement instead of shipping a full JDK image.

Alternatives considered:
- Single generic root Dockerfile with build args for service name: rejected because it hides service ownership, makes per-service contracts less discoverable, and increases the chance of accidental drift between documented runtime shape and actual image usage.
- Building from each module directory as Docker context: rejected because the service modules do not contain a self-sufficient build context; they rely on root Gradle files and sibling modules.

### 2. Extract the native-image Gradle setup into a shared convention for deployable app modules

The current `app/build.gradle.kts` already proves the repository can support GraalVM native-image builds, including an availability guard that skips native compilation when `native-image` is not present. That logic should be generalized into one reusable convention and then applied to:

- `access-gateway-app`
- `api-service-app`
- `message-service-app`
- `persistence-service-app`

The convention should cover:

- applying `org.graalvm.buildtools.native`
- adding `io.micronaut:micronaut-graal` annotation processing where needed
- setting a per-service `imageName`
- keeping the current "skip when native-image is unavailable" behavior so ordinary JVM development is not blocked

Rationale:
- The build surface is cross-cutting and should not be maintained by copy-pasting the same native configuration four times.
- The existing `app` logic is already the repository's proven baseline; reusing its behavior reduces risk.
- Service-specific binary names are needed so Dockerfiles can copy a stable output path for each app.

Alternatives considered:
- Copy the native plugin block into every service module manually: rejected because it creates long-term drift risk and makes future tuning harder.
- Keep native-image support only at the legacy `app` layer and build service containers from JVM jars: rejected because it contradicts the requested runtime model and leaves the default microservice topology without first-class native packaging.

### 3. Run the native binaries in a minimal glibc-compatible runtime image as non-root

The runtime stage should execute the native binary directly rather than launching a JVM. The base image should remain small but provide the glibc-compatible environment and CA certificates expected by Micronaut, Netty, Redis, PostgreSQL, and RocketMQ client paths embedded in the native binary. The runtime image should also default to a non-root user.

Service runtime contracts:

- `access-gateway-app` image exposes TCP `9000` and gRPC `19093`
- `api-service-app` image exposes HTTP `8080` and gRPC `19091`
- `message-service-app` image exposes gRPC `19092`
- `persistence-service-app` image does not need public listener exposure in the current phase, but still runs as a dedicated containerized process

The container entrypoint should be the copied service binary itself, preserving the existing environment-variable-based configuration contract already encoded in each module's `application.yml`.

Rationale:
- This satisfies the "container uses GraalVM native image runtime" requirement in the operational sense: the container runs a native binary, not a JVM.
- A minimal runtime base reduces attack surface and startup overhead while staying compatible with dynamically linked native-image outputs.
- Running as non-root is the safer default and does not conflict with the current port choices.

Alternatives considered:
- Reuse the GraalVM builder image as the final runtime image: rejected because it produces unnecessarily large images and undermines the point of native-image packaging.
- Use `scratch`: rejected because the current native-image outputs are expected to be dynamically linked and benefit from a standard C runtime and cert bundle.

### 4. Preserve existing service configuration keys and document container-specific build/run entrypoints instead of inventing a second config model

The Dockerfiles should not introduce a new configuration namespace. They should preserve the current environment contract from each service's `application.yml`, including:

- `MOCHAT_REDIS_URI`
- `MOCHAT_POSTGRES_*`
- `MOCHAT_ROCKETMQ_*`
- `MOCHAT_API_SERVICE_GRPC_ADDRESS`
- `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS`
- `MOCHAT_ACCESS_GATEWAY_*`

Container-specific work should therefore focus on:

- explicit build commands for each image
- explicit runtime ports and entrypoints
- any Docker-oriented defaults that are safe to inherit from the current service configs

Rationale:
- The repository already documents these keys in the runbook, and the dedicated services use them as their source of truth.
- Reusing the same config surface keeps local multi-process runs and container runs conceptually aligned.

Alternatives considered:
- Add container-only config wrappers or additional indirection scripts: rejected because they create another configuration surface without solving a real runtime problem.

## Risks / Trade-offs

- [Native-image reflection/resource gaps differ across services] -> Mitigation: keep native compilation per service explicit and verify each dedicated app with its own `:module:nativeCompile` path rather than assuming the legacy `app` coverage is sufficient.
- [Docker builds become slow because each service rebuilds shared modules] -> Mitigation: use repository-root context plus Gradle cache-aware layering in the builder stage, and keep the shared native convention centralized so cache behavior stays consistent.
- [Container runtime behavior differs from local JVM runs, especially for `access-gateway` networking] -> Mitigation: rely on the existing runtime fallback strategy for Netty transport and add focused container/native smoke verification for listener startup.
- [Per-service Dockerfiles drift over time] -> Mitigation: keep service-specific content limited to module target, binary name, and exposed ports; move shared build logic into the Gradle convention instead of the Dockerfiles.

## Migration Plan

1. Move the existing native-image baseline from the legacy `app` module into a shared Gradle convention that dedicated service apps can reuse.
2. Apply that convention to the four deployable service app modules and assign stable native binary names per service.
3. Add one Dockerfile per deployable service module using repository-root build context and multi-stage native compilation.
4. Update the runbook with image build commands, service runtime ports, and container execution examples aligned with the existing environment-variable contract.
5. Verify at least the focused service-native build paths and Docker image builds for the dedicated service modules before treating the change as complete.

Rollback:
- Keep the legacy `app` native/JVM compatibility path intact during this change.
- If the new service Docker packaging proves unstable, stop using the new per-service images and continue running the dedicated services from Gradle/local processes or fall back to the legacy compatibility shell. No schema or protocol rollback is required because this change only affects packaging and runtime assembly.

## Open Questions

- Should the repository also gain a Dockerfile for the legacy `app` compatibility shell, or should that path remain intentionally undocumented for container deployment? Current recommendation: leave it out of scope for this change and treat the four dedicated services as the only first-class deployment targets.
- Should image publishing automation be added in CI immediately, or remain a later follow-up once the per-service native Dockerfiles are stable? Current recommendation: defer CI publishing and complete local/native verification first.
