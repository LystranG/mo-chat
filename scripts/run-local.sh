#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${MOCHAT_ENV_FILE:-$repo_root/.env}"
log_dir="${MOCHAT_LOCAL_LOG_DIR:-$repo_root/.local/logs}"
pid_dir="${MOCHAT_LOCAL_PID_DIR:-$repo_root/.local/pids}"

services=(
  "api-service-app"
  "message-service-app"
  "persistence-service-app"
  "access-gateway-app"
  "call-service-app"
)

command="${1:-start}"

fail() {
  echo "$1" >&2
  exit 2
}

load_env() {
  if [[ ! -f "$env_file" ]]; then
    fail "Missing local env file: $env_file. Create it from .env.example and fill LiveKit values."
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" != *=* ]]; then
      fail "Invalid env line in $env_file: $line"
    fi
    local key
    local value
    key="${line%%=*}"
    value="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      fail "Invalid env key in $env_file: $key"
    fi
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done < "$env_file"
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    fail "$name is required in $env_file or the parent shell."
  fi
}

validate_env() {
  load_env
  require_env "MOCHAT_LIVEKIT_URL"
  require_env "MOCHAT_LIVEKIT_API_KEY"
  require_env "MOCHAT_LIVEKIT_API_SECRET"
}

gradle_command() {
  local service="$1"
  printf 'MICRONAUT_ENVIRONMENTS=local ./gradlew :%s:run' "$service"
}

process_marker_for() {
  local service="$1"
  local token="$2"
  printf 'mochat-local:%s:%s:%s' "$service" "$repo_root" "$token"
}

pid_file_for() {
  local service="$1"
  printf '%s/%s.pid' "$pid_dir" "$service"
}

meta_file_for() {
  local service="$1"
  printf '%s/%s.meta' "$pid_dir" "$service"
}

log_file_for() {
  local service="$1"
  printf '%s/%s.log' "$log_dir" "$service"
}

is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

is_valid_pid() {
  local pid="$1"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]]
}

command_for_pid() {
  local pid="$1"
  ps -p "$pid" -o command= 2>/dev/null || true
}

read_token_for() {
  local service="$1"
  local meta_file
  meta_file="$(meta_file_for "$service")"
  if [[ ! -f "$meta_file" ]]; then
    return 1
  fi
  local line
  line="$(grep -E '^token=' "$meta_file" 2>/dev/null | head -n 1 || true)"
  if [[ -z "$line" ]]; then
    return 1
  fi
  local token
  token="${line#token=}"
  [[ "$token" =~ ^[A-Za-z0-9_.:-]+$ ]] || return 1
  printf '%s' "$token"
}

generate_token() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    printf '%s-%s-%s' "$(date +%s%N)" "$$" "$RANDOM"
  fi
}

process_matches_service() {
  local pid="$1"
  local service="$2"
  local command_line
  local marker
  local token
  is_valid_pid "$pid" || return 1
  command_line="$(command_for_pid "$pid")"
  token="$(read_token_for "$service")" || return 1
  marker="$(process_marker_for "$service" "$token")"
  [[ "$command_line" == *"$marker :$service:run"* ]] || return 1
}

service_is_running() {
  local pid="$1"
  local service="$2"
  is_valid_pid "$pid" && is_running "$pid" && process_matches_service "$pid" "$service"
}

print_commands() {
  validate_env
  for service in "${services[@]}"; do
    gradle_command "$service"
    printf '\n'
  done
}

start_services() {
  validate_env
  start_services_without_validation
}

start_services_without_validation() {
  mkdir -p "$log_dir" "$pid_dir"
  for service in "${services[@]}"; do
    local pid_file
    local meta_file
    pid_file="$(pid_file_for "$service")"
    meta_file="$(meta_file_for "$service")"
    if [[ -f "$pid_file" ]]; then
      local existing_pid
      existing_pid="$(cat "$pid_file")"
      if service_is_running "$existing_pid" "$service"; then
        fail "$service is already running with pid $existing_pid"
      fi
      rm -f "$pid_file" "$meta_file"
    else
      rm -f "$meta_file"
    fi
  done

  local started_services=()
  for service in "${services[@]}"; do
    local log_file
    local pid_file
    local meta_file
    local pid
    local marker
    local token
    log_file="$(log_file_for "$service")"
    pid_file="$(pid_file_for "$service")"
    meta_file="$(meta_file_for "$service")"
    token="$(generate_token)"
    marker="$(process_marker_for "$service" "$token")"
    printf 'token=%s\n' "$token" >"$meta_file"
    (
      cd "$repo_root"
      export MICRONAUT_ENVIRONMENTS=local
      exec -a "$marker :$service:run" bash -c '
        child=0
        terminate() {
          if [[ "$child" != 0 ]]; then
            kill "$child" >/dev/null 2>&1 || true
            wait "$child" >/dev/null 2>&1 || true
          fi
          exit 143
        }
        trap terminate TERM INT
        ./gradlew ":$1:run" &
        child="$!"
        wait "$child"
      ' "$marker :$service:run" "$service"
    ) >"$log_file" 2>&1 &
    pid="$!"
    echo "$pid" >"$pid_file"
    sleep 1
    if ! service_is_running "$pid" "$service"; then
      rm -f "$pid_file" "$meta_file"
      echo "$service failed to stay running; see log=$log_file" >&2
      stop_started_services "${started_services[@]}"
      exit 1
    fi
    started_services+=("$service")
    echo "Started $service pid=$pid log=$log_file"
  done
}

stop_started_services() {
  local service
  local failed=0
  for service in "$@"; do
    stop_service "$service" >/dev/null || failed=1
  done
  return "$failed"
}

validate_then_stop_services() {
  validate_env
  stop_services
}

stop_service() {
  local service="$1"
  local pid_file
  local meta_file
  pid_file="$(pid_file_for "$service")"
  meta_file="$(meta_file_for "$service")"
  if [[ ! -f "$pid_file" ]]; then
    echo "$service is not running"
    return 0
  fi
  local pid
  pid="$(cat "$pid_file")"
  if ! is_valid_pid "$pid"; then
    echo "$service pid file was invalid: $pid"
    rm -f "$pid_file"
    rm -f "$meta_file"
    return 0
  fi
  if service_is_running "$pid" "$service"; then
    if ! kill "$pid" >/dev/null 2>&1; then
      if ! service_is_running "$pid" "$service"; then
        echo "$service pid file was stale: $pid"
        rm -f "$pid_file" "$meta_file"
        return 0
      fi
      echo "$service could not be signalled pid=$pid" >&2
      return 1
    fi
    for _ in {1..10}; do
      sleep 0.2
      if ! service_is_running "$pid" "$service"; then
        echo "Stopped $service pid=$pid"
        rm -f "$pid_file" "$meta_file"
        return 0
      fi
    done
    echo "$service did not stop after TERM pid=$pid" >&2
    return 1
  else
    echo "$service pid file was stale: $pid"
  fi
  rm -f "$pid_file" "$meta_file"
}

stop_services() {
  mkdir -p "$pid_dir"
  local failed=0
  for service in "${services[@]}"; do
    stop_service "$service" || failed=1
  done
  return "$failed"
}

validate_then_status_services() {
  validate_env
  status_services
}

status_services() {
  mkdir -p "$pid_dir"
  for service in "${services[@]}"; do
    local pid_file
    pid_file="$(pid_file_for "$service")"
    if [[ ! -f "$pid_file" ]]; then
      echo "$service stopped"
      continue
    fi
    local pid
    pid="$(cat "$pid_file")"
    if ! is_valid_pid "$pid"; then
      echo "$service invalid pid=$pid"
    elif service_is_running "$pid" "$service"; then
      echo "$service running pid=$pid"
    else
      echo "$service stale pid=$pid"
    fi
  done
}

case "$command" in
  print-commands)
    print_commands
    ;;
  start)
    start_services
    ;;
  stop)
    validate_then_stop_services
    ;;
  status)
    validate_then_status_services
    ;;
  restart)
    validate_env
    stop_services
    start_services_without_validation
    ;;
  *)
    fail "Usage: scripts/run-local.sh {start|stop|status|restart|print-commands}"
    ;;
esac
