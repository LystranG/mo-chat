#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command="start"
image_mode="${MOCHAT_K8S_IMAGE_MODE:-jvm}"
namespace="${MOCHAT_K8S_NAMESPACE:-mochat}"
release_name="${MOCHAT_K8S_RELEASE:-mochat}"
observability_namespace="${MOCHAT_OBSERVABILITY_NAMESPACE:-mochat-observability}"
env_file="${MOCHAT_ENV_FILE:-$repo_root/.env}"
tls_dir="${MOCHAT_ACCESS_GATEWAY_TLS_DIR:-$repo_root/.local/helm/access-gateway-tls}"
tls_crt="$tls_dir/tls.crt"
tls_key="$tls_dir/tls.key"
build_images="${MOCHAT_K8S_BUILD_IMAGES:-1}"
deploy_observability="${MOCHAT_K8S_OBSERVABILITY:-1}"
enable_prometheus_scrape="${MOCHAT_K8S_PROMETHEUS_SCRAPE:-1}"
image_tag="${MOCHAT_K8S_IMAGE_TAG:-}"

fail() {
  echo "$1" >&2
  exit 2
}

parse_args() {
  local command_seen=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --native)
        image_mode="native"
        ;;
      --native-docker|--docker-compile)
        image_mode="native-docker"
        ;;
      --jvm)
        image_mode="jvm"
        ;;
      --skip-compile)
        build_images="0"
        ;;
      -h|--help|help)
        command="help"
        ;;
      start|stop|restart|status|verify-native)
        if [[ "$command_seen" == "1" ]]; then
          fail "Only one command is allowed."
        fi
        command="$1"
        command_seen=1
        ;;
      *)
        fail "Unknown argument: $1"
        ;;
    esac
    shift
  done
}

parse_args "$@"

if [[ -z "$image_tag" ]]; then
  case "$image_mode" in
    native|native-docker)
      image_tag="dev-native"
      ;;
    *)
      image_tag="dev"
      ;;
  esac
fi

usage() {
  cat <<EOF
Usage: scripts/run-local-k8s.sh [--native|--native-docker|--jvm] [--skip-compile] {start|stop|restart|status|verify-native}

Environment overrides:
  --skip-compile                  skip Gradle compile and Docker packaging for this run
  MOCHAT_K8S_BUILD_IMAGES=0        skip image build
  MOCHAT_K8S_IMAGE_MODE=native     use scripts/build-local-images.sh default native mode
  MOCHAT_K8S_IMAGE_MODE=native-docker use scripts/build-local-images.sh --docker-compile
  MOCHAT_K8S_IMAGE_TAG=dev-native  override image tag used by build and Helm
  MOCHAT_K8S_OBSERVABILITY=0       skip k3s observability stack
  MOCHAT_K8S_PROMETHEUS_SCRAPE=0   do not add Prometheus scrape annotations
  MOCHAT_ENV_FILE=.env             env file for LiveKit values
EOF
}

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || fail "Missing required command: $name"
}

release_exists() {
  helm -n "$namespace" status "$release_name" >/dev/null 2>&1
}

desired_image_for() {
  local service="$1"
  printf "localhost/mochat/%s:%s" "$service" "$image_tag"
}

workload_image() {
  local kind="$1"
  local name="$2"
  kubectl -n "$namespace" get "$kind" "$name" -o jsonpath="{.spec.template.spec.containers[0].image}" 2>/dev/null || true
}

current_images_match_desired() {
  [[ "$(workload_image deploy api-service)" == "$(desired_image_for api-service)" ]] || return 1
  [[ "$(workload_image deploy message-service)" == "$(desired_image_for message-service)" ]] || return 1
  [[ "$(workload_image deploy persistence-service)" == "$(desired_image_for persistence-service)" ]] || return 1
  [[ "$(workload_image deploy call-service)" == "$(desired_image_for call-service)" ]] || return 1
  [[ "$(workload_image statefulset access-gateway)" == "$(desired_image_for access-gateway)" ]] || return 1
}

load_env_if_present() {
  if [[ ! -f "$env_file" ]]; then
    echo "Env file not found: $env_file; LiveKit values will be empty."
    return
  fi

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

ensure_tls() {
  if [[ -f "$tls_crt" && -f "$tls_key" ]]; then
    return
  fi

  require_command openssl
  mkdir -p "$tls_dir"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -subj "/CN=localhost" \
    -keyout "$tls_key" \
    -out "$tls_crt"
}

warn_if_livekit_incomplete() {
  if [[ -z "${MOCHAT_LIVEKIT_URL:-}" || -z "${MOCHAT_LIVEKIT_API_KEY:-}" || -z "${MOCHAT_LIVEKIT_API_SECRET:-}" ]]; then
    echo "LiveKit env is incomplete; call-service can start, but LiveKit token requests will fail." >&2
  fi
}

build_images_if_needed() {
  if [[ "$build_images" != "1" ]]; then
    echo "Skipping image build because MOCHAT_K8S_BUILD_IMAGES=0"
    return
  fi

  echo "Building $image_mode images with tag=$image_tag"
  case "$image_mode" in
    jvm)
      IMAGE_TAG="$image_tag" "$repo_root/scripts/build-local-jvm-images.sh"
      ;;
    native)
      IMAGE_TAG="$image_tag" "$repo_root/scripts/build-local-images.sh"
      ;;
    native-docker)
      IMAGE_TAG="$image_tag" "$repo_root/scripts/build-local-images.sh" --docker-compile
      ;;
    *)
      fail "Unsupported image mode: $image_mode. Use --jvm, --native, --native-docker, or MOCHAT_K8S_IMAGE_MODE=jvm|native|native-docker."
      ;;
  esac
}

helm_upgrade() {
  local args=()
  warn_if_livekit_incomplete
  args+=("--set-string" "livekit.url=${MOCHAT_LIVEKIT_URL:-}")
  args+=("--set-string" "livekit.apiKey=${MOCHAT_LIVEKIT_API_KEY:-}")
  args+=("--set-string" "livekit.apiSecret=${MOCHAT_LIVEKIT_API_SECRET:-}")

  if [[ "$enable_prometheus_scrape" == "1" ]]; then
    args+=("--set" "observability.prometheus.scrape=true")
  fi
  args+=("--set-string" "apiService.image.tag=$image_tag")
  args+=("--set-string" "messageService.image.tag=$image_tag")
  args+=("--set-string" "persistenceService.image.tag=$image_tag")
  args+=("--set-string" "accessGateway.image.tag=$image_tag")
  args+=("--set-string" "callService.image.tag=$image_tag")

  helm upgrade --install "$release_name" "$repo_root/deploy/helm/mochat" \
    --namespace "$namespace" --create-namespace \
    -f "$repo_root/deploy/helm/mochat/values-dev.yaml" \
    -f "$repo_root/deploy/helm/mochat/values-local.yaml" \
    --set accessGateway.replicaCount=1 \
    "${args[@]}" \
    --set-file "accessGatewayTls.certificate=$tls_crt" \
    --set-file "accessGatewayTls.privateKey=$tls_key"
}

rollout_status() {
  kubectl -n "$namespace" rollout status deploy/api-service
  kubectl -n "$namespace" rollout status deploy/message-service
  kubectl -n "$namespace" rollout status deploy/persistence-service
  kubectl -n "$namespace" rollout status deploy/call-service
  kubectl -n "$namespace" rollout status statefulset/access-gateway
}

start_stack() {
  require_command helm
  require_command kubectl
  load_env_if_present

  local restart_after_upgrade=0
  if release_exists && current_images_match_desired; then
    restart_after_upgrade=1
  fi

  build_images_if_needed

  ensure_tls
  helm_upgrade
  if [[ "$restart_after_upgrade" == "1" ]]; then
    kubectl -n "$namespace" rollout restart deploy/api-service deploy/message-service deploy/persistence-service deploy/call-service statefulset/access-gateway
  fi

  if [[ "$deploy_observability" == "1" ]]; then
    kubectl apply -k "$repo_root/deploy/observability/kubernetes"
  else
    echo "Skipping observability stack because MOCHAT_K8S_OBSERVABILITY=0"
  fi

  rollout_status
  status_stack
}

stop_stack() {
  require_command helm
  require_command kubectl

  helm -n "$namespace" uninstall "$release_name" --ignore-not-found
  if [[ "$deploy_observability" == "1" ]]; then
    kubectl delete -k "$repo_root/deploy/observability/kubernetes" --ignore-not-found
  else
    echo "Skipping observability delete because MOCHAT_K8S_OBSERVABILITY=0"
  fi
}

status_stack() {
  require_command helm
  require_command kubectl

  echo "== Helm release =="
  helm -n "$namespace" status "$release_name" || true

  echo
  echo "== MoChat pods/services =="
  kubectl -n "$namespace" get pods,svc || true

  echo
  echo "== Observability pods/services =="
  kubectl -n "$observability_namespace" get pods,svc || true
}

pod_image_for() {
  local app_name="$1"
  kubectl -n "$namespace" get pod \
    -l "app.kubernetes.io/name=$app_name" \
    -o jsonpath="{.items[0].spec.containers[0].image}"
}

image_entrypoint_for() {
  local image="$1"
  docker image inspect "$image" --format "{{json .Config.Entrypoint}}"
}

verify_native_service() {
  local app_name="$1"
  local expected_entrypoint="$2"
  local image
  local entrypoint
  image="$(pod_image_for "$app_name")"
  entrypoint="$(image_entrypoint_for "$image")"
  echo "$app_name: image=$image entrypoint=$entrypoint"

  if [[ "$entrypoint" == *"/app/bin/"* || "$entrypoint" == *"java"* ]]; then
    echo "$app_name is still packaged as JVM" >&2
    return 1
  fi
  if [[ "$entrypoint" != *"$expected_entrypoint"* ]]; then
    echo "$app_name entrypoint is not the expected native binary: $expected_entrypoint" >&2
    return 1
  fi
  return 0
}

verify_native() {
  require_command kubectl
  require_command docker

  local failed=0
  verify_native_service api-service /app/api-service || failed=1
  verify_native_service message-service /app/message-service || failed=1
  verify_native_service access-gateway /app/access-gateway || failed=1

  local call_image
  local call_entrypoint
  call_image="$(pod_image_for call-service)"
  call_entrypoint="$(image_entrypoint_for "$call_image")"
  echo "call-service: image=$call_image entrypoint=$call_entrypoint"
  echo "call-service is expected to stay JVM in the current native build script."

  local persistence_image
  local persistence_entrypoint
  persistence_image="$(pod_image_for persistence-service)"
  persistence_entrypoint="$(image_entrypoint_for "$persistence_image")"
  echo "persistence-service: image=$persistence_image entrypoint=$persistence_entrypoint"
  echo "persistence-service is expected to stay JVM in the current native build script."

  return "$failed"
}

case "$command" in
  start)
    start_stack
    ;;
  stop)
    stop_stack
    ;;
  restart)
    stop_stack
    start_stack
    ;;
  status)
    status_stack
    ;;
  verify-native)
    verify_native
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
