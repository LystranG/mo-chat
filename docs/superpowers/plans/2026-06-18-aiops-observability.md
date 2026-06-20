# AIOps Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Kubernetes-only observability integration for mo-chat using an umbrella Helm chart that installs mo-chat with Prometheus, Loki, and Tempo for AIOps monitoring.

**Architecture:** The app receives standard Micronaut management and Prometheus support, activated by the `k8s` Micronaut environment. The Helm chart owns Kubernetes-specific behavior: mo-chat workload templates, labels, ServiceMonitor, OpenTelemetry Java agent injection, and official monitoring stack dependencies. Logs remain SLF4J/Logback stdout logs and are collected by Loki at the cluster layer.

**Tech Stack:** Java 25, Gradle Kotlin DSL, Micronaut 4.9.0, Micrometer Prometheus registry, Logback, OpenTelemetry Java agent, Helm v3, kube-prometheus-stack `86.2.3`, Loki chart `7.0.0`, Tempo community chart `2.2.3`.

---

## File Structure

- Modify `app/build.gradle.kts`: add Micronaut management and Prometheus registry dependencies.
- Create `app/src/main/resources/application-k8s.yml`: Kubernetes-only management and Prometheus endpoint configuration.
- Create `app/src/test/java/com/github/lystran/mochat/observability/K8sObservabilityConfigurationTest.java`: assert the k8s profile exposes expected endpoint properties without starting external infrastructure.
- Create `deploy/helm/mo-chat-observability/Chart.yaml`: umbrella chart metadata and dependency declarations.
- Create `deploy/helm/mo-chat-observability/values.yaml`: default values for mo-chat and child charts.
- Create `deploy/helm/mo-chat-observability/templates/_helpers.tpl`: shared chart naming and label helpers.
- Create `deploy/helm/mo-chat-observability/templates/configmap.yaml`: k8s profile environment configuration for mo-chat.
- Create `deploy/helm/mo-chat-observability/templates/deployment.yaml`: mo-chat Deployment with OTel Java agent init container and env vars.
- Create `deploy/helm/mo-chat-observability/templates/service.yaml`: HTTP and TCP service ports.
- Create `deploy/helm/mo-chat-observability/templates/servicemonitor.yaml`: Prometheus Operator ServiceMonitor gated by values.
- Create `deploy/helm/mo-chat-observability/templates/NOTES.txt`: installed service URLs for AIOps.
- Create `deploy/helm/mo-chat-observability/README.md`: install, render, and AIOps datasource instructions.

## Implementation Tasks

### Task 1: Add Kubernetes-only Micronaut observability configuration

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/resources/application-k8s.yml`
- Create: `app/src/test/java/com/github/lystran/mochat/observability/K8sObservabilityConfigurationTest.java`

- [ ] **Step 1: Write the failing configuration test**

Create `app/src/test/java/com/github/lystran/mochat/observability/K8sObservabilityConfigurationTest.java`:

```java
package com.github.lystran.mochat.observability;

import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class K8sObservabilityConfigurationTest {
    @Test
    void k8sEnvironmentEnablesPrometheusAndHealthEndpoints() {
        try (Environment environment = Environment.builder("k8s")
            .deduceEnvironment(false)
            .build()
            .start()) {
            assertTrue(environment.getActiveNames().contains("k8s"));
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.all.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.health.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("endpoints.prometheus.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                "/prometheus",
                environment.getProperty("endpoints.prometheus.path", String.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("micronaut.metrics.enabled", Boolean.class).orElseThrow()
            );
            assertEquals(
                Boolean.TRUE,
                environment.getProperty("micronaut.metrics.export.prometheus.enabled", Boolean.class).orElseThrow()
            );
        }
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
rtk ./gradlew :app:test --tests 'com.github.lystran.mochat.observability.K8sObservabilityConfigurationTest'
```

Expected: FAIL because the expected endpoint properties are missing before `application-k8s.yml` exists.

- [ ] **Step 3: Add Micronaut management and Prometheus dependencies**

Modify `app/build.gradle.kts`. In the existing `dependencies` block, add these lines after `implementation("io.micronaut:micronaut-jackson-databind:4.9.0")`:

```kotlin
    implementation("io.micronaut:micronaut-management:4.9.0")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core:5.12.0")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus:5.12.0")
```

Do not remove existing exclusions on the RocketMQ client.

- [ ] **Step 4: Add the k8s-only application configuration**

Create `app/src/main/resources/application-k8s.yml`:

```yaml
endpoints:
  all:
    enabled: true
    sensitive: false
  health:
    enabled: true
    sensitive: false
    details-visible: ANONYMOUS
  prometheus:
    enabled: true
    sensitive: false
    path: /prometheus

micronaut:
  metrics:
    enabled: true
    binders:
      jvm:
        enabled: true
      uptime:
        enabled: true
      processor:
        enabled: true
      files:
        enabled: true
      logback:
        enabled: true
    export:
      prometheus:
        enabled: true
        descriptions: true
        step: PT1M
```

- [ ] **Step 5: Run the focused test**

Run:

```bash
rtk ./gradlew :app:test --tests 'com.github.lystran.mochat.observability.K8sObservabilityConfigurationTest'
```

Expected: PASS.

- [ ] **Step 6: Run the app test suite**

Run:

```bash
rtk ./gradlew :app:test
```

Expected: PASS. If existing unrelated integration tests require external PostgreSQL, Redis, or RocketMQ and fail for connectivity, capture the failing test names and continue only after confirming they are pre-existing environment failures.

- [ ] **Step 7: Commit Task 1**

Run:

```bash
rtk git add app/build.gradle.kts app/src/main/resources/application-k8s.yml app/src/test/java/com/github/lystran/mochat/observability/K8sObservabilityConfigurationTest.java
rtk git commit -m "feat: enable k8s observability endpoints"
```

Expected: commit succeeds.

### Task 2: Create the umbrella Helm chart metadata and defaults

**Files:**
- Create: `deploy/helm/mo-chat-observability/Chart.yaml`
- Create: `deploy/helm/mo-chat-observability/values.yaml`

- [ ] **Step 1: Create the chart metadata**

Create `deploy/helm/mo-chat-observability/Chart.yaml`:

```yaml
apiVersion: v2
name: mo-chat-observability
description: Umbrella chart for mo-chat with Prometheus, Loki, and Tempo for AIOps monitoring.
type: application
version: 0.1.0
appVersion: "1.0-SNAPSHOT"
dependencies:
  - name: kube-prometheus-stack
    version: 86.2.3
    repository: https://prometheus-community.github.io/helm-charts
    condition: kube-prometheus-stack.enabled
  - name: loki
    version: 7.0.0
    repository: https://grafana.github.io/helm-charts
    condition: loki.enabled
  - name: tempo
    version: 2.2.3
    repository: https://grafana-community.github.io/helm-charts
    condition: tempo.enabled
```

- [ ] **Step 2: Create default values**

Create `deploy/helm/mo-chat-observability/values.yaml`:

```yaml
mochat:
  replicaCount: 1
  image:
    repository: mo-chat
    tag: "1.0-SNAPSHOT"
    pullPolicy: IfNotPresent
  imagePullSecrets: []
  podAnnotations: {}
  podLabels: {}
  resources: {}
  nodeSelector: {}
  tolerations: []
  affinity: {}
  service:
    type: ClusterIP
    httpPort: 8080
    tcpPort: 9000
  env:
    javaToolOptions: ""
    httpHost: 0.0.0.0
    httpPort: "8080"
    tcpEnabled: "true"
    tcpHost: 0.0.0.0
    tcpPort: "9000"
    tcpIoUringPreferred: "false"
    flywayMigrateOnStart: "true"
    redisUri: redis://redis:6379
    redisTopicPrefix: mochat
    postgresUrl: jdbc:postgresql://postgres:5432/mochat
    postgresUsername: mochat
    postgresPassword: mochat
    rocketmqNameServer: rocketmq-namesrv:9876
    rocketmqProducerGroup: mochat-producer
    rocketmqConsumerEnabled: "true"
    rocketmqConsumerGroup: mochat-persistence-consumer
    rocketmqTopic: mochat.messages
    callOfflineTopic: mochat.call.offline-notifications
    callOfflineConsumerGroup: mochat-call-offline-consumer
    tlsEnabled: "true"
    tlsSelfSigned: "true"
    tlsCertificatePath: ""
    tlsPrivateKeyPath: ""
    idWorkerId: "1"
    rustfsEndpoint: http://rustfs:9000
    rustfsAccessKey: rustfsadmin
    rustfsSecretKey: rustfsadmin
    rustfsBucket: mochat-media
    rustfsRegion: cn-local

observability:
  enabled: true
  aiopsProjectId: mochat-prod
  environment: prod
  serviceNamespace: mochat
  prometheus:
    serviceMonitor:
      enabled: true
      interval: 30s
      scrapeTimeout: 10s
      labels: {}
  otel:
    enabled: true
    javaAgentImage:
      repository: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java
      tag: "2.16.0"
      pullPolicy: IfNotPresent
    exporterOtlpEndpoint: http://mochat-tempo:4318
    tracesExporter: otlp
    metricsExporter: none
    logsExporter: none

kube-prometheus-stack:
  enabled: true
  grafana:
    enabled: false
  prometheus:
    prometheusSpec:
      serviceMonitorSelectorNilUsesHelmValues: false
      podMonitorSelectorNilUsesHelmValues: false

loki:
  enabled: true
  deploymentMode: SingleBinary
  loki:
    auth_enabled: false
    commonConfig:
      replication_factor: 1
  singleBinary:
    replicas: 1
  minio:
    enabled: true

tempo:
  enabled: true
  tempo:
    reportingEnabled: false
  service:
    type: ClusterIP
```

- [ ] **Step 3: Update dependencies**

Run:

```bash
rtk helm dependency update deploy/helm/mo-chat-observability
```

Expected: `Chart.lock` is created and Helm downloads the three dependencies into `deploy/helm/mo-chat-observability/charts/` or reports that dependency charts are saved. If `helm` is unavailable, install Helm or record the missing binary before continuing.

- [ ] **Step 4: Commit Task 2**

Run:

```bash
rtk git add deploy/helm/mo-chat-observability/Chart.yaml deploy/helm/mo-chat-observability/values.yaml deploy/helm/mo-chat-observability/Chart.lock
rtk git commit -m "feat: add aiops observability umbrella chart"
```

Expected: commit succeeds. Do not commit downloaded `.tgz` files if repository policy excludes vendored Helm dependencies; if no policy exists, prefer committing only `Chart.lock`.

### Task 3: Add Helm helpers and mo-chat workload templates

**Files:**
- Create: `deploy/helm/mo-chat-observability/templates/_helpers.tpl`
- Create: `deploy/helm/mo-chat-observability/templates/configmap.yaml`
- Create: `deploy/helm/mo-chat-observability/templates/deployment.yaml`
- Create: `deploy/helm/mo-chat-observability/templates/service.yaml`

- [ ] **Step 1: Add helper templates**

Create `deploy/helm/mo-chat-observability/templates/_helpers.tpl`:

```gotemplate
{{- define "mo-chat-observability.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mo-chat-observability.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "mo-chat-observability.mochatName" -}}
{{- printf "%s-mochat" (include "mo-chat-observability.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mo-chat-observability.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "mo-chat-observability.mochatLabels" -}}
{{ include "mo-chat-observability.labels" . }}
app.kubernetes.io/name: mo-chat
app.kubernetes.io/component: chat-service
aiops.project_id: {{ .Values.observability.aiopsProjectId | quote }}
{{- end -}}

{{- define "mo-chat-observability.selectorLabels" -}}
app.kubernetes.io/name: mo-chat
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: chat-service
{{- end -}}
```

- [ ] **Step 2: Add the ConfigMap template**

Create `deploy/helm/mo-chat-observability/templates/configmap.yaml`:

```gotemplate
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "mo-chat-observability.mochatName" . }}-config
  labels:
    {{- include "mo-chat-observability.mochatLabels" . | nindent 4 }}
data:
  MICRONAUT_ENVIRONMENTS: "k8s"
  MOCHAT_HTTP_HOST: {{ .Values.mochat.env.httpHost | quote }}
  MOCHAT_HTTP_PORT: {{ .Values.mochat.env.httpPort | quote }}
  MOCHAT_TCP_ENABLED: {{ .Values.mochat.env.tcpEnabled | quote }}
  MOCHAT_TCP_HOST: {{ .Values.mochat.env.tcpHost | quote }}
  MOCHAT_TCP_PORT: {{ .Values.mochat.env.tcpPort | quote }}
  MOCHAT_TCP_IO_URING_PREFERRED: {{ .Values.mochat.env.tcpIoUringPreferred | quote }}
  MOCHAT_FLYWAY_MIGRATE_ON_START: {{ .Values.mochat.env.flywayMigrateOnStart | quote }}
  MOCHAT_REDIS_URI: {{ .Values.mochat.env.redisUri | quote }}
  MOCHAT_REDIS_TOPIC_PREFIX: {{ .Values.mochat.env.redisTopicPrefix | quote }}
  MOCHAT_POSTGRES_URL: {{ .Values.mochat.env.postgresUrl | quote }}
  MOCHAT_POSTGRES_USERNAME: {{ .Values.mochat.env.postgresUsername | quote }}
  MOCHAT_ROCKETMQ_NAME_SERVER: {{ .Values.mochat.env.rocketmqNameServer | quote }}
  MOCHAT_ROCKETMQ_PRODUCER_GROUP: {{ .Values.mochat.env.rocketmqProducerGroup | quote }}
  MOCHAT_ROCKETMQ_CONSUMER_ENABLED: {{ .Values.mochat.env.rocketmqConsumerEnabled | quote }}
  MOCHAT_ROCKETMQ_CONSUMER_GROUP: {{ .Values.mochat.env.rocketmqConsumerGroup | quote }}
  MOCHAT_ROCKETMQ_TOPIC: {{ .Values.mochat.env.rocketmqTopic | quote }}
  MOCHAT_CALL_OFFLINE_TOPIC: {{ .Values.mochat.env.callOfflineTopic | quote }}
  MOCHAT_CALL_OFFLINE_CONSUMER_GROUP: {{ .Values.mochat.env.callOfflineConsumerGroup | quote }}
  MOCHAT_TLS_ENABLED: {{ .Values.mochat.env.tlsEnabled | quote }}
  MOCHAT_TLS_SELF_SIGNED: {{ .Values.mochat.env.tlsSelfSigned | quote }}
  MOCHAT_TLS_CERT_PATH: {{ .Values.mochat.env.tlsCertificatePath | quote }}
  MOCHAT_TLS_KEY_PATH: {{ .Values.mochat.env.tlsPrivateKeyPath | quote }}
  MOCHAT_ID_WORKER_ID: {{ .Values.mochat.env.idWorkerId | quote }}
  RUSTFS_ENDPOINT: {{ .Values.mochat.env.rustfsEndpoint | quote }}
  RUSTFS_ACCESS_KEY: {{ .Values.mochat.env.rustfsAccessKey | quote }}
  RUSTFS_BUCKET: {{ .Values.mochat.env.rustfsBucket | quote }}
  RUSTFS_REGION: {{ .Values.mochat.env.rustfsRegion | quote }}
  OTEL_SERVICE_NAME: "mo-chat"
  OTEL_RESOURCE_ATTRIBUTES: {{ printf "service.namespace=%s,deployment.environment=%s,aiops.project_id=%s" .Values.observability.serviceNamespace .Values.observability.environment .Values.observability.aiopsProjectId | quote }}
  OTEL_TRACES_EXPORTER: {{ .Values.observability.otel.tracesExporter | quote }}
  OTEL_METRICS_EXPORTER: {{ .Values.observability.otel.metricsExporter | quote }}
  OTEL_LOGS_EXPORTER: {{ .Values.observability.otel.logsExporter | quote }}
  OTEL_EXPORTER_OTLP_ENDPOINT: {{ .Values.observability.otel.exporterOtlpEndpoint | quote }}
```

- [ ] **Step 3: Add the Service template**

Create `deploy/helm/mo-chat-observability/templates/service.yaml`:

```gotemplate
apiVersion: v1
kind: Service
metadata:
  name: {{ include "mo-chat-observability.mochatName" . }}
  labels:
    {{- include "mo-chat-observability.mochatLabels" . | nindent 4 }}
spec:
  type: {{ .Values.mochat.service.type }}
  selector:
    {{- include "mo-chat-observability.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.mochat.service.httpPort }}
      targetPort: http
      protocol: TCP
    - name: chat-tcp
      port: {{ .Values.mochat.service.tcpPort }}
      targetPort: chat-tcp
      protocol: TCP
```

- [ ] **Step 4: Add the Deployment template**

Create `deploy/helm/mo-chat-observability/templates/deployment.yaml`:

```gotemplate
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "mo-chat-observability.mochatName" . }}
  labels:
    {{- include "mo-chat-observability.mochatLabels" . | nindent 4 }}
spec:
  replicas: {{ .Values.mochat.replicaCount }}
  selector:
    matchLabels:
      {{- include "mo-chat-observability.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "mo-chat-observability.mochatLabels" . | nindent 8 }}
        {{- with .Values.mochat.podLabels }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      annotations:
        {{- with .Values.mochat.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      {{- with .Values.mochat.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- if .Values.observability.otel.enabled }}
      initContainers:
        - name: copy-opentelemetry-javaagent
          image: "{{ .Values.observability.otel.javaAgentImage.repository }}:{{ .Values.observability.otel.javaAgentImage.tag }}"
          imagePullPolicy: {{ .Values.observability.otel.javaAgentImage.pullPolicy }}
          command:
            - sh
            - -c
            - cp /javaagent.jar /otel-auto-instrumentation/javaagent.jar
          volumeMounts:
            - name: otel-auto-instrumentation
              mountPath: /otel-auto-instrumentation
      {{- end }}
      containers:
        - name: mo-chat
          image: "{{ .Values.mochat.image.repository }}:{{ .Values.mochat.image.tag }}"
          imagePullPolicy: {{ .Values.mochat.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.mochat.service.httpPort }}
              protocol: TCP
            - name: chat-tcp
              containerPort: {{ .Values.mochat.service.tcpPort }}
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "mo-chat-observability.mochatName" . }}-config
          env:
            - name: MOCHAT_POSTGRES_PASSWORD
              value: {{ .Values.mochat.env.postgresPassword | quote }}
            - name: RUSTFS_SECRET_KEY
              value: {{ .Values.mochat.env.rustfsSecretKey | quote }}
            - name: JAVA_TOOL_OPTIONS
              value: {{- if .Values.observability.otel.enabled }} {{ printf "%s -javaagent:/otel-auto-instrumentation/javaagent.jar" .Values.mochat.env.javaToolOptions | trim | quote }}{{- else }} {{ .Values.mochat.env.javaToolOptions | quote }}{{- end }}
          readinessProbe:
            httpGet:
              path: /health
              port: http
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: http
            initialDelaySeconds: 30
            periodSeconds: 20
          resources:
            {{- toYaml .Values.mochat.resources | nindent 12 }}
          {{- if .Values.observability.otel.enabled }}
          volumeMounts:
            - name: otel-auto-instrumentation
              mountPath: /otel-auto-instrumentation
          {{- end }}
      {{- if .Values.observability.otel.enabled }}
      volumes:
        - name: otel-auto-instrumentation
          emptyDir: {}
      {{- end }}
      {{- with .Values.mochat.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.mochat.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.mochat.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
```

- [ ] **Step 5: Render templates to catch syntax errors**

Run:

```bash
rtk helm template mochat deploy/helm/mo-chat-observability --skip-tests
```

Expected: rendered YAML includes one `Deployment`, one `Service`, and one `ConfigMap` for mo-chat. No Helm parse errors.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
rtk git add deploy/helm/mo-chat-observability/templates/_helpers.tpl deploy/helm/mo-chat-observability/templates/configmap.yaml deploy/helm/mo-chat-observability/templates/deployment.yaml deploy/helm/mo-chat-observability/templates/service.yaml
rtk git commit -m "feat: template mo-chat k8s workload"
```

Expected: commit succeeds.

### Task 4: Add Prometheus ServiceMonitor and chart notes

**Files:**
- Create: `deploy/helm/mo-chat-observability/templates/servicemonitor.yaml`
- Create: `deploy/helm/mo-chat-observability/templates/NOTES.txt`

- [ ] **Step 1: Add the ServiceMonitor template**

Create `deploy/helm/mo-chat-observability/templates/servicemonitor.yaml`:

```gotemplate
{{- if and .Values.observability.enabled .Values.observability.prometheus.serviceMonitor.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "mo-chat-observability.mochatName" . }}
  labels:
    {{- include "mo-chat-observability.mochatLabels" . | nindent 4 }}
    {{- with .Values.observability.prometheus.serviceMonitor.labels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  selector:
    matchLabels:
      {{- include "mo-chat-observability.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: /prometheus
      interval: {{ .Values.observability.prometheus.serviceMonitor.interval }}
      scrapeTimeout: {{ .Values.observability.prometheus.serviceMonitor.scrapeTimeout }}
{{- end }}
```

- [ ] **Step 2: Add chart notes with AIOps URLs**

Create `deploy/helm/mo-chat-observability/templates/NOTES.txt`:

```gotemplate
mo-chat observability stack installed.

mo-chat service:
  http: http://{{ include "mo-chat-observability.mochatName" . }}.{{ .Release.Namespace }}.svc:{{ .Values.mochat.service.httpPort }}
  prometheus endpoint: http://{{ include "mo-chat-observability.mochatName" . }}.{{ .Release.Namespace }}.svc:{{ .Values.mochat.service.httpPort }}/prometheus

AIOps datasource examples:
  prometheus.base_url: http://{{ .Release.Name }}-kube-prometheus-stack-prometheus.{{ .Release.Namespace }}.svc:9090
  loki.base_url: http://{{ .Release.Name }}-loki.{{ .Release.Namespace }}.svc:3100
  tempo.base_url: http://{{ .Release.Name }}-tempo.{{ .Release.Namespace }}.svc:3200

The mo-chat workload uses:
  service.name: mo-chat
  aiops.project_id: {{ .Values.observability.aiopsProjectId }}
  deployment.environment: {{ .Values.observability.environment }}
```

- [ ] **Step 3: Render with ServiceMonitor enabled**

Run:

```bash
rtk helm template mochat deploy/helm/mo-chat-observability --show-only templates/servicemonitor.yaml
```

Expected: output includes `kind: ServiceMonitor`, `path: /prometheus`, and `port: http`.

- [ ] **Step 4: Render with ServiceMonitor disabled**

Run:

```bash
rtk helm template mochat deploy/helm/mo-chat-observability --set observability.prometheus.serviceMonitor.enabled=false --show-only templates/servicemonitor.yaml
```

Expected: output is empty or Helm reports no manifest for that template. It must not render a ServiceMonitor.

- [ ] **Step 5: Commit Task 4**

Run:

```bash
rtk git add deploy/helm/mo-chat-observability/templates/servicemonitor.yaml deploy/helm/mo-chat-observability/templates/NOTES.txt
rtk git commit -m "feat: expose mo-chat prometheus scraping"
```

Expected: commit succeeds.

### Task 5: Document install and AIOps datasource configuration

**Files:**
- Create: `deploy/helm/mo-chat-observability/README.md`

- [ ] **Step 1: Create README**

Create `deploy/helm/mo-chat-observability/README.md`:

```markdown
# mo-chat Observability Chart

This umbrella chart installs mo-chat with Prometheus, Loki, and Tempo for AIOps monitoring in Kubernetes.

## Components

- mo-chat application workload
- Prometheus through `kube-prometheus-stack` chart `86.2.3`
- Loki through Grafana `loki` chart `7.0.0`
- Tempo through Grafana community `tempo` chart `2.2.3`

## Install

```bash
helm dependency update deploy/helm/mo-chat-observability
helm install mochat deploy/helm/mo-chat-observability --namespace mochat --create-namespace
```

Set the mo-chat image before installing into a real cluster:

```bash
helm install mochat deploy/helm/mo-chat-observability \
  --namespace mochat \
  --create-namespace \
  --set mochat.image.repository=registry.example.com/mo-chat \
  --set mochat.image.tag=1.0.0
```

## Render Locally

```bash
helm dependency update deploy/helm/mo-chat-observability
helm lint deploy/helm/mo-chat-observability
helm template mochat deploy/helm/mo-chat-observability --namespace mochat
```

## AIOps project datasource example

```yaml
default_project: mochat-prod

projects:
  mochat-prod:
    name: "mo-chat production"
    metric_profile: java
    enabled: true
    datasources:
      prometheus:
        base_url: "http://mochat-kube-prometheus-stack-prometheus.mochat.svc:9090"
        verify_ssl: true
      loki:
        base_url: "http://mochat-loki.mochat.svc:3100"
        auth_type: NoAuth
        verify_ssl: true
      tempo:
        base_url: "http://mochat-tempo.mochat.svc:3200"
        verify_ssl: true
      kubernetes:
        mode: in_cluster
        namespaces: ["mochat"]
```

## Correlation labels

mo-chat resources include:

- `app.kubernetes.io/name=mo-chat`
- `app.kubernetes.io/component=chat-service`
- `aiops.project_id=mochat-prod`

OpenTelemetry resource attributes include:

- `service.name=mo-chat`
- `service.namespace=mochat`
- `deployment.environment=prod`
- `aiops.project_id=mochat-prod`

## Logs

mo-chat continues to use SLF4J and Logback. Container stdout and stderr are collected by Loki at the Kubernetes layer.

## Traces

The chart injects the OpenTelemetry Java agent with `JAVA_TOOL_OPTIONS=-javaagent:/otel-auto-instrumentation/javaagent.jar`. Traces are exported through OTLP HTTP to Tempo using `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Local behavior

The observability endpoints are configured in `application-k8s.yml` and require `MICRONAUT_ENVIRONMENTS=k8s`. Local `application.yml` and `application-local.yml` are not changed by this chart.
```

- [ ] **Step 2: Check README commands render correctly**

Run:

```bash
rtk rg -n 'helm dependency update|helm lint|projects:|service.name=mo-chat|MICRONAUT_ENVIRONMENTS=k8s' deploy/helm/mo-chat-observability/README.md
```

Expected: output includes all searched strings with line numbers.

- [ ] **Step 3: Commit Task 5**

Run:

```bash
rtk git add deploy/helm/mo-chat-observability/README.md
rtk git commit -m "docs: document aiops observability chart"
```

Expected: commit succeeds.

### Task 6: Final verification

**Files:**
- Read: `docs/superpowers/specs/2026-06-18-aiops-observability-design.md`
- Read: `deploy/helm/mo-chat-observability/README.md`
- Verify generated and modified files from Tasks 1-5.

- [ ] **Step 1: Run focused application tests**

Run:

```bash
rtk ./gradlew :app:test --tests 'com.github.lystran.mochat.observability.K8sObservabilityConfigurationTest'
```

Expected: PASS.

- [ ] **Step 2: Run app test suite**

Run:

```bash
rtk ./gradlew :app:test
```

Expected: PASS, or documented pre-existing external-service failures with exact failing test names and connection errors.

- [ ] **Step 3: Update Helm dependencies**

Run:

```bash
rtk helm dependency update deploy/helm/mo-chat-observability
```

Expected: dependencies resolve for kube-prometheus-stack `86.2.3`, loki `7.0.0`, and tempo `2.2.3`.

- [ ] **Step 4: Lint chart**

Run:

```bash
rtk helm lint deploy/helm/mo-chat-observability
```

Expected: `1 chart(s) linted, 0 chart(s) failed`.

- [ ] **Step 5: Render chart**

Run:

```bash
rtk helm template mochat deploy/helm/mo-chat-observability --namespace mochat
```

Expected: output includes mo-chat `Deployment`, `Service`, `ConfigMap`, `ServiceMonitor`, and child chart manifests. No template errors.

- [ ] **Step 6: Check rendered observability wiring**

Run:

```bash
rtk proxy sh -lc 'helm template mochat deploy/helm/mo-chat-observability --namespace mochat | rg -n "MICRONAUT_ENVIRONMENTS|OTEL_EXPORTER_OTLP_ENDPOINT|/prometheus|ServiceMonitor|aiops.project_id|javaagent"'
```

Expected: output includes:

```text
MICRONAUT_ENVIRONMENTS
OTEL_EXPORTER_OTLP_ENDPOINT
/prometheus
ServiceMonitor
aiops.project_id
javaagent
```

- [ ] **Step 7: Check git status**

Run:

```bash
rtk git status --short
```

Expected: no uncommitted changes from implementation tasks, unless `charts/*.tgz` were generated and intentionally left untracked. If untracked chart archives exist, leave them uncommitted unless the repo owner asks to vendor Helm dependencies.

## Self-Review

- Spec coverage: Tasks cover k8s-only Micronaut management/Prometheus configuration, umbrella chart dependencies, mo-chat workload templates, ServiceMonitor, OTel Java agent injection, SLF4J stdout logging strategy, AIOps datasource docs, and verification.
- Placeholder scan: No unresolved markers or open-ended implementation instructions remain.
- Type and property consistency: The plan uses `MICRONAUT_ENVIRONMENTS=k8s`, `/prometheus`, `observability.aiopsProjectId`, `observability.environment`, `observability.otel.exporterOtlpEndpoint`, `app.kubernetes.io/name=mo-chat`, and `aiops.project_id` consistently across tests, values, templates, and README.
