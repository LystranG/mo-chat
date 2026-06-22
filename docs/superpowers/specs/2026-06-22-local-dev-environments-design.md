# Local and Dev Environments Design

## Context

MoChat currently runs as five dedicated services:

- `api-service`
- `message-service`
- `persistence-service`
- `access-gateway`
- `call-service`

The services share PostgreSQL, Redis, RocketMQ, and optionally LiveKit for audio/video calls. The current configuration mixes local defaults such as `127.0.0.1` with Kubernetes DNS defaults such as `api-service:19091` and `message-service:19092`. As a result, direct local Gradle startup requires long manual overrides, while Helm `values-local.yaml` actually describes a Kubernetes-style local deployment.

The target split is:

- `local`: run the five service apps directly as Gradle processes on the developer machine.
- `dev`: deploy the five service apps to a local k3s Kubernetes cluster.
- `prod`: reserve the environment name and documentation structure for a later production rollout.

## Goals

- Make `local` a first-class local development environment that starts the five dedicated services as Gradle processes.
- Make `dev` a first-class local k3s deployment environment using Helm.
- Stop using `local` to mean both direct Gradle startup and Kubernetes deployment.
- Keep real secrets out of git while providing a clear root `.env.example`.
- Require LiveKit configuration for `local` startup, so call-service failures are explicit.
- Preserve the dedicated service topology and do not reintroduce the legacy `app` runtime as the default path.

## Non-Goals

- Do not implement `prod` in this change.
- Do not deploy PostgreSQL, Redis, RocketMQ, or LiveKit inside the MoChat Helm chart.
- Do not redesign service ownership, protocols, database schema, or message flow.
- Do not support call-service multi-replica state sharing.
- Do not replace Docker Compose infrastructure for `local`.

## Environment Semantics

### local

`local` means direct Gradle process startup on the developer machine.

- Infrastructure comes from root `docker-compose.yml`.
- Services run with `MICRONAUT_ENVIRONMENTS=local`.
- Service-to-service addresses use `127.0.0.1`.
- Default local topology uses one access gateway:
  - gateway identity: `gateway-a`
  - TCP port: `9000`
  - gRPC port: `19093`
- `message-service` resolves gateway targets through a static map.
- `access-gateway` uses configured identity and static peer discovery.
- `call-service` must receive non-empty LiveKit variables from `.env` or the parent shell:
  - `MOCHAT_LIVEKIT_URL`
  - `MOCHAT_LIVEKIT_API_KEY`
  - `MOCHAT_LIVEKIT_API_SECRET`

### dev

`dev` means deployment to a local k3s Kubernetes cluster.

- MoChat services are deployed by Helm.
- Service-to-service addresses use Kubernetes Service DNS and headless Service DNS.
- `access-gateway` identity comes from Pod metadata.
- `message-service` and `access-gateway` use Kubernetes DNS discovery when Pod metadata is available.
- Infrastructure endpoints must be addresses reachable from k3s Pods.
- LiveKit, PostgreSQL credentials, and gateway TLS material are injected through Helm values or pre-created Kubernetes Secrets.

### prod

`prod` is only reserved in naming and documentation. A later change should add production-specific Helm values, image tags, resource sizing, Secret ownership, observability, and rollout policy.

## Configuration Layout

### Root environment files

Add a committed root `.env.example` with non-secret empty example values:

```dotenv
MOCHAT_LIVEKIT_URL=
MOCHAT_LIVEKIT_API_KEY=
MOCHAT_LIVEKIT_API_SECRET=
```

The real root `.env` is local-only and must not be committed. The root `.gitignore` should ignore `.env` while allowing `.env.example`.

The local startup script loads `.env` first and then allows already-exported shell variables to override it when the shell chooses to do so.

### Micronaut local profile files

Add or update one `application-local.yml` per dedicated service:

- `api-service-app/src/main/resources/application-local.yml`
  - `grpc.channels.message-service.address=127.0.0.1:19092`
  - keep HTTP `8080` and gRPC `19091`

- `message-service-app/src/main/resources/application-local.yml`
  - `grpc.channels.api-service.address=127.0.0.1:19091`
  - `mochat.runtime.gateway.identity-mode=CONFIGURED`
  - `mochat.runtime.gateway.discovery-mode=STATIC_MAP`
  - `mochat.message-service.route.gateway-targets.gateway-a=127.0.0.1:19093`
  - keep gRPC `19092`

- `access-gateway-app/src/main/resources/application-local.yml`
  - `grpc.channels.api-service.address=127.0.0.1:19091`
  - `grpc.channels.message-service.address=127.0.0.1:19092`
  - `mochat.runtime.gateway.identity-mode=CONFIGURED`
  - `mochat.runtime.gateway.identity-value=gateway-a`
  - `mochat.runtime.gateway.discovery-mode=STATIC_MAP`
  - `mochat.access-gateway.route.gateway-pod=gateway-a`
  - keep TCP `9000`, gRPC `19093`, admin HTTP `18080`

- `persistence-service-app/src/main/resources/application-local.yml`
  - PostgreSQL `jdbc:postgresql://127.0.0.1:5432/mochat`
  - Redis `redis://127.0.0.1:6379`
  - RocketMQ `127.0.0.1:9876`

- `call-service-app/src/main/resources/application-local.yml`
  - PostgreSQL `jdbc:postgresql://127.0.0.1:5432/mochat`
  - Redis `redis://127.0.0.1:6379`
  - RocketMQ `127.0.0.1:9876`
  - LiveKit values remain environment-driven and required by the local startup script.

The common `application.yml` files should stay environment-neutral where possible. Kubernetes-specific values should come from Helm, not from local defaults.

## Local Startup Scripts

Add `scripts/run-local.sh` as the recommended local entrypoint.

Responsibilities:

- Support `start`, `stop`, `status`, and `restart`.
- Load root `.env`.
- Fail fast if `.env` is missing.
- Fail fast if any required LiveKit variable is blank.
- Create `.local/logs` and `.local/pids`.
- Start the five Gradle processes with `MICRONAUT_ENVIRONMENTS=local`.
- Write each service log to `.local/logs/<service>.log`.
- Write each process PID to `.local/pids/<service>.pid`.
- Refuse to start a service when its PID file points to a still-running process.
- Stop only processes recorded in `.local/pids`.

The script should not manage production or k3s deployment. It is only the direct Gradle process runner for `local`.

## Dev Helm Values

Add `deploy/helm/mochat/values-dev.yaml` for local k3s.

Expected semantics:

- `global.environment=dev`
- `global.projectId=mochat-dev`
- image registry/tag values are suitable for a local k3s image workflow.
- external dependency addresses are explicitly documented as k3s-reachable addresses.
- LiveKit values default to empty strings and must be supplied through Secret or local override.
- gateway TLS values default to empty strings and must be supplied through Secret or `--set-file`.

The existing `values-local.yaml` should either be migrated to `values-dev.yaml` or marked as deprecated in documentation. The preferred direction is to use `values-dev.yaml` as the canonical k3s development values file so `local` remains reserved for direct Gradle startup.

## Validation Strategy

### Configuration tests

Add focused tests proving the `local` environment loads the expected values:

- `api-service` resolves `message-service` as `127.0.0.1:19092`.
- `message-service` resolves `api-service` as `127.0.0.1:19091`.
- `message-service` local gateway target map contains `gateway-a -> 127.0.0.1:19093`.
- `access-gateway` local identity is `gateway-a`.
- `access-gateway` local upstreams use `127.0.0.1`.
- `call-service` local external dependencies use `127.0.0.1`.

### Script tests

Add a dry-run path or shell-level test coverage for `scripts/run-local.sh`:

- Missing `.env` fails with a clear message.
- Blank LiveKit values fail with a clear message.
- Valid `.env` produces the five expected Gradle commands with `MICRONAUT_ENVIRONMENTS=local`.

### Helm tests

Extend Kubernetes/Helm manifest tests to cover `values-dev.yaml`:

- `global.environment=dev` appears in labels or rendered environment markers where the chart already supports it.
- runtime ConfigMap still renders `api-service:19091`, `message-service:19092`, and `access-gateway-headless`.
- `access-gateway` still receives Pod metadata env vars.
- `call-service` still references the LiveKit Secret.

## Documentation Updates

Update:

- `README.md`
  - Make `local` direct Gradle startup the main local development path.
  - Point to `.env.example` and `scripts/run-local.sh`.

- `docs/runbook.md`
  - Split `local` direct Gradle startup from `dev` k3s Helm deployment.
  - Replace ambiguous `values-local.yaml` language with `values-dev.yaml`.

- `docs/codebase/deployment/README.md`
  - Record the new environment semantics, script entrypoint, and Helm dev values.

- Service memory files as needed when service configuration defaults or profile files change.

## Risks and Mitigations

- Risk: local scripts can leave stale PID files.
  - Mitigation: `status` and `start` verify whether recorded PIDs are still alive before acting.

- Risk: `.env` can accidentally be committed.
  - Mitigation: root `.gitignore` explicitly ignores `.env`; only `.env.example` is committed.

- Risk: `values-dev.yaml` still may not work for every k3s installation because host access differs by platform.
  - Mitigation: document the dependency addresses as examples that must be reachable from k3s Pods.

- Risk: local profile tests may accidentally start real network listeners.
  - Mitigation: tests should use Micronaut `ApplicationContext` property loading or disabled dependency toggles instead of starting full services unless the module already has safe embedded-server tests.

## Deferred Work

- Default local topology starts one access gateway. A later change can add `scripts/run-local.sh start --gateways=2` with `gateway-b`, TCP `9001`, and gRPC `19094`.
- `prod` values are intentionally deferred to a later design and implementation cycle.
