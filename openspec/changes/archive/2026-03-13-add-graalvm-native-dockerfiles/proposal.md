## Why

The repository now defaults to dedicated microservice runtimes, but container packaging still lags behind that topology: only the legacy `app` path has explicit GraalVM native build wiring, and the deployable service apps do not yet ship with service-specific Dockerfiles. This change is needed now so the current four-service architecture can be built and run as native-image-based containers instead of relying on ad hoc local startup.

## What Changes

- Add first-class Dockerfile definitions for each deployable service app: `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app`.
- Extend the native runtime/build expectations so each deployable service can produce a GraalVM native binary and package it into a container image suitable for direct runtime use.
- Standardize service-level container packaging inputs such as entrypoint expectations, exposed ports, and required runtime configuration surface for the dedicated services.
- Keep shared library modules as internal build dependencies only; container packaging is defined for deployable service entry modules rather than every Gradle subproject.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `modular-architecture-and-native-runtime`: Change the runtime packaging requirement so every deployable microservice entrypoint has an explicit Dockerfile and native-image-based container build path, not just generic native-build compatibility at the repository level.

## Impact

- Affected code and build surfaces: `access-gateway-app`, `api-service-app`, `message-service-app`, `persistence-service-app`, shared Gradle/native build conventions, and container-related repository assets.
- Affected runtime/deployment surface: service image build flow, service startup commands, per-service port exposure, and environment-driven configuration used by containerized deployment.
- Affected docs and verification: runbook or deployment documentation will need to describe how each service image is built and run under the native-image container model.
