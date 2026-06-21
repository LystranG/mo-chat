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

{{- define "mo-chat-observability.mochatConfigMapName" -}}
{{- printf "%s-config" (include "mo-chat-observability.mochatName" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mo-chat-observability.mochatSecretName" -}}
{{- printf "%s-secret" (include "mo-chat-observability.mochatName" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mo-chat-observability.tempoServiceName" -}}
{{- printf "%s-tempo" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mo-chat-observability.otlpEndpoint" -}}
{{- if .Values.observability.otel.exporterOtlpEndpoint -}}
{{- .Values.observability.otel.exporterOtlpEndpoint -}}
{{- else if .Values.tempo.enabled -}}
{{- printf "http://%s:4318" (include "mo-chat-observability.tempoServiceName" .) -}}
{{- else if .Values.observability.otel.enabled -}}
{{- fail "observability.otel.exporterOtlpEndpoint must be set when observability.otel.enabled=true and tempo.enabled=false" -}}
{{- end -}}
{{- end -}}

{{- define "mo-chat-observability.javaToolOptions" -}}
{{- if .Values.observability.otel.enabled -}}
{{- printf "%s -javaagent:/otel-auto-instrumentation/javaagent.jar" .Values.mochat.env.javaToolOptions | trim -}}
{{- else -}}
{{- .Values.mochat.env.javaToolOptions -}}
{{- end -}}
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

{{- define "mo-chat-observability.safePodLabels" -}}
{{- $labels := omit .Values.mochat.podLabels "app.kubernetes.io/name" "app.kubernetes.io/instance" "app.kubernetes.io/component" -}}
{{- if and (hasKey $labels "app") (kindIs "map" (get $labels "app")) -}}
{{- $appLabels := get $labels "app" -}}
{{- if and (hasKey $appLabels "kubernetes") (kindIs "map" (get $appLabels "kubernetes")) -}}
{{- $kubernetesLabels := get $appLabels "kubernetes" -}}
{{- if or (hasKey $kubernetesLabels "io/name") (hasKey $kubernetesLabels "io/instance") (hasKey $kubernetesLabels "io/component") -}}
{{- $labels = omit $labels "app" -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $labels | toYaml -}}
{{- end -}}
