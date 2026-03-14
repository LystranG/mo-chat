#!/usr/bin/env bash
set -euo pipefail

overlay_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${overlay_dir}/../../../.." && pwd)"
namespace="${K8S_NAMESPACE:-mochat}"
probe_pod="mochat-kind-routing-probe"

cleanup() {
  kubectl delete pod "${probe_pod}" -n "${namespace}" --ignore-not-found=true >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "${SKIP_MINIMAL_TOPOLOGY:-0}" != "1" ]]; then
  bash "${overlay_dir}/verify-minimal-topology.sh"
fi

kubectl scale statefulset/access-gateway -n "${namespace}" --replicas=3
kubectl rollout status statefulset/access-gateway -n "${namespace}" --timeout=180s

kubectl delete pod "${probe_pod}" -n "${namespace}" --ignore-not-found=true >/dev/null 2>&1 || true
kubectl run "${probe_pod}" -n "${namespace}" --image=busybox:1.36 --restart=Never --command -- sh -c '
  nslookup access-gateway-2.access-gateway-headless.mochat.svc.cluster.local
  wget -qO- http://access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:18080/internal/lifecycle/readyz >/dev/null
  wget -qO- http://access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:18080/internal/lifecycle/drain >/dev/null
  ! wget -qO- http://access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:18080/internal/lifecycle/readyz >/dev/null
  wget -qO- http://access-gateway-0.access-gateway-headless.mochat.svc.cluster.local:18080/internal/lifecycle/livez >/dev/null
'
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/"${probe_pod}" -n "${namespace}" --timeout=120s
kubectl logs "${probe_pod}" -n "${namespace}"

if ! kubectl get endpointslice -n "${namespace}" -l kubernetes.io/service-name=access-gateway-headless -o json \
  | jq -e '.items[].endpoints[] | select(.hostname=="access-gateway-0") | .conditions.ready == false' >/dev/null; then
  printf 'Drained gateway endpoint did not become unready\n' >&2
  exit 1
fi

kubectl delete pod access-gateway-0 -n "${namespace}" --wait=true
kubectl rollout status statefulset/access-gateway -n "${namespace}" --timeout=240s

before_uid="$(
  kubectl get pod access-gateway-0 -n "${namespace}" -o jsonpath="{.metadata.uid}"
)"
kubectl rollout restart statefulset/access-gateway -n "${namespace}"
kubectl rollout status statefulset/access-gateway -n "${namespace}" --timeout=240s
after_uid="$(
  kubectl get pod access-gateway-0 -n "${namespace}" -o jsonpath="{.metadata.uid}"
)"
if [[ "${before_uid}" == "${after_uid}" ]]; then
  printf 'Rollout restart did not recreate access-gateway-0\n' >&2
  exit 1
fi

(
  cd "${repo_root}"
  ./gradlew :message-service-app:test \
    --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest \
    --rerun-tasks
)

(
  cd "${repo_root}"
  ./gradlew :access-gateway-app:test \
    --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills \
    --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace \
    --tests com.github.lystran.mochat.accessgateway.runtime.GatewayIngressLifecycleTest \
    --tests com.github.lystran.mochat.accessgateway.AccessGatewayLifecycleEndpointTest \
    --rerun-tasks
)

kubectl scale statefulset/access-gateway -n "${namespace}" --replicas=2
kubectl rollout status statefulset/access-gateway -n "${namespace}" --timeout=180s

printf 'Routing, drain, rollout verification passed for %s\n' "${namespace}"
