# AIOps Observability Design

## Goal

Make mo-chat deployable as an AIOps-monitored Kubernetes workload by adding a local umbrella Helm chart that installs mo-chat together with Prometheus, Loki, and Tempo, while keeping observability behavior enabled only in the Kubernetes profile.

## Confirmed Scope

- Add `deploy/helm/mo-chat-observability` as the integration entrypoint.
- Use Helm dependencies for the monitoring stack:
  - `prometheus-community/kube-prometheus-stack` chart `86.2.3`, Prometheus Operator appVersion `v0.91.0`.
  - `grafana/loki` chart `7.0.0`, appVersion `3.6.7`.
  - `grafana-community/tempo` chart `2.2.3`, appVersion `2.10.7`.
- Template mo-chat workload resources inside the umbrella chart.
- Enable Micronaut management and Prometheus metrics only when the `k8s` environment is active.
- Use SLF4J/Logback stdout logs for Loki ingestion.
- Use OpenTelemetry Java agent injection from the Kubernetes deployment, not business-code instrumentation.
- Do not add custom business metrics, dashboards, production alert rules, image build pipelines, or JSON logging in this iteration.

## Current Project Context

mo-chat is a Java 25 Gradle multi-module Micronaut application. The active modules are `app`, `common`, `protocol`, `infra-redis`, `connection-module`, `call-module`, `message-module`, `logic-module`, `persistence-module`, and `multimedia-module`.

The main application entrypoint is `app/src/main/java/com/github/lystran/mochat/Application.java`. Runtime infrastructure is assembled in `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`. The repository currently has local infrastructure in `docker-compose.yml`, but no Dockerfile, Kubernetes manifests, or Helm chart.

## Architecture

The umbrella chart owns the local Kubernetes integration surface:

- mo-chat `Deployment`, `Service`, `ConfigMap`, optional `ServiceMonitor`, and chart notes.
- Prometheus, Loki, and Tempo dependencies with values suitable for local or staging AIOps validation.
- Stable labels and resource attributes so AIOps can correlate metrics, logs, traces, and Kubernetes objects.

The mo-chat application remains mostly environment-agnostic. It gains standard Micronaut management and Prometheus support, but those endpoints are exposed through `application-k8s.yml` and chart-provided environment variables. Local `application.yml` and `application-local.yml` behavior stays unchanged.

Traces are produced through the OpenTelemetry Java agent in the Pod command line. This keeps tracing Kubernetes-scoped and avoids adding tracing code to HTTP controllers or Netty handlers.

## Data Flow

Prometheus discovers mo-chat through `ServiceMonitor` and scrapes the Micronaut Prometheus endpoint on the HTTP service port.

Loki ingests mo-chat container stdout and stderr through the logging collector included by the Loki chart configuration. The app continues using normal SLF4J log calls.

Tempo receives OTLP traces exported by the OpenTelemetry Java agent. The agent adds resource attributes for `service.name`, `service.namespace`, `deployment.environment`, and `aiops.project_id`.

AIOps actively queries Prometheus, Loki, Tempo, and the Kubernetes API. It does not require mo-chat to call AIOps directly.

## Labels and Attributes

Kubernetes labels on mo-chat resources:

- `app.kubernetes.io/name: mo-chat`
- `app.kubernetes.io/instance: <release name>`
- `app.kubernetes.io/component: chat-service`
- `app.kubernetes.io/managed-by: Helm`
- `aiops.project_id: <values.observability.aiopsProjectId>`

OpenTelemetry resource attributes:

- `service.name=mo-chat`
- `service.namespace=mochat`
- `deployment.environment=<values.observability.environment>`
- `aiops.project_id=<values.observability.aiopsProjectId>`

## Helm Structure

Create:

```text
deploy/helm/mo-chat-observability/
  Chart.yaml
  values.yaml
  README.md
  templates/
    _helpers.tpl
    configmap.yaml
    deployment.yaml
    service.yaml
    servicemonitor.yaml
    NOTES.txt
```

The chart exposes values for the mo-chat image, service ports, environment variables, observability flags, OTel agent image, ServiceMonitor settings, and child chart values.

## Application Changes

`app/build.gradle.kts` will add Micronaut management and Micrometer Prometheus registry dependencies.

`app/src/main/resources/application-k8s.yml` will enable management, health, and Prometheus endpoints for Kubernetes deployments. It will not hardcode Prometheus, Loki, Tempo, or AIOps URLs.

No business logic code changes are required for this phase.

## Validation

Implementation should verify:

- `rtk ./gradlew :app:test`
- `helm dependency update deploy/helm/mo-chat-observability`
- `helm lint deploy/helm/mo-chat-observability`
- `helm template mochat deploy/helm/mo-chat-observability`

The README will include AIOps `projects.yaml` datasource examples for the services created by the chart.

## Sources Checked

- `zym-docs/aiops接入指南.md`
- Micronaut documentation via Context7: management endpoint and Micrometer guidance.
- OpenTelemetry Java documentation via Context7: OTLP endpoint and resource attribute configuration.
- Official Helm chart indexes checked on 2026-06-18:
  - `https://prometheus-community.github.io/helm-charts/index.yaml`
  - `https://grafana.github.io/helm-charts/index.yaml`
  - `https://grafana-community.github.io/helm-charts/index.yaml`
