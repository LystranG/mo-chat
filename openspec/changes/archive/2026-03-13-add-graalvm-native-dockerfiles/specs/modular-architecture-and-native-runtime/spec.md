## MODIFIED Requirements

### Requirement: GraalVM native image compatibility
The build and packaging pipeline MUST support GraalVM native image packaging for every deployable microservice entrypoint: `access-gateway-app`, `api-service-app`, `message-service-app`, and `persistence-service-app`. Each deployable service MUST have an explicit Dockerfile that packages its service-specific native binary into a runnable container image. Shared library modules MUST remain internal build dependencies and MUST NOT be treated as standalone deployable container targets.

#### Scenario: dedicated service native build run
- **WHEN** the native build profile is executed for any deployable service entry module
- **THEN** that service produces its own native binary artifact with the runtime metadata required for that entrypoint

#### Scenario: dedicated service container image build
- **WHEN** an operator builds the image for one of the deployable service entry modules
- **THEN** the repository provides a service-owned Dockerfile that packages that service's native binary into a runnable container image

#### Scenario: containerized service keeps existing runtime contract
- **WHEN** a deployable service is started from its native-image-based container
- **THEN** it uses the same documented environment-variable configuration surface and listener contract as the dedicated runtime instead of requiring a separate container-only configuration model
