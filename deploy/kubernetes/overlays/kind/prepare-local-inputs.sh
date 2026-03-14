#!/usr/bin/env bash
set -euo pipefail

overlay_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
local_dir="${overlay_dir}/.local"
tls_dir="${local_dir}/access-gateway-tls"
external_env="${local_dir}/external-dependencies.env"
secret_env="${local_dir}/external-dependency-secrets.env"
compose_project="${MOCHAT_KIND_COMPOSE_PROJECT:-ddd-demo}"

resolve_compose_container_name() {
  local service_name="$1"
  podman ps \
    --filter "label=com.docker.compose.project=${compose_project}" \
    --filter "label=com.docker.compose.service=${service_name}" \
    --format '{{.Names}}' \
    | head -n 1
}

container_ipv4() {
  local container_name="$1"
  podman inspect "${container_name}" \
    | jq -r '.[0].NetworkSettings.Networks | to_entries[] | .value.IPAddress | select(length > 0)' \
    | head -n 1
}

postgres_container="${MOCHAT_KIND_POSTGRES_CONTAINER:-$(resolve_compose_container_name postgres)}"
redis_container="${MOCHAT_KIND_REDIS_CONTAINER:-$(resolve_compose_container_name redis)}"
rocketmq_namesrv_container="${MOCHAT_KIND_ROCKETMQ_NAMESRV_CONTAINER:-$(resolve_compose_container_name rocketmq-namesrv)}"
rocketmq_broker_container="${MOCHAT_KIND_ROCKETMQ_BROKER_CONTAINER:-$(resolve_compose_container_name rocketmq-broker)}"

postgres_container="${postgres_container:-ddd-demo-postgres-1}"
redis_container="${redis_container:-ddd-demo-redis}"
rocketmq_namesrv_container="${rocketmq_namesrv_container:-ddd-demo-rocketmq-namesrv}"
rocketmq_broker_container="${rocketmq_broker_container:-ddd-demo-rocketmq-broker}"

postgres_host="${MOCHAT_KIND_POSTGRES_HOST:-$(container_ipv4 "${postgres_container}")}"
redis_host="${MOCHAT_KIND_REDIS_HOST:-$(container_ipv4 "${redis_container}")}"
rocketmq_namesrv_host="${MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST:-$(container_ipv4 "${rocketmq_namesrv_container}")}"
rocketmq_broker_host="${MOCHAT_KIND_ROCKETMQ_BROKER_HOST:-$(container_ipv4 "${rocketmq_broker_container}")}"

for required_value in \
  "MOCHAT_KIND_POSTGRES_HOST:${postgres_host}" \
  "MOCHAT_KIND_REDIS_HOST:${redis_host}" \
  "MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST:${rocketmq_namesrv_host}" \
  "MOCHAT_KIND_ROCKETMQ_BROKER_HOST:${rocketmq_broker_host}"
do
  key="${required_value%%:*}"
  value="${required_value#*:}"
  if [[ -z "${value}" ]]; then
    printf 'Unable to determine %s; ensure the local Podman compose dependency is running or override it explicitly\n' "${key}" >&2
    exit 1
  fi
done

mkdir -p "${tls_dir}"

cat >"${external_env}" <<EOF
MOCHAT_KIND_POSTGRES_HOST=${postgres_host}
MOCHAT_KIND_REDIS_HOST=${redis_host}
MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST=${rocketmq_namesrv_host}
MOCHAT_KIND_ROCKETMQ_BROKER_HOST=${rocketmq_broker_host}
MOCHAT_REDIS_URI=redis://${redis_host}:6379
MOCHAT_POSTGRES_URL=jdbc:postgresql://${postgres_host}:5432/mochat
MOCHAT_ROCKETMQ_NAME_SERVER=${rocketmq_namesrv_host}:9876
MOCHAT_ROCKETMQ_TOPIC=mochat.messages
EOF

cat >"${secret_env}" <<'EOF'
MOCHAT_POSTGRES_USERNAME=mochat
MOCHAT_POSTGRES_PASSWORD=mochat
EOF

if [[ ! -s "${tls_dir}/tls.crt" || ! -s "${tls_dir}/tls.key" || "${FORCE_REGENERATE_TLS:-0}" == "1" ]]; then
  openssl req -x509 -nodes -newkey rsa:2048 -sha256 -days "${TLS_VALID_DAYS:-30}" \
    -keyout "${tls_dir}/tls.key" \
    -out "${tls_dir}/tls.crt" \
    -subj "/CN=host.containers.internal" \
    -addext "subjectAltName=DNS:host.containers.internal,DNS:localhost,IP:127.0.0.1,DNS:access-gateway-tcp.mochat.svc,DNS:access-gateway-tcp.mochat.svc.cluster.local" \
    >/dev/null 2>&1
fi

printf 'Prepared kind overlay inputs in %s using compose container IPs postgres=%s redis=%s namesrv=%s broker=%s\n' \
  "${local_dir}" \
  "${postgres_host}" \
  "${redis_host}" \
  "${rocketmq_namesrv_host}" \
  "${rocketmq_broker_host}"
