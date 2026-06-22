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
{{- if $root.Values.global.imageRegistry -}}
{{- printf "%s/%s:%s" $root.Values.global.imageRegistry $image.repository $image.tag -}}
{{- else -}}
{{- printf "%s:%s" $image.repository $image.tag -}}
{{- end -}}
{{- end -}}

{{- define "mochat.validateCommonLabels" -}}
{{- $reserved := list "app.kubernetes.io/name" "app.kubernetes.io/instance" "app.kubernetes.io/part-of" "app.kubernetes.io/managed-by" "helm.sh/chart" "mochat.lystran.io/project-id" "mochat.lystran.io/environment" -}}
{{- range $key := $reserved -}}
{{- if hasKey $.Values.global.commonLabels $key -}}
{{- fail (printf "global.commonLabels must not override reserved label %q" $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "mochat.labels" -}}
{{- include "mochat.validateCommonLabels" . -}}
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
