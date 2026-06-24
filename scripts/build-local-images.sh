#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

IMAGE_REGISTRY="${IMAGE_REGISTRY:-localhost}"
IMAGE_NAMESPACE="${IMAGE_NAMESPACE:-mochat}"
IMAGE_TAG="${IMAGE_TAG:-dev}"
DOCKER_BUILDER="${DOCKER_BUILDER:-docker}"
LOCAL_IMAGE_MODE="${LOCAL_IMAGE_MODE:-native-container}"
NATIVE_BUILDER_IMAGE="${NATIVE_BUILDER_IMAGE:-ghcr.1ms.run/graalvm/native-image-community:25}"
NATIVE_CONTAINER_MEMORY="${NATIVE_CONTAINER_MEMORY:-8g}"
docker_build_dir="$repo_root/build/docker/local-images"

images=(
  "access-gateway:access-gateway-app:9000 19093 18080"
  "api-service:api-service-app:8080 19091"
  "message-service:message-service-app:19092"
  "persistence-service:persistence-service-app:"
  "call-service:call-service-app:8090"
)

image_ref_for() {
  local image_name="$1"
  printf '%s/%s/%s:%s' "$IMAGE_REGISTRY" "$IMAGE_NAMESPACE" "$image_name" "$IMAGE_TAG"
}

run_buildx() {
  if [[ "$DOCKER_BUILDER" == "docker-buildx" ]]; then
    docker-buildx build "$@"
    return
  fi

  if [[ "$DOCKER_BUILDER" != "docker" ]]; then
    "$DOCKER_BUILDER" buildx build "$@"
    return
  fi

  if docker buildx version >/dev/null 2>&1; then
    docker buildx build "$@"
    return
  fi

  if command -v docker-buildx >/dev/null 2>&1; then
    docker-buildx build "$@"
    return
  fi

  echo "Neither 'docker buildx' nor 'docker-buildx' is available." >&2
  exit 2
}

run_docker() {
  if [[ "$DOCKER_BUILDER" == "docker-buildx" || "$DOCKER_BUILDER" == "docker" ]]; then
    docker "$@"
    return
  fi

  "$DOCKER_BUILDER" "$@"
}

build_host_artifacts() {
  if [[ "$LOCAL_IMAGE_MODE" == "native-container" ]]; then
    if ! run_docker run --rm \
      --memory "$NATIVE_CONTAINER_MEMORY" \
      -v "$repo_root:/workspace" \
      -w /workspace \
      -e GRADLE_USER_HOME=/workspace/.gradle-cache \
      -e GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx1g" \
      --entrypoint /bin/bash \
      "$NATIVE_BUILDER_IMAGE" \
      -lc 'chmod +x ./gradlew && ./gradlew --no-daemon --stacktrace :access-gateway-app:nativeCompile :api-service-app:nativeCompile :message-service-app:nativeCompile :call-service-app:nativeCompile :persistence-service-app:installDist'; then
      echo "Native container build failed." >&2
      echo "Check the full output above and Gradle logs under .gradle-cache/daemon/." >&2
      echo "If native-image was killed or the daemon disappeared, increase Colima memory or set NATIVE_CONTAINER_MEMORY=12g." >&2
      exit 1
    fi
    return
  fi

  if [[ "$LOCAL_IMAGE_MODE" == "jvm" ]]; then
    ./gradlew --no-daemon \
      :access-gateway-app:installDist \
      :api-service-app:installDist \
      :message-service-app:installDist \
      :persistence-service-app:installDist \
      :call-service-app:installDist
    return
  fi

  if [[ "$LOCAL_IMAGE_MODE" == "native-host" ]]; then
    require_linux_native_host
    ./gradlew --no-daemon \
      :access-gateway-app:nativeCompile \
      :api-service-app:nativeCompile \
      :message-service-app:nativeCompile \
      :call-service-app:nativeCompile \
      :persistence-service-app:installDist
    return
  fi

  echo "Unsupported LOCAL_IMAGE_MODE=$LOCAL_IMAGE_MODE. Use LOCAL_IMAGE_MODE=native-container, LOCAL_IMAGE_MODE=jvm, or LOCAL_IMAGE_MODE=native-host." >&2
  exit 2
}

require_linux_native_host() {
  local host_os
  host_os="$(uname -s)"
  if [[ "$host_os" == "Darwin" ]]; then
    echo "LOCAL_IMAGE_MODE=native-host cannot package macOS native binaries into Linux container images." >&2
    echo "Use the default LOCAL_IMAGE_MODE=jvm on macOS/Colima." >&2
    exit 2
  fi
  if [[ "$host_os" != "Linux" ]]; then
    echo "LOCAL_IMAGE_MODE=native-host requires a Linux host. Current host OS: $host_os" >&2
    exit 2
  fi
}

native_binary_for() {
  local app_dir="$1"
  local image_name="$2"
  printf '%s/build/native/nativeCompile/%s' "$app_dir" "$image_name"
}

build_context_for() {
  local app_dir="$1"
  local image_name="$2"

  if [[ "$LOCAL_IMAGE_MODE" != "jvm" && "$app_dir" != "persistence-service-app" ]]; then
    dirname "$(native_binary_for "$app_dir" "$image_name")"
  else
    printf '%s/build/install/%s' "$app_dir" "$app_dir"
  fi
}

write_native_dockerfile() {
  local dockerfile="$1"
  local image_name="$2"
  local ports="$3"

  {
    printf 'FROM gcr.1ms.run/distroless/cc\n\n'
    printf 'WORKDIR /app\n\n'
    printf 'COPY --chown=65532:65532 . /app/%s\n\n' "$image_name"
    printf 'USER 65532:65532\n\n'
    if [[ -n "$ports" ]]; then
      printf 'EXPOSE %s\n\n' "$ports"
    fi
    printf 'ENTRYPOINT ["/app/%s"]\n' "$image_name"
  } >"$dockerfile"
}

write_jvm_dockerfile() {
  local dockerfile="$1"
  local app_dir="$2"
  local ports="$3"

  {
    printf 'FROM docker.io/eclipse-temurin:25-jre\n\n'
    printf 'WORKDIR /app\n\n'
    printf 'COPY --chown=65532:65532 . /app\n\n'
    printf 'USER 65532:65532\n\n'
    if [[ -n "$ports" ]]; then
      printf 'EXPOSE %s\n\n' "$ports"
    fi
    printf 'ENTRYPOINT ["/app/bin/%s"]\n' "$app_dir"
  } >"$dockerfile"
}

write_packaging_dockerfile() {
  local image_name="$1"
  local app_dir="$2"
  local ports="$3"
  local dockerfile="$docker_build_dir/$image_name.Dockerfile"

  mkdir -p "$docker_build_dir"
  if [[ "$LOCAL_IMAGE_MODE" != "jvm" && "$app_dir" != "persistence-service-app" ]]; then
    write_native_dockerfile "$dockerfile" "$image_name" "$ports"
  else
    write_jvm_dockerfile "$dockerfile" "$app_dir" "$ports"
  fi
  printf '%s' "$dockerfile"
}

main() {
  cd "$repo_root"
  build_host_artifacts

  for image in "${images[@]}"; do
    local image_name
    local app_dir
    local ports
    local dockerfile
    local build_context
    local image_ref
    image_name="${image%%:*}"
    local remaining="${image#*:}"
    app_dir="${remaining%%:*}"
    ports="${remaining#*:}"
    dockerfile="$(write_packaging_dockerfile "$image_name" "$app_dir" "$ports")"
    build_context="$(build_context_for "$app_dir" "$image_name")"
    image_ref="$(image_ref_for "$image_name")"

    echo "Building $image_ref from $dockerfile context=$build_context"
    run_buildx --load -f "$dockerfile" -t "$image_ref" "$build_context"
  done
}

main "$@"
