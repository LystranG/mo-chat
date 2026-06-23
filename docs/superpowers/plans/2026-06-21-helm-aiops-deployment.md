# Helm AIOps Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Docker-first, native-first Helm deployment path for five MoChat services, with Docker Compose observability stack configuration for external AIOps integration.

**Architecture:** `deploy/helm/mochat` owns Kubernetes workload templating for MoChat only; Docker Compose owns PostgreSQL, Redis, RocketMQ, Prometheus, Loki, Tempo, and Alertmanager for local integration. AIOps remains external and consumes metrics, logs, traces, alerts, Kubernetes labels, and example datasource configuration.

**Tech Stack:** Helm 3, Kubernetes YAML, Docker Compose, Micronaut services, GraalVM native images, Prometheus, Loki, Tempo, Alertmanager, JUnit 5, SnakeYAML.

---

## File Structure

Create:

- `deploy/helm/mochat/Chart.yaml`: Helm chart metadata.
- `deploy/helm/mochat/values.yaml`: Default chart values for five services, external dependencies, observability, and secrets.
- `deploy/helm/mochat/values-local.yaml`: Local Docker/kind-friendly values.
- `deploy/helm/mochat/values.schema.json`: Basic values validation for service enable flags, replica counts, and required strings.
- `deploy/helm/mochat/README.md`: Helm deployment instructions in Simplified Chinese.
- `deploy/helm/mochat/templates/_helpers.tpl`: Common names, labels, annotations, image helper, and env helpers.
- `deploy/helm/mochat/templates/namespace.yaml`: Optional namespace resource.
- `deploy/helm/mochat/templates/configmap-runtime.yaml`: Internal discovery and external dependency non-secret config.
- `deploy/helm/mochat/templates/configmap-observability.yaml`: Shared observability env values.
- `deploy/helm/mochat/templates/secret-external-dependencies.yaml`: Local/test external dependency secret, disabled when existing secret is used.
- `deploy/helm/mochat/templates/secret-access-gateway-tls.yaml`: Local/test gateway TLS secret, disabled when existing secret is used.
- `deploy/helm/mochat/templates/secret-livekit.yaml`: LiveKit secret for `call-service`, disabled when existing secret is used.
- `deploy/helm/mochat/templates/serviceaccount.yaml`: ServiceAccount shared by MoChat Pods.
- `deploy/helm/mochat/templates/api-service.yaml`: Deployment and Service for `api-service`.
- `deploy/helm/mochat/templates/message-service.yaml`: Deployment and Service for `message-service`.
- `deploy/helm/mochat/templates/persistence-service.yaml`: Deployment for `persistence-service`.
- `deploy/helm/mochat/templates/access-gateway.yaml`: StatefulSet, headless Service, and TCP Service for `access-gateway`.
- `deploy/helm/mochat/templates/call-service.yaml`: Deployment and Service for `call-service`.
- `deploy/helm/mochat/templates/NOTES.txt`: Post-install checks.
- `call-service-app/Dockerfile`: Native-first Dockerfile for `call-service`.
- `deploy/observability/docker-compose.yml`: Local observability stack.
- `deploy/observability/README.md`: Docker-first observability stack instructions in Simplified Chinese.
- `deploy/observability/prometheus/prometheus.yml`: Prometheus local scrape config.
- `deploy/observability/prometheus/rules/mochat-alerts.yml`: Basic MoChat alert examples.
- `deploy/observability/alertmanager/alertmanager.yml`: Alertmanager webhook template with documented example values.
- `deploy/observability/loki/loki.yml`: Minimal local Loki config.
- `deploy/observability/promtail/promtail.yml`: Local Promtail config and notes.
- `deploy/observability/tempo/tempo.yml`: Minimal local Tempo config.
- `deploy/observability/aiops/projects.yaml.example`: External AIOps project datasource example.
- `zym-docs/mochat-aiops-deployment.md`: End-to-end MoChat-as-monitored-system guide in Simplified Chinese.
- `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`: Helm render contract tests.

Modify:

- `docs/runbook.md`: Convert the main runbook to Simplified Chinese wording, make Docker CLI the default command path, and add Helm/AIOps deployment flow.
- `docs/codebase/deployment/README.md`: Record Helm chart, observability compose stack, Docker default commands, and `call-service` Kubernetes ownership.
- `docs/codebase/call-service/README.md`: Record new Dockerfile, Helm workload, Service, single-replica default, LiveKit Secret injection, and native-first caveat.
- `.gitignore`: Add `.superpowers/` if missing so brainstorming artifacts are not accidentally staged.

Do not delete:

- `deploy/kubernetes/base/**`
- `deploy/kubernetes/overlays/kind/**`

Those remain the kustomize/kind reference and fallback path.

---

### Task 1: Ignore Brainstorming Artifacts

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Write the failing check**

Run:

```bash
rtk rg -n '^\.superpowers/$' .gitignore
```

Expected: command exits `1` because `.superpowers/` is not ignored yet.

- [ ] **Step 2: Add `.superpowers/` to `.gitignore`**

Append this line near other local/tooling ignores:

```gitignore
.superpowers/
```

- [ ] **Step 3: Verify the ignore rule**

Run:

```bash
rtk rg -n '^\.superpowers/$' .gitignore
```

Expected: output contains:

```text
.superpowers/
```

- [ ] **Step 4: Check staged scope**

Run:

```bash
rtk git status --short
```

Expected: `.superpowers/` does not appear as an untracked path after the ignore rule is added.

- [ ] **Step 5: Commit**

```bash
rtk git add .gitignore
rtk git commit -m "chore: 忽略brainstorming本地产物"
```

---

### Task 2: Add Helm Chart Skeleton and Shared Values

**Files:**
- Create: `deploy/helm/mochat/Chart.yaml`
- Create: `deploy/helm/mochat/values.yaml`
- Create: `deploy/helm/mochat/values-local.yaml`
- Create: `deploy/helm/mochat/values.schema.json`
- Create: `deploy/helm/mochat/templates/_helpers.tpl`
- Create: `deploy/helm/mochat/templates/namespace.yaml`
- Create: `deploy/helm/mochat/templates/serviceaccount.yaml`
- Create: `deploy/helm/mochat/templates/configmap-runtime.yaml`
- Create: `deploy/helm/mochat/templates/configmap-observability.yaml`
- Create: `deploy/helm/mochat/templates/NOTES.txt`

- [ ] **Step 1: Create chart metadata**

Write `deploy/helm/mochat/Chart.yaml`:

```yaml
apiVersion: v2
name: mochat
description: MoChat dedicated services deployment chart
type: application
version: 0.1.0
appVersion: "1.0-SNAPSHOT"
```

- [ ] **Step 2: Create default values**

Write `deploy/helm/mochat/values.yaml`:

```yaml
global:
  namespace: mochat
  createNamespace: true
  imageRegistry: localhost/mochat
  imagePullPolicy: IfNotPresent
  projectId: mochat-local
  environment: local
  clusterDomain: cluster.local
  serviceAccountName: mochat
  commonLabels: {}

externalDependencies:
  redisUri: redis://redis.external.example:6379
  postgresUrl: jdbc:postgresql://postgres.external.example:5432/mochat
  rocketmqNameServer: rocketmq.external.example:9876
  rocketmqTopic: mochat.messages
  secret:
    create: true
    name: mochat-external-dependency-secrets
    postgresUsername: ""
    postgresPassword: ""

observability:
  prometheus:
    scrape: true
    path: /prometheus
  otel:
    enabled: false
    endpoint: ""
    resourceAttributes: ""
  logFormat: text

accessGatewayTls:
  create: true
  secretName: access-gateway-tls
  certificate: ""
  privateKey: ""

livekit:
  createSecret: true
  secretName: mochat-livekit
  url: ""
  apiKey: ""
  apiSecret: ""

apiService:
  enabled: true
  replicaCount: 1
  image:
    repository: api-service
    tag: dev
  ports:
    http: 8080
    grpc: 19091
  resources: {}
  probes:
    enabled: true
    path: /health

messageService:
  enabled: true
  replicaCount: 1
  image:
    repository: message-service
    tag: dev
  ports:
    grpc: 19092
  resources: {}

persistenceService:
  enabled: true
  replicaCount: 1
  image:
    repository: persistence-service
    tag: dev
  queueConsumerEnabled: true
  resources: {}

accessGateway:
  enabled: true
  replicaCount: 2
  image:
    repository: access-gateway
    tag: dev
  ports:
    admin: 18080
    tcp: 9000
    grpc: 19093
    nodePort: 32000
  drainGracePeriod: 30s
  terminationGracePeriodSeconds: 45
  resources: {}

callService:
  enabled: true
  replicaCount: 1
  image:
    repository: call-service
    tag: dev
  ports:
    http: 8090
  flywayMigrateOnStart: true
  queueConsumerEnabled: true
  resources: {}
  probes:
    enabled: true
    path: /health
```

- [ ] **Step 3: Create local values**

Write `deploy/helm/mochat/values-local.yaml`:

```yaml
global:
  namespace: mochat
  imageRegistry: localhost/mochat
  projectId: mochat-local
  environment: local

externalDependencies:
  redisUri: redis://host.docker.internal:6379
  postgresUrl: jdbc:postgresql://host.docker.internal:5432/mochat
  rocketmqNameServer: host.docker.internal:9876
  secret:
    create: true
    postgresUsername: mochat
    postgresPassword: mochat

accessGatewayTls:
  create: true
  certificate: ""
  privateKey: ""

livekit:
  createSecret: true
  url: ""
  apiKey: ""
  apiSecret: ""
```

- [ ] **Step 4: Create values schema**

Write `deploy/helm/mochat/values.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "global": {
      "type": "object",
      "properties": {
        "namespace": { "type": "string", "minLength": 1 },
        "projectId": { "type": "string", "minLength": 1 },
        "environment": { "type": "string", "minLength": 1 }
      },
      "required": ["namespace", "projectId", "environment"]
    },
    "callService": {
      "type": "object",
      "properties": {
        "enabled": { "type": "boolean" },
        "replicaCount": { "type": "integer", "minimum": 1, "maximum": 1 }
      }
    }
  }
}
```

- [ ] **Step 5: Create helper templates**

Write `deploy/helm/mochat/templates/_helpers.tpl`:

```gotemplate
{{- define "mochat.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mochat.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mochat.namespace" -}}
{{- .Values.global.namespace | default .Release.Namespace -}}
{{- end -}}

{{- define "mochat.serviceAccountName" -}}
{{- .Values.global.serviceAccountName | default (include "mochat.fullname" .) -}}
{{- end -}}

{{- define "mochat.image" -}}
{{- $root := index . 0 -}}
{{- $image := index . 1 -}}
{{- printf "%s/%s:%s" $root.Values.global.imageRegistry $image.repository $image.tag -}}
{{- end -}}

{{- define "mochat.labels" -}}
app.kubernetes.io/part-of: mochat
app.kubernetes.io/instance: {{ .Release.Name | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
mochat.lystran.io/project-id: {{ .Values.global.projectId | quote }}
mochat.lystran.io/environment: {{ .Values.global.environment | quote }}
{{- with .Values.global.commonLabels }}
{{- toYaml . | nindent 0 }}
{{- end }}
{{- end -}}

{{- define "mochat.serviceLabels" -}}
{{ include "mochat.labels" . }}
app.kubernetes.io/name: {{ .serviceName | quote }}
{{- end -}}

{{- define "mochat.prometheusAnnotations" -}}
{{- if .Values.observability.prometheus.scrape }}
prometheus.io/scrape: "true"
prometheus.io/path: {{ .Values.observability.prometheus.path | quote }}
{{- end }}
{{- end -}}
```

- [ ] **Step 6: Create namespace and service account templates**

Write `deploy/helm/mochat/templates/namespace.yaml`:

```gotemplate
{{- if .Values.global.createNamespace }}
apiVersion: v1
kind: Namespace
metadata:
  name: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
{{- end }}
```

Write `deploy/helm/mochat/templates/serviceaccount.yaml`:

```gotemplate
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "mochat.serviceAccountName" . }}
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
```

- [ ] **Step 7: Create runtime and observability config templates**

Write `deploy/helm/mochat/templates/configmap-runtime.yaml`:

```gotemplate
apiVersion: v1
kind: ConfigMap
metadata:
  name: mochat-runtime-config
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
data:
  MOCHAT_API_SERVICE_GRPC_ADDRESS: api-service:{{ .Values.apiService.ports.grpc }}
  MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS: message-service:{{ .Values.messageService.ports.grpc }}
  MOCHAT_GATEWAY_HEADLESS_SERVICE: access-gateway-headless
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: mochat-external-dependencies
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
data:
  MOCHAT_REDIS_URI: {{ .Values.externalDependencies.redisUri | quote }}
  MOCHAT_POSTGRES_URL: {{ .Values.externalDependencies.postgresUrl | quote }}
  MOCHAT_ROCKETMQ_NAME_SERVER: {{ .Values.externalDependencies.rocketmqNameServer | quote }}
  MOCHAT_ROCKETMQ_TOPIC: {{ .Values.externalDependencies.rocketmqTopic | quote }}
```

Write `deploy/helm/mochat/templates/configmap-observability.yaml`:

```gotemplate
apiVersion: v1
kind: ConfigMap
metadata:
  name: mochat-observability
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
data:
  MOCHAT_LOG_FORMAT: {{ .Values.observability.logFormat | quote }}
  OTEL_RESOURCE_ATTRIBUTES: {{ default (printf "service.namespace=mochat,deployment.environment=%s,mochat.project_id=%s" .Values.global.environment .Values.global.projectId) .Values.observability.otel.resourceAttributes | quote }}
  OTEL_EXPORTER_OTLP_ENDPOINT: {{ .Values.observability.otel.endpoint | quote }}
```

- [ ] **Step 8: Create NOTES**

Write `deploy/helm/mochat/templates/NOTES.txt`:

```gotemplate
MoChat release {{ .Release.Name }} installed in namespace {{ include "mochat.namespace" . }}.

Check workloads:
  kubectl -n {{ include "mochat.namespace" . }} get pods,svc

Check Prometheus annotations:
  kubectl -n {{ include "mochat.namespace" . }} get pod -l app.kubernetes.io/part-of=mochat -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.annotations}{"\n"}{end}'
```

- [ ] **Step 9: Run Helm lint**

Run:

```bash
rtk helm lint deploy/helm/mochat
```

Expected: `1 chart(s) linted, 0 chart(s) failed`.

- [ ] **Step 10: Commit**

```bash
rtk git add deploy/helm/mochat
rtk git commit -m "feat: 新增mochat Helm chart骨架"
```

---

### Task 3: Add Helm Secrets and Five Service Templates

**Files:**
- Create: `deploy/helm/mochat/templates/secret-external-dependencies.yaml`
- Create: `deploy/helm/mochat/templates/secret-access-gateway-tls.yaml`
- Create: `deploy/helm/mochat/templates/secret-livekit.yaml`
- Create: `deploy/helm/mochat/templates/api-service.yaml`
- Create: `deploy/helm/mochat/templates/message-service.yaml`
- Create: `deploy/helm/mochat/templates/persistence-service.yaml`
- Create: `deploy/helm/mochat/templates/access-gateway.yaml`
- Create: `deploy/helm/mochat/templates/call-service.yaml`

- [ ] **Step 1: Create secret templates**

Write `deploy/helm/mochat/templates/secret-external-dependencies.yaml`:

```gotemplate
{{- if .Values.externalDependencies.secret.create }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ .Values.externalDependencies.secret.name }}
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
type: Opaque
stringData:
  MOCHAT_POSTGRES_USERNAME: {{ .Values.externalDependencies.secret.postgresUsername | quote }}
  MOCHAT_POSTGRES_PASSWORD: {{ .Values.externalDependencies.secret.postgresPassword | quote }}
{{- end }}
```

Write `deploy/helm/mochat/templates/secret-access-gateway-tls.yaml`:

```gotemplate
{{- if .Values.accessGatewayTls.create }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ .Values.accessGatewayTls.secretName }}
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
type: kubernetes.io/tls
stringData:
  tls.crt: {{ .Values.accessGatewayTls.certificate | quote }}
  tls.key: {{ .Values.accessGatewayTls.privateKey | quote }}
{{- end }}
```

Write `deploy/helm/mochat/templates/secret-livekit.yaml`:

```gotemplate
{{- if .Values.livekit.createSecret }}
apiVersion: v1
kind: Secret
metadata:
  name: {{ .Values.livekit.secretName }}
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
type: Opaque
stringData:
  MOCHAT_LIVEKIT_URL: {{ .Values.livekit.url | quote }}
  MOCHAT_LIVEKIT_API_KEY: {{ .Values.livekit.apiKey | quote }}
  MOCHAT_LIVEKIT_API_SECRET: {{ .Values.livekit.apiSecret | quote }}
{{- end }}
```

- [ ] **Step 2: Create `api-service` template**

Write `deploy/helm/mochat/templates/api-service.yaml`:

```gotemplate
{{- if .Values.apiService.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: api-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: api-service
  annotations:
    {{- include "mochat.prometheusAnnotations" . | nindent 4 }}
    prometheus.io/port: {{ .Values.apiService.ports.http | quote }}
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: api-service
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
  ports:
    - name: http
      port: {{ .Values.apiService.ports.http }}
      targetPort: {{ .Values.apiService.ports.http }}
    - name: grpc
      port: {{ .Values.apiService.ports.grpc }}
      targetPort: {{ .Values.apiService.ports.grpc }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: api-service
spec:
  replicas: {{ .Values.apiService.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: api-service
      app.kubernetes.io/instance: {{ .Release.Name | quote }}
  template:
    metadata:
      labels:
        {{- include "mochat.labels" . | nindent 8 }}
        app.kubernetes.io/name: api-service
      annotations:
        {{- include "mochat.prometheusAnnotations" . | nindent 8 }}
        prometheus.io/port: {{ .Values.apiService.ports.http | quote }}
    spec:
      serviceAccountName: {{ include "mochat.serviceAccountName" . }}
      containers:
        - name: api-service
          image: {{ include "mochat.image" (list . .Values.apiService.image) | quote }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          envFrom:
            - configMapRef:
                name: mochat-runtime-config
            - configMapRef:
                name: mochat-external-dependencies
            - configMapRef:
                name: mochat-observability
          env:
            - name: OTEL_SERVICE_NAME
              value: api-service
          ports:
            - name: http
              containerPort: {{ .Values.apiService.ports.http }}
            - name: grpc
              containerPort: {{ .Values.apiService.ports.grpc }}
{{- end }}
```

- [ ] **Step 3: Create `message-service` template**

Write `deploy/helm/mochat/templates/message-service.yaml`:

```gotemplate
{{- if .Values.messageService.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: message-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: message-service
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: message-service
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
  ports:
    - name: grpc
      port: {{ .Values.messageService.ports.grpc }}
      targetPort: {{ .Values.messageService.ports.grpc }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: message-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: message-service
spec:
  replicas: {{ .Values.messageService.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: message-service
      app.kubernetes.io/instance: {{ .Release.Name | quote }}
  template:
    metadata:
      labels:
        {{- include "mochat.labels" . | nindent 8 }}
        app.kubernetes.io/name: message-service
    spec:
      serviceAccountName: {{ include "mochat.serviceAccountName" . }}
      containers:
        - name: message-service
          image: {{ include "mochat.image" (list . .Values.messageService.image) | quote }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          envFrom:
            - configMapRef:
                name: mochat-runtime-config
            - configMapRef:
                name: mochat-external-dependencies
            - configMapRef:
                name: mochat-observability
          env:
            - name: OTEL_SERVICE_NAME
              value: message-service
            - name: MOCHAT_RUNTIME_POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: MOCHAT_RUNTIME_POD_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
          ports:
            - name: grpc
              containerPort: {{ .Values.messageService.ports.grpc }}
{{- end }}
```

- [ ] **Step 4: Create `persistence-service` template**

Write `deploy/helm/mochat/templates/persistence-service.yaml`:

```gotemplate
{{- if .Values.persistenceService.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: persistence-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: persistence-service
spec:
  replicas: {{ .Values.persistenceService.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: persistence-service
      app.kubernetes.io/instance: {{ .Release.Name | quote }}
  template:
    metadata:
      labels:
        {{- include "mochat.labels" . | nindent 8 }}
        app.kubernetes.io/name: persistence-service
    spec:
      serviceAccountName: {{ include "mochat.serviceAccountName" . }}
      containers:
        - name: persistence-service
          image: {{ include "mochat.image" (list . .Values.persistenceService.image) | quote }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          envFrom:
            - configMapRef:
                name: mochat-runtime-config
            - configMapRef:
                name: mochat-external-dependencies
            - secretRef:
                name: {{ .Values.externalDependencies.secret.name }}
            - configMapRef:
                name: mochat-observability
          env:
            - name: OTEL_SERVICE_NAME
              value: persistence-service
            - name: MOCHAT_PERSISTENCE_SERVICE_QUEUE_CONSUMER_ENABLED
              value: {{ .Values.persistenceService.queueConsumerEnabled | quote }}
{{- end }}
```

- [ ] **Step 5: Create `access-gateway` template**

Write `deploy/helm/mochat/templates/access-gateway.yaml`:

```gotemplate
{{- if .Values.accessGateway.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: access-gateway-headless
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: access-gateway
spec:
  clusterIP: None
  selector:
    app.kubernetes.io/name: access-gateway
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
  ports:
    - name: grpc
      port: {{ .Values.accessGateway.ports.grpc }}
      targetPort: {{ .Values.accessGateway.ports.grpc }}
---
apiVersion: v1
kind: Service
metadata:
  name: access-gateway-tcp
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: access-gateway
spec:
  type: NodePort
  selector:
    app.kubernetes.io/name: access-gateway
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
  ports:
    - name: tcp
      port: {{ .Values.accessGateway.ports.tcp }}
      targetPort: {{ .Values.accessGateway.ports.tcp }}
      nodePort: {{ .Values.accessGateway.ports.nodePort }}
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: access-gateway
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: access-gateway
spec:
  serviceName: access-gateway-headless
  replicas: {{ .Values.accessGateway.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: access-gateway
      app.kubernetes.io/instance: {{ .Release.Name | quote }}
  template:
    metadata:
      labels:
        {{- include "mochat.labels" . | nindent 8 }}
        app.kubernetes.io/name: access-gateway
      annotations:
        {{- include "mochat.prometheusAnnotations" . | nindent 8 }}
        prometheus.io/port: {{ .Values.accessGateway.ports.admin | quote }}
    spec:
      serviceAccountName: {{ include "mochat.serviceAccountName" . }}
      terminationGracePeriodSeconds: {{ .Values.accessGateway.terminationGracePeriodSeconds }}
      containers:
        - name: access-gateway
          image: {{ include "mochat.image" (list . .Values.accessGateway.image) | quote }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          envFrom:
            - configMapRef:
                name: mochat-runtime-config
            - configMapRef:
                name: mochat-external-dependencies
            - configMapRef:
                name: mochat-observability
          env:
            - name: OTEL_SERVICE_NAME
              value: access-gateway
            - name: MOCHAT_ACCESS_GATEWAY_HTTP_PORT
              value: {{ .Values.accessGateway.ports.admin | quote }}
            - name: MOCHAT_ACCESS_GATEWAY_TCP_PORT
              value: {{ .Values.accessGateway.ports.tcp | quote }}
            - name: MOCHAT_ACCESS_GATEWAY_GRPC_PORT
              value: {{ .Values.accessGateway.ports.grpc | quote }}
            - name: MOCHAT_ACCESS_GATEWAY_TLS_CERTIFICATE_PATH
              value: /var/run/mochat/tls/tls.crt
            - name: MOCHAT_ACCESS_GATEWAY_TLS_PRIVATE_KEY_PATH
              value: /var/run/mochat/tls/tls.key
            - name: MOCHAT_ACCESS_GATEWAY_TLS_SELF_SIGNED
              value: "false"
            - name: MOCHAT_ACCESS_GATEWAY_DRAIN_SHUTDOWN_WAIT_ENABLED
              value: "true"
            - name: MOCHAT_ACCESS_GATEWAY_DRAIN_GRACE_PERIOD
              value: {{ .Values.accessGateway.drainGracePeriod | quote }}
            - name: MOCHAT_RUNTIME_POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: MOCHAT_RUNTIME_POD_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
          ports:
            - name: admin
              containerPort: {{ .Values.accessGateway.ports.admin }}
            - name: tcp
              containerPort: {{ .Values.accessGateway.ports.tcp }}
            - name: grpc
              containerPort: {{ .Values.accessGateway.ports.grpc }}
          volumeMounts:
            - name: access-gateway-tls
              mountPath: /var/run/mochat/tls
              readOnly: true
          readinessProbe:
            httpGet:
              path: /internal/lifecycle/readyz
              port: {{ .Values.accessGateway.ports.admin }}
            periodSeconds: 2
            failureThreshold: 1
          livenessProbe:
            httpGet:
              path: /internal/lifecycle/livez
              port: {{ .Values.accessGateway.ports.admin }}
            periodSeconds: 10
            failureThreshold: 3
          lifecycle:
            preStop:
              httpGet:
                path: /internal/lifecycle/drain
                port: {{ .Values.accessGateway.ports.admin }}
      volumes:
        - name: access-gateway-tls
          secret:
            secretName: {{ .Values.accessGatewayTls.secretName }}
{{- end }}
```

- [ ] **Step 6: Create `call-service` template**

Write `deploy/helm/mochat/templates/call-service.yaml`:

```gotemplate
{{- if .Values.callService.enabled }}
apiVersion: v1
kind: Service
metadata:
  name: call-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: call-service
  annotations:
    {{- include "mochat.prometheusAnnotations" . | nindent 4 }}
    prometheus.io/port: {{ .Values.callService.ports.http | quote }}
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: call-service
    app.kubernetes.io/instance: {{ .Release.Name | quote }}
  ports:
    - name: http
      port: {{ .Values.callService.ports.http }}
      targetPort: {{ .Values.callService.ports.http }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: call-service
  namespace: {{ include "mochat.namespace" . }}
  labels:
    {{- include "mochat.labels" . | nindent 4 }}
    app.kubernetes.io/name: call-service
spec:
  replicas: {{ .Values.callService.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: call-service
      app.kubernetes.io/instance: {{ .Release.Name | quote }}
  template:
    metadata:
      labels:
        {{- include "mochat.labels" . | nindent 8 }}
        app.kubernetes.io/name: call-service
      annotations:
        {{- include "mochat.prometheusAnnotations" . | nindent 8 }}
        prometheus.io/port: {{ .Values.callService.ports.http | quote }}
    spec:
      serviceAccountName: {{ include "mochat.serviceAccountName" . }}
      containers:
        - name: call-service
          image: {{ include "mochat.image" (list . .Values.callService.image) | quote }}
          imagePullPolicy: {{ .Values.global.imagePullPolicy }}
          envFrom:
            - configMapRef:
                name: mochat-runtime-config
            - configMapRef:
                name: mochat-external-dependencies
            - secretRef:
                name: {{ .Values.externalDependencies.secret.name }}
            - secretRef:
                name: {{ .Values.livekit.secretName }}
            - configMapRef:
                name: mochat-observability
          env:
            - name: OTEL_SERVICE_NAME
              value: call-service
            - name: MOCHAT_CALL_SERVICE_HTTP_HOST
              value: "0.0.0.0"
            - name: MOCHAT_CALL_SERVICE_HTTP_PORT
              value: {{ .Values.callService.ports.http | quote }}
            - name: MOCHAT_CALL_SERVICE_FLYWAY_MIGRATE_ON_START
              value: {{ .Values.callService.flywayMigrateOnStart | quote }}
            - name: MOCHAT_CALL_SERVICE_QUEUE_CONSUMER_ENABLED
              value: {{ .Values.callService.queueConsumerEnabled | quote }}
          ports:
            - name: http
              containerPort: {{ .Values.callService.ports.http }}
{{- end }}
```

- [ ] **Step 7: Render the chart**

Run:

```bash
rtk helm template mochat deploy/helm/mochat -f deploy/helm/mochat/values-local.yaml
```

Expected: output includes `kind: Deployment` with `name: call-service`, `kind: StatefulSet` with `name: access-gateway`, and `kind: Secret` with `name: mochat-livekit`.

- [ ] **Step 8: Commit**

```bash
rtk git add deploy/helm/mochat
rtk git commit -m "feat: 增加mochat五服务Helm模板"
```

---

### Task 4: Add Helm Contract Tests

**Files:**
- Create: `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`

- [ ] **Step 1: Write the failing test**

Create `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`:

```java
package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmMoChatChartContractTest {
    @Test
    void helmTemplateRendersFiveMochatServices() throws Exception {
        List<Map<String, Object>> manifests = renderChart();

        assertHasManifest(manifests, "Deployment", "api-service");
        assertHasManifest(manifests, "Deployment", "message-service");
        assertHasManifest(manifests, "Deployment", "persistence-service");
        assertHasManifest(manifests, "StatefulSet", "access-gateway");
        assertHasManifest(manifests, "Deployment", "call-service");

        assertHasManifest(manifests, "Service", "api-service");
        assertHasManifest(manifests, "Service", "message-service");
        assertHasManifest(manifests, "Service", "access-gateway-headless");
        assertHasManifest(manifests, "Service", "access-gateway-tcp");
        assertHasManifest(manifests, "Service", "call-service");
    }

    @Test
    void callServiceUsesSingleReplicaLivekitSecretAndHttpPort8090() throws Exception {
        List<Map<String, Object>> manifests = renderChart();
        Map<String, Object> deployment = manifest(manifests, "Deployment", "call-service");

        assertEquals(1, nestedMap(deployment, "spec").get("replicas"));
        Map<String, Object> container = firstContainer(deployment);
        assertHasContainerPort(container, "http", 8090);
        assertEnvFromSecret(container, "mochat-livekit");
        assertEnvFromSecret(container, "mochat-external-dependency-secrets");

        Map<String, Object> service = manifest(manifests, "Service", "call-service");
        assertHasServicePort(service, "http", 8090);
    }

    @Test
    void allWorkloadsExposeAiopsCorrelationLabels() throws Exception {
        List<Map<String, Object>> manifests = renderChart();
        for (String name : List.of("api-service", "message-service", "persistence-service", "call-service")) {
            assertWorkloadLabels(manifest(manifests, "Deployment", name), name);
        }
        assertWorkloadLabels(manifest(manifests, "StatefulSet", "access-gateway"), "access-gateway");
    }

    @Test
    void prometheusAnnotationsAreRenderedForHttpScrapeTargets() throws Exception {
        List<Map<String, Object>> manifests = renderChart();
        assertPrometheusAnnotation(manifest(manifests, "Deployment", "api-service"), "8080");
        assertPrometheusAnnotation(manifest(manifests, "StatefulSet", "access-gateway"), "18080");
        assertPrometheusAnnotation(manifest(manifests, "Deployment", "call-service"), "8090");
    }

    private List<Map<String, Object>> renderChart() throws Exception {
        Path root = repositoryRoot();
        Process process = new ProcessBuilder(
            "helm",
            "template",
            "mochat",
            root.resolve("deploy/helm/mochat").toString(),
            "-f",
            root.resolve("deploy/helm/mochat/values-local.yaml").toString()
        )
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();

        boolean completed = process.waitFor(Duration.ofSeconds(30));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(completed, () -> "helm template timed out:\n" + output);
        assertEquals(0, process.exitValue(), () -> "helm template failed:\n" + output);

        List<Map<String, Object>> result = new ArrayList<>();
        Yaml yaml = new Yaml();
        for (Object document : yaml.loadAll(output)) {
            if (document instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) raw;
                result.add(typed);
            }
        }
        return result;
    }

    private void assertHasManifest(List<Map<String, Object>> manifests, String kind, String name) {
        manifest(manifests, kind, name);
    }

    private Map<String, Object> manifest(List<Map<String, Object>> manifests, String kind, String name) {
        return manifests.stream()
            .filter(entry -> kind.equals(entry.get("kind")))
            .filter(entry -> name.equals(nestedMap(entry, "metadata").get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing " + kind + "/" + name));
    }

    private Map<String, Object> firstContainer(Map<String, Object> workload) {
        return nestedList(nestedMap(workload, "spec", "template", "spec"), "containers").get(0);
    }

    private void assertWorkloadLabels(Map<String, Object> workload, String serviceName) {
        Map<String, Object> labels = nestedMap(workload, "spec", "template", "metadata", "labels");
        assertEquals(serviceName, labels.get("app.kubernetes.io/name"));
        assertEquals("mochat", labels.get("app.kubernetes.io/part-of"));
        assertEquals("mochat", labels.get("app.kubernetes.io/instance"));
        assertEquals("mochat-local", labels.get("mochat.lystran.io/project-id"));
        assertEquals("local", labels.get("mochat.lystran.io/environment"));
    }

    private void assertPrometheusAnnotation(Map<String, Object> workload, String port) {
        Map<String, Object> annotations = nestedMap(workload, "spec", "template", "metadata", "annotations");
        assertEquals("true", annotations.get("prometheus.io/scrape"));
        assertEquals("/prometheus", annotations.get("prometheus.io/path"));
        assertEquals(port, annotations.get("prometheus.io/port"));
    }

    private void assertEnvFromSecret(Map<String, Object> container, String name) {
        List<Map<String, Object>> envFrom = nestedList(container, "envFrom");
        assertTrue(
            envFrom.stream().anyMatch(entry ->
                entry.containsKey("secretRef") && Objects.equals(name, nestedMap(entry, "secretRef").get("name"))
            ),
            () -> "Missing secretRef " + name
        );
    }

    private void assertHasContainerPort(Map<String, Object> container, String name, int port) {
        List<Map<String, Object>> ports = nestedList(container, "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("containerPort"))),
            () -> "Missing container port " + name + "=" + port
        );
    }

    private void assertHasServicePort(Map<String, Object> service, String name, int port) {
        List<Map<String, Object>> ports = nestedList(nestedMap(service, "spec"), "ports");
        assertTrue(
            ports.stream().anyMatch(entry -> Objects.equals(name, entry.get("name")) && Objects.equals(port, entry.get("port"))),
            () -> "Missing service port " + name + "=" + port
        );
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> source, String... path) {
        Object current = source;
        for (String segment : path) {
            assertTrue(current instanceof Map<?, ?>, () -> "Expected map at " + String.join(".", path));
            current = ((Map<String, Object>) current).get(segment);
            assertNotNull(current, () -> "Missing path " + String.join(".", path));
        }
        return (Map<String, Object>) current;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nestedList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertTrue(value instanceof List<?>, () -> "Expected list at " + key);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : (List<Object>) value) {
            assertTrue(entry instanceof Map<?, ?>, () -> "Expected map in " + key);
            result.add((Map<String, Object>) entry);
        }
        return result;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails before templates are complete**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected before Task 3 is complete: FAIL with `Missing Deployment/call-service` or `helm template failed`.

- [ ] **Step 3: Run the test after Task 3**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected after Task 3 is complete: PASS.

- [ ] **Step 4: Commit**

```bash
rtk git add service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java
rtk git commit -m "test: 增加mochat Helm chart契约测试"
```

---

### Task 5: Add Native-First call-service Dockerfile

**Files:**
- Create: `call-service-app/Dockerfile`

- [ ] **Step 1: Write the failing check**

Run:

```bash
rtk test -f call-service-app/Dockerfile
```

Expected: command exits `1` because the Dockerfile does not exist.

- [ ] **Step 2: Create the Dockerfile**

Write `call-service-app/Dockerfile`:

```dockerfile
FROM ghcr.1ms.run/graalvm/native-image-community:25 AS builder

WORKDIR /workspace

COPY . .

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :call-service-app:nativeCompile

FROM gcr.1ms.run/distroless/cc

WORKDIR /app

COPY --from=builder --chown=65532:65532 /workspace/call-service-app/build/native/nativeCompile/call-service /app/call-service

USER 65532:65532

EXPOSE 8090

ENTRYPOINT ["/app/call-service"]
```

- [ ] **Step 3: Verify Dockerfile exists**

Run:

```bash
rtk test -f call-service-app/Dockerfile
```

Expected: command exits `0`.

- [ ] **Step 4: Run native compile if native-image is available**

Run:

```bash
rtk ./gradlew :call-service-app:nativeCompile
```

Expected:

- PASS if GraalVM native-image is available.
- If Gradle logs `native-image unavailable`, keep the Dockerfile and record the limitation in `deploy/helm/mochat/README.md` during Task 8. The root Gradle build already skips nativeCompile when native-image is missing.

- [ ] **Step 5: Commit**

```bash
rtk git add call-service-app/Dockerfile
rtk git commit -m "feat: 增加call-service native镜像构建"
```

---

### Task 6: Add Docker Compose Observability Stack

**Files:**
- Create: `deploy/observability/docker-compose.yml`
- Create: `deploy/observability/prometheus/prometheus.yml`
- Create: `deploy/observability/prometheus/rules/mochat-alerts.yml`
- Create: `deploy/observability/alertmanager/alertmanager.yml`
- Create: `deploy/observability/loki/loki.yml`
- Create: `deploy/observability/promtail/promtail.yml`
- Create: `deploy/observability/tempo/tempo.yml`
- Create: `deploy/observability/aiops/projects.yaml.example`

- [ ] **Step 1: Create observability compose file**

Write `deploy/observability/docker-compose.yml`:

```yaml
name: mochat-observability

services:
  prometheus:
    image: prom/prometheus:v3.0.1
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --web.enable-lifecycle
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rules:/etc/prometheus/rules:ro

  alertmanager:
    image: prom/alertmanager:v0.27.0
    command:
      - --config.file=/etc/alertmanager/alertmanager.yml
    ports:
      - "9093:9093"
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro

  loki:
    image: grafana/loki:3.2.1
    command: -config.file=/etc/loki/loki.yml
    ports:
      - "3100:3100"
    volumes:
      - ./loki/loki.yml:/etc/loki/loki.yml:ro

  promtail:
    image: grafana/promtail:3.2.1
    command: -config.file=/etc/promtail/promtail.yml
    volumes:
      - ./promtail/promtail.yml:/etc/promtail/promtail.yml:ro
      - /var/log:/var/log:ro

  tempo:
    image: grafana/tempo:2.6.1
    command: -config.file=/etc/tempo/tempo.yml
    ports:
      - "3200:3200"
      - "4317:4317"
      - "4318:4318"
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
```

- [ ] **Step 2: Create Prometheus config**

Write `deploy/observability/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules/*.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - alertmanager:9093

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets:
          - localhost:9090

  - job_name: mochat-port-forward
    metrics_path: /prometheus
    static_configs:
      - targets:
          - host.docker.internal:18080
        labels:
          service: access-gateway
          project_id: mochat-local
```

Write `deploy/observability/prometheus/rules/mochat-alerts.yml`:

```yaml
groups:
  - name: mochat-smoke
    rules:
      - alert: MoChatPrometheusSmoke
        expr: up{job="prometheus"} == 1
        for: 1m
        labels:
          severity: info
          service: prometheus
          project_id: mochat-local
        annotations:
          summary: "MoChat Prometheus smoke alert"
          description: "Prometheus is running and can evaluate alert rules."
```

- [ ] **Step 3: Create Alertmanager config**

Write `deploy/observability/alertmanager/alertmanager.yml`:

```yaml
route:
  receiver: aiops-webhook
  group_by: ["alertname", "service", "project_id"]
  group_wait: 10s
  group_interval: 1m
  repeat_interval: 1h

receivers:
  - name: aiops-webhook
    webhook_configs:
      - url: "http://host.docker.internal:8000/api/v1/alerts/webhook"
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: "replace-with-aiops-token"
```

- [ ] **Step 4: Create Loki and Promtail configs**

Write `deploy/observability/loki/loki.yml`:

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  path_prefix: /tmp/loki
  storage:
    filesystem:
      chunks_directory: /tmp/loki/chunks
      rules_directory: /tmp/loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h
```

Write `deploy/observability/promtail/promtail.yml`:

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: local-varlog
    static_configs:
      - targets:
          - localhost
        labels:
          job: varlogs
          project_id: mochat-local
          __path__: /var/log/*.log
```

- [ ] **Step 5: Create Tempo config**

Write `deploy/observability/tempo/tempo.yml`:

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

storage:
  trace:
    backend: local
    local:
      path: /tmp/tempo/traces
```

- [ ] **Step 6: Create AIOps project example**

Write `deploy/observability/aiops/projects.yaml.example`:

```yaml
default_project: mochat-local

projects:
  mochat-local:
    name: "mo-chat 本地联调"
    metric_profile: java
    enabled: true
    datasources:
      prometheus:
        base_url: "http://localhost:9090"
        verify_ssl: false
      loki:
        base_url: "http://localhost:3100"
        auth_type: NoAuth
        verify_ssl: false
      tempo:
        base_url: "http://localhost:3200"
        verify_ssl: false
      kubernetes:
        mode: kubeconfig
        kubeconfig_path: "~/.kube/config"
        namespaces: ["mochat"]
        verify_ssl: false
```

- [ ] **Step 7: Validate compose config**

Run:

```bash
rtk docker compose -f deploy/observability/docker-compose.yml config
```

Expected: Docker Compose prints normalized YAML and exits `0`.

- [ ] **Step 8: Commit**

```bash
rtk git add deploy/observability
rtk git commit -m "feat: 增加本地观测栈compose配置"
```

---

### Task 7: Add Helm and Observability Documentation

**Files:**
- Create: `deploy/helm/mochat/README.md`
- Create: `deploy/observability/README.md`
- Create: `zym-docs/mochat-aiops-deployment.md`

- [ ] **Step 1: Create Helm README**

Write `deploy/helm/mochat/README.md`:

```markdown
# MoChat Helm 部署

这个 chart 只部署 MoChat 五个业务服务：

- `api-service`
- `message-service`
- `persistence-service`
- `access-gateway`
- `call-service`

PostgreSQL、Redis、RocketMQ、Prometheus、Loki、Tempo、Alertmanager 和 AIOps 不属于这个 chart。

## 本地部署

先启动基础设施和观测栈：

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
```

构建本地镜像：

```bash
docker build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
docker build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
docker build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
docker build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
docker build -f call-service-app/Dockerfile -t localhost/mochat/call-service:dev .
```

部署：

```bash
helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-local.yaml
```

检查：

```bash
kubectl -n mochat get pods,svc
helm -n mochat status mochat
```

## call-service 限制

`call-service` 默认 `replicaCount: 1`。它当前把活跃通话房间保存在进程内内存中，不能直接无状态横向扩容。需要多副本时，先设计粘性路由或把房间状态外部化。

## OpenTelemetry

默认 native-first，不启用 Java Agent。chart 预留 OTEL 环境变量，但 native trace 不是第一阶段保证项。
```

- [ ] **Step 2: Create observability README**

Write `deploy/observability/README.md`:

```markdown
# MoChat 本地观测栈

这个目录提供本地联调用 Docker Compose 观测栈：

- Prometheus
- Loki
- Promtail
- Tempo
- Alertmanager

启动：

```bash
docker compose -f deploy/observability/docker-compose.yml up -d
```

停止：

```bash
docker compose -f deploy/observability/docker-compose.yml down
```

## AIOps 接入

外部 AIOps 项目配置可参考：

```text
deploy/observability/aiops/projects.yaml.example
```

默认数据源：

- Prometheus: `http://localhost:9090`
- Loki: `http://localhost:3100`
- Tempo: `http://localhost:3200`
- Alertmanager: `http://localhost:9093`

Alertmanager webhook 需要把 `deploy/observability/alertmanager/alertmanager.yml` 中的 URL 和 Bearer token 改成真实 AIOps 地址和 token。

## 日志采集说明

Promtail 在 Docker Compose 中运行时，默认只能采集宿主机 `/var/log/*.log`。MoChat Pod 日志如果运行在 Kubernetes 中，生产或完整联调应改用 Kubernetes 内的日志采集器。第一阶段主要验证 Prometheus、Alertmanager 和 AIOps webhook 链路。
```

- [ ] **Step 3: Create AIOps deployment guide**

Write `zym-docs/mochat-aiops-deployment.md`:

```markdown
# MoChat 接入外部 AIOps 的部署方式

MoChat 作为被监控系统部署，外部 AIOps 通过 Prometheus、Loki、Tempo、Alertmanager 和 Kubernetes API 接入。

## 部署边界

- MoChat 五个服务使用 Helm 部署到 Kubernetes。
- PostgreSQL、Redis、RocketMQ 使用 Docker Compose。
- Prometheus、Loki、Tempo、Alertmanager 使用 Docker Compose 做本地联调。
- AIOps 服务不由本项目部署。
- AIOps 对 Kubernetes 的操作权限不由本项目创建。

## 启动流程

```bash
docker compose up -d
docker compose -f deploy/observability/docker-compose.yml up -d
helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-local.yaml
```

## AIOps 项目配置

参考：

```text
deploy/observability/aiops/projects.yaml.example
```

推荐 project id：

```text
mochat-local
```

MoChat Kubernetes namespace：

```text
mochat
```

## Alertmanager Webhook

Alertmanager webhook 指向：

```text
<AIOPS_URL>/api/v1/alerts/webhook
```

请求需要：

```text
Authorization: Bearer <token>
X-Project-Id: mochat-local
```

## OpenTelemetry

MoChat 默认 native-first 部署，不默认使用 OpenTelemetry Java Agent。chart 会预留 OTEL 环境变量；native trace 作为后续增强。
```

- [ ] **Step 4: Commit**

```bash
rtk git add deploy/helm/mochat/README.md deploy/observability/README.md zym-docs/mochat-aiops-deployment.md
rtk git commit -m "docs: 增加AIOps部署联调文档"
```

---

### Task 8: Update runbook and Codebase Memory

**Files:**
- Modify: `docs/runbook.md`
- Modify: `docs/codebase/deployment/README.md`
- Modify: `docs/codebase/call-service/README.md`

- [ ] **Step 1: Update `docs/runbook.md`**

Rewrite the main sections in Simplified Chinese and make Docker the default command style. Preserve existing kustomize/kind fallback information, but add a new recommended Helm path near the top:

```markdown
# MoChat Kubernetes 与 AIOps 部署操作手册

这份 runbook 记录 MoChat 当前推荐部署路径：

- MoChat 五个业务服务使用 Helm 部署到 Kubernetes。
- PostgreSQL、Redis、RocketMQ 使用 Docker Compose。
- Prometheus、Loki、Tempo、Alertmanager 使用 Docker Compose 做本地联调。
- AIOps 是外部服务，本项目只提供被监控系统端点、标签和配置示例。

## 推荐本地流程

### 1. 启动基础设施

```bash
docker compose up -d
```

### 2. 启动观测栈

```bash
docker compose -f deploy/observability/docker-compose.yml up -d
```

### 3. 构建镜像

```bash
docker build -f access-gateway-app/Dockerfile -t localhost/mochat/access-gateway:dev .
docker build -f api-service-app/Dockerfile -t localhost/mochat/api-service:dev .
docker build -f message-service-app/Dockerfile -t localhost/mochat/message-service:dev .
docker build -f persistence-service-app/Dockerfile -t localhost/mochat/persistence-service:dev .
docker build -f call-service-app/Dockerfile -t localhost/mochat/call-service:dev .
```

### 4. 使用 Helm 部署 MoChat

```bash
helm upgrade --install mochat deploy/helm/mochat \
  --namespace mochat --create-namespace \
  -f deploy/helm/mochat/values-local.yaml
```

### 5. 检查部署

```bash
kubectl -n mochat get pods,svc
helm -n mochat status mochat
```

## AIOps 接入

外部 AIOps 使用 `deploy/observability/aiops/projects.yaml.example` 作为项目配置参考。
```

After adding the Helm path, keep the existing kustomize/kind section under a heading like:

```markdown
## 旧版 kustomize/kind 验证路径
```

Keep the static-address rollback section and translate any English heading such as `Rollback to the Current Static-Address Topology` to:

```markdown
## 回滚到当前静态地址拓扑
```

- [ ] **Step 2: Update deployment codebase memory**

In `docs/codebase/deployment/README.md`, update the current scope and paths. Add this content under the responsibility/current notes sections:

```markdown
- 推荐部署路径新增 `deploy/helm/mochat`，用于部署 `api-service`、`message-service`、`persistence-service`、`access-gateway`、`call-service`。
- 本地观测栈新增 `deploy/observability`，使用 Docker Compose 管理 Prometheus、Loki、Tempo、Alertmanager。
- 命令文档默认使用 Docker CLI；Podman 不再是 runbook 默认命令。
- AIOps 是外部服务，本项目只提供 metrics、logs、trace 预留、Alertmanager webhook 示例、Kubernetes labels 和 `projects.yaml.example`。
- `deploy/kubernetes/base` 和 `deploy/kubernetes/overlays/kind` 保留为旧版 kustomize/kind 验证路径。
```

Update the “主要代码路径” list to include:

```markdown
- Helm chart：`deploy/helm/mochat/**`
- 本地观测栈：`deploy/observability/**`
- call-service 镜像：`call-service-app/Dockerfile`
```

- [ ] **Step 3: Update call-service codebase memory**

In `docs/codebase/call-service/README.md`, add:

```markdown
- `call-service` 已纳入 Helm 部署，默认 `replicaCount: 1`。
- Kubernetes Service 端口为 HTTP/WebSocket `8090`。
- LiveKit 配置通过 `mochat-livekit` Secret 注入 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。
- `call-service-app/Dockerfile` 优先使用 native image 构建。
- 当前活跃房间状态仍在 JVM 内存中，不能直接多副本无状态扩容。
```

- [ ] **Step 4: Verify docs mention Docker and no old English heading**

Run:

```bash
rtk rg -n "Rollback to the Current Static-Address Topology" docs/runbook.md
```

Expected: no matches.

Run:

```bash
rtk rg -n "docker compose|deploy/helm/mochat|deploy/observability" docs/runbook.md docs/codebase/deployment/README.md docs/codebase/call-service/README.md
```

Expected: matches in the updated files.

- [ ] **Step 5: Commit**

```bash
rtk git add docs/runbook.md docs/codebase/deployment/README.md docs/codebase/call-service/README.md
rtk git commit -m "docs: 更新Helm与AIOps部署说明"
```

---

### Task 9: Final Verification

**Files:**
- All files changed by Tasks 1-8.

- [ ] **Step 1: Run Helm lint**

```bash
rtk helm lint deploy/helm/mochat
```

Expected: `1 chart(s) linted, 0 chart(s) failed`.

- [ ] **Step 2: Render Helm chart**

```bash
rtk helm template mochat deploy/helm/mochat -f deploy/helm/mochat/values-local.yaml
```

Expected: output includes all five services and exits `0`.

- [ ] **Step 3: Run service-runtime contract tests**

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected: PASS.

- [ ] **Step 4: Validate observability compose**

```bash
rtk docker compose -f deploy/observability/docker-compose.yml config
```

Expected: normalized compose YAML and exit `0`.

- [ ] **Step 5: Check docs and status**

```bash
rtk rg -n "deploy/helm/mochat|deploy/observability|docker compose|mochat-local" docs deploy zym-docs
rtk git status --short
```

Expected:

- `rg` finds the new deployment and AIOps references.
- `git status --short` only shows intentional files before the final commit, or is clean after all commits.

- [ ] **Step 6: Final commit if verification fixes were needed**

If any verification step required additional fixes:

```bash
rtk git add <changed-files>
rtk git commit -m "test: 完成Helm与AIOps部署验证"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review Notes

- Spec coverage: The plan covers Helm chart, five services including `call-service`, native-first Dockerfile, Docker Compose observability stack, external AIOps project example, Docker-first runbook, codebase memory updates, and verification.
- Red-flag scan: The plan avoids unfinished marker words and vague implementation instructions.
- Type and name consistency: Service names, labels, Secret names, ports, and values keys are consistent across templates, tests, and docs.
