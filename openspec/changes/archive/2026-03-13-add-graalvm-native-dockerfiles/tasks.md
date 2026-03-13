## 1. Shared Native Build Convention

- [x] 1.1 Extract the existing GraalVM native-image build setup into a reusable Gradle convention or shared script for deployable service app modules.
- [x] 1.2 Apply the shared native build convention to `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app`, including stable per-service native binary names.

## 2. Service Dockerfiles

- [x] 2.1 Add module-local multi-stage Dockerfiles for `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app` that build from repository-root context and package the native binaries into runnable images.
- [x] 2.2 Ensure each Dockerfile preserves the dedicated service runtime contract, including entrypoint expectations and listener exposure aligned with the existing service topology.

## 3. Documentation And Verification

- [x] 3.1 Update the runbook or related docs with per-service native build and Docker image build/run commands for the dedicated services.
- [x] 3.2 Run focused verification for the new Gradle native configuration and Dockerfile build flow, then record the verified commands/results.
