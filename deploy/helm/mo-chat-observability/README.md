# mo-chat Observability Chart

This umbrella chart installs mo-chat with Prometheus, Loki, and Tempo for AIOps monitoring in Kubernetes.

## Components

- mo-chat application workload
- kube-prometheus-stack chart 86.2.3
- Loki chart 7.0.0
- Tempo chart 2.2.3

## Helm Repositories

Add the required chart repositories before building dependencies:

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add grafana-community https://grafana-community.github.io/helm-charts
helm repo update
```

## Install

Build dependencies and install the chart into the `mochat` namespace:

```bash
helm dependency update deploy/helm/mo-chat-observability
helm install mochat deploy/helm/mo-chat-observability --namespace mochat --create-namespace
```

Install with a real mo-chat image:

```bash
helm dependency update deploy/helm/mo-chat-observability
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=2026.06.19
```

## Render Locally

Validate and render the chart before installing:

```bash
helm dependency update deploy/helm/mo-chat-observability
helm lint deploy/helm/mo-chat-observability
helm template mochat deploy/helm/mo-chat-observability --namespace mochat
```

## AIOps Datasource Configuration

Example `projects.yaml` datasource configuration:

```yaml
default_project: mochat-prod

projects:
  mochat-prod:
    name: "mo-chat 生产"
    metric_profile: java
    enabled: true
    datasources:
      prometheus:
        base_url: http://mochat-kube-prometheus-prometheus.mochat.svc:9090
      loki:
        base_url: http://mochat-loki.mochat.svc:3100
      tempo:
        base_url: http://mochat-tempo.mochat.svc:3200
      kubernetes:
        mode: in_cluster
        namespaces: ["mochat"]
```

The Prometheus service name is derived from the release name and the kube-prometheus-stack Prometheus service. For the `mochat` release it is `mochat-kube-prometheus-prometheus`.

## Correlation Labels

Use these labels to correlate Kubernetes workloads, metrics, logs, and traces:

```text
app.kubernetes.io/name=mo-chat
app.kubernetes.io/component=chat-service
aiops.project_id=mochat-prod
```

## OpenTelemetry Resource Attributes

The workload should emit these OpenTelemetry resource attributes:

```text
service.name=mo-chat
service.namespace=mochat
deployment.environment=prod
aiops.project_id=mochat-prod
```

## Logs

The mo-chat application writes SLF4J/Logback output to stdout and stderr. In Kubernetes, Loki collects container stdout and stderr streams through the cluster logging pipeline and uses the workload labels above for AIOps correlation.

## Traces

When `observability.otel.enabled=true`, the chart injects the OpenTelemetry Java agent configuration into the mo-chat workload. With Tempo enabled, the chart points OTLP trace export at the in-cluster Tempo service. If `tempo.enabled=false` and OpenTelemetry remains enabled, set `observability.otel.exporterOtlpEndpoint` to an external or separately managed OTLP endpoint.

## Local Behavior

Observability endpoints require `MICRONAUT_ENVIRONMENTS=k8s`. Local application configs are unchanged, so local development keeps using the existing non-Kubernetes configuration unless that environment is explicitly enabled.

## Secrets

The default chart stores `MOCHAT_POSTGRES_PASSWORD` and `RUSTFS_SECRET_KEY` in a Kubernetes Secret. Production deployments should override the default values with deployment-specific secrets.
