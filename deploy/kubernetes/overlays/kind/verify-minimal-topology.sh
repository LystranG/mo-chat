#!/usr/bin/env bash
set -euo pipefail

overlay_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${overlay_dir}/../../../.." && pwd)"
namespace="${K8S_NAMESPACE:-mochat}"
kind_cluster_name="${KIND_CLUSTER_NAME:-kind-cluster}"
kind_network="${KIND_NETWORK:-kind}"
expected_context="${KUBECTL_CONTEXT:-kind-kind-cluster}"
probe_pod="mochat-kind-minimal-probe"
archive_dir="$(mktemp -d /tmp/mochat-kind-images.XXXXXX)"
external_env="${overlay_dir}/.local/external-dependencies.env"

cleanup() {
  kubectl delete pod "${probe_pod}" -n "${namespace}" --ignore-not-found=true >/dev/null 2>&1 || true
  rm -rf "${archive_dir}"
}
trap cleanup EXIT

bash "${overlay_dir}/prepare-local-inputs.sh"
set -a
source "${external_env}"
set +a

: "${MOCHAT_KIND_POSTGRES_HOST:?prepare-local-inputs.sh did not define MOCHAT_KIND_POSTGRES_HOST}"
: "${MOCHAT_KIND_REDIS_HOST:?prepare-local-inputs.sh did not define MOCHAT_KIND_REDIS_HOST}"
: "${MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST:?prepare-local-inputs.sh did not define MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST}"
: "${MOCHAT_KIND_ROCKETMQ_BROKER_HOST:?prepare-local-inputs.sh did not define MOCHAT_KIND_ROCKETMQ_BROKER_HOST}"

if [[ "$(kubectl config current-context)" != "${expected_context}" ]]; then
  printf 'kubectl context must be %s\n' "${expected_context}" >&2
  exit 1
fi

for required_port in 5432 6379 9876; do
  if ! ss -ltn | rg -q ":${required_port}\\b"; then
    printf 'Required host port %s is not listening\n' "${required_port}" >&2
    exit 1
  fi
done

for image in \
  localhost/mochat/access-gateway:dev \
  localhost/mochat/api-service:dev \
  localhost/mochat/message-service:dev \
  localhost/mochat/persistence-service:dev
do
  archive_path="${archive_dir}/$(printf '%s' "${image}" | tr '/:' '_').tar"
  docker save -o "${archive_path}" "${image}"
  kind load image-archive "${archive_path}" --name "${kind_cluster_name}"
done

kubectl apply -k "${overlay_dir}"
kubectl rollout restart deployment/api-service -n "${namespace}"
kubectl rollout restart deployment/message-service -n "${namespace}"
kubectl rollout restart deployment/persistence-service -n "${namespace}"
kubectl rollout restart statefulset/access-gateway -n "${namespace}"
# Force StatefulSet pods to be recreated so the same :dev tag resolves to the freshly loaded node image.
kubectl delete pod -n "${namespace}" -l app.kubernetes.io/name=access-gateway --wait=true
kubectl rollout status deployment/api-service -n "${namespace}" --timeout=180s
kubectl rollout status deployment/message-service -n "${namespace}" --timeout=180s
kubectl rollout status deployment/persistence-service -n "${namespace}" --timeout=180s
kubectl rollout status statefulset/access-gateway -n "${namespace}" --timeout=180s

pod_name_field_ref="$(
  kubectl get statefulset access-gateway -n "${namespace}" \
    -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name=='MOCHAT_RUNTIME_POD_NAME')].valueFrom.fieldRef.fieldPath}"
)"
if [[ "${pod_name_field_ref}" != "metadata.name" ]]; then
  printf 'MOCHAT_RUNTIME_POD_NAME is not wired from pod metadata\n' >&2
  exit 1
fi

kubectl delete pod "${probe_pod}" -n "${namespace}" --ignore-not-found=true >/dev/null 2>&1 || true
kubectl run "${probe_pod}" -n "${namespace}" --image=busybox:1.36 --restart=Never --command -- sh -c "
  nslookup api-service.mochat.svc.cluster.local
  nslookup access-gateway-0.access-gateway-headless.mochat.svc.cluster.local
  for endpoint in \
    ${MOCHAT_KIND_POSTGRES_HOST}:5432 \
    ${MOCHAT_KIND_REDIS_HOST}:6379 \
    ${MOCHAT_KIND_ROCKETMQ_NAMESRV_HOST}:9876 \
    ${MOCHAT_KIND_ROCKETMQ_BROKER_HOST}:10909 \
    ${MOCHAT_KIND_ROCKETMQ_BROKER_HOST}:10911 \
    ${MOCHAT_KIND_ROCKETMQ_BROKER_HOST}:10912
  do
    host=\${endpoint%:*}
    port=\${endpoint#*:}
    nc -vz -w 2 "\$host" "\$port"
  done
  printf '*1\r\n\$4\r\nPING\r\n' | nc -w 2 '${MOCHAT_KIND_REDIS_HOST}' 6379 | grep -q '+PONG'
"
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/"${probe_pod}" -n "${namespace}" --timeout=120s
kubectl logs "${probe_pod}" -n "${namespace}"

node_ip="$(
  kubectl get node -o jsonpath="{.items[0].status.addresses[?(@.type=='InternalIP')].address}"
)"
node_port="$(
  kubectl get service access-gateway-tcp -n "${namespace}" \
    -o jsonpath="{.spec.ports[?(@.name=='tcp')].nodePort}"
)"
if ! timeout 10 docker run --rm --network "${kind_network}" docker.io/library/busybox:1.36 \
  sh -c "nc -vz -w 2 ${node_ip} ${node_port}"; then
  printf 'NodePort %s on %s is not reachable from cluster-external probe on network %s\n' \
    "${node_port}" "${node_ip}" "${kind_network}" >&2
  exit 1
fi

printf 'Minimal kind topology verification passed for %s\n' "${namespace}"
