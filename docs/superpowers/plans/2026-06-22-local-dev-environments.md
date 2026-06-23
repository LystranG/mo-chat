# Local and Dev Environments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `local` run the five MoChat services as direct Gradle processes and make `dev` the local k3s Helm environment.

**Architecture:** Use Micronaut `application-local.yml` files for direct process configuration, a root `.env.example` plus `scripts/run-local.sh` for local orchestration, and a Helm `values-dev.yaml` for k3s. Keep Kubernetes discovery in Helm-rendered runtime config, while local process mode uses `127.0.0.1` and static gateway routing.

**Tech Stack:** Java 25, Gradle, Micronaut 4.9, JUnit 5, SnakeYAML, Bash, Helm.

---

## Files And Responsibilities

- Create `api-service-app/src/main/resources/application-local.yml`: local profile overrides for api-service.
- Modify `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java`: verify local profile message-service address.
- Create `message-service-app/src/main/resources/application-local.yml`: local profile overrides for message-service.
- Modify `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceApplicationContextTest.java`: verify local api-service address and gateway static map.
- Create `access-gateway-app/src/main/resources/application-local.yml`: local profile overrides for access-gateway.
- Modify `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`: verify local upstreams, identity, and discovery.
- Create `persistence-service-app/src/main/resources/application-local.yml`: local profile overrides for persistence-service.
- Modify `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`: verify local external dependency addresses.
- Create `call-service-app/src/main/resources/application-local.yml`: local profile overrides for call-service.
- Create `call-service-app/src/test/java/com/github/lystran/mochat/callservice/CallServiceApplicationContextTest.java`: verify local external dependency addresses.
- Create `.env.example`: committed local environment template.
- Modify `.gitignore`: ignore root `.env`.
- Create `scripts/run-local.sh`: local process runner with `start`, `stop`, `status`, `restart`, and `print-commands`.
- Create `service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java`: test script behavior without starting long-lived services.
- Create `deploy/helm/mochat/values-dev.yaml`: canonical local k3s values.
- Modify `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`: render `values-dev.yaml` and assert dev labels/secrets/runtime config.
- Modify `README.md`: document local direct Gradle startup.
- Modify `docs/runbook.md`: split local process mode from dev k3s mode.
- Modify `docs/codebase/deployment/README.md`: record new environment boundaries and entrypoints.
- Modify service memory files touched by config changes:
  - `docs/codebase/api-service/README.md`
  - `docs/codebase/message-service/README.md`
  - `docs/codebase/access-gateway/README.md`
  - `docs/codebase/persistence-service/README.md`
  - `docs/codebase/call-service/README.md`

### Task 1: Add Failing Local Profile Configuration Tests

**Files:**
- Modify: `api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java`
- Modify: `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceApplicationContextTest.java`
- Modify: `access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java`
- Modify: `persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java`
- Create: `call-service-app/src/test/java/com/github/lystran/mochat/callservice/CallServiceApplicationContextTest.java`

- [ ] **Step 1: Add api-service local profile test**

Add this test method to `ApiServiceApplicationContextTest`:

```java
    @Test
    void localProfilePointsMessageServiceGrpcClientAtLoopback() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "mochat.api-service.dependencies.postgres-enabled", false
            ))
            .start()) {
            assertEquals(
                "127.0.0.1:19092",
                context.getRequiredProperty("grpc.channels.message-service.address", String.class)
            );
        }
    }
```

- [ ] **Step 2: Add message-service local profile test**

Add this test method to `MessageServiceApplicationContextTest`:

```java
    @Test
    void localProfileUsesLoopbackApiAndStaticGatewayTarget() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "mochat.message-service.dependencies.api-grpc-enabled", false,
                "mochat.message-service.dependencies.gateway-grpc-enabled", false,
                "mochat.message-service.dependencies.mq-enabled", false,
                "mochat.message-service.dependencies.redis-enabled", false
            ))
            .start()) {
            assertEquals(
                "127.0.0.1:19091",
                context.getRequiredProperty("grpc.channels.api-service.address", String.class)
            );
            assertEquals(
                "STATIC_MAP",
                context.getRequiredProperty("mochat.runtime.gateway.discovery-mode", String.class)
            );
            assertEquals(
                "127.0.0.1:19093",
                context.getRequiredProperty("mochat.message-service.route.gateway-targets.gateway-a", String.class)
            );
        }
    }
```

- [ ] **Step 3: Add access-gateway local profile test**

Add this test method to `AccessGatewayApplicationContextTest`:

```java
    @Test
    void localProfileUsesConfiguredGatewayIdentityAndLoopbackUpstreams() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "spec.name", "access-gateway-local-profile",
                "mochat.access-gateway.runtime.enabled", false,
                "mochat.access-gateway.tcp.enabled", false,
                "mochat.access-gateway.dependencies.api-grpc-enabled", false,
                "mochat.access-gateway.dependencies.redis-enabled", false
            ))
            .start()) {
            assertEquals(
                "127.0.0.1:19091",
                context.getRequiredProperty("grpc.channels.api-service.address", String.class)
            );
            assertEquals(
                "127.0.0.1:19092",
                context.getRequiredProperty("grpc.channels.message-service.address", String.class)
            );
            assertEquals(
                "CONFIGURED",
                context.getRequiredProperty("mochat.runtime.gateway.identity-mode", String.class)
            );
            assertEquals(
                "gateway-a",
                context.getRequiredProperty("mochat.runtime.gateway.identity-value", String.class)
            );
            assertEquals(
                "STATIC_MAP",
                context.getRequiredProperty("mochat.runtime.gateway.discovery-mode", String.class)
            );
            assertEquals(
                "gateway-a",
                context.getRequiredProperty("mochat.access-gateway.route.gateway-pod", String.class)
            );
        }
    }
```

- [ ] **Step 4: Add persistence-service local profile test**

Add this test method to `PersistenceServiceApplicationContextTest`:

```java
    @Test
    void localProfileUsesLoopbackInfrastructureAddresses() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "mochat.persistence-service.flyway.migrate-on-start", false,
                "mochat.persistence-service.queue.consumer-enabled", false,
                "mochat.persistence-service.dependencies.postgres-enabled", false,
                "mochat.persistence-service.dependencies.redis-enabled", false,
                "mochat.persistence-service.dependencies.mq-enabled", false
            ))
            .start()) {
            assertEquals("redis://127.0.0.1:6379", context.getRequiredProperty("mochat.redis.uri", String.class));
            assertEquals(
                "jdbc:postgresql://127.0.0.1:5432/mochat",
                context.getRequiredProperty("mochat.postgres.url", String.class)
            );
            assertEquals("127.0.0.1:9876", context.getRequiredProperty("mochat.rocketmq.name-server", String.class));
        }
    }
```

- [ ] **Step 5: Create call-service local profile test class**

Create `call-service-app/src/test/java/com/github/lystran/mochat/callservice/CallServiceApplicationContextTest.java`:

```java
package com.github.lystran.mochat.callservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallServiceApplicationContextTest {
    @Test
    void localProfileUsesLoopbackInfrastructureAddressesAndEnvironmentDrivenLivekit() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "mochat.call-service.flyway.migrate-on-start", false,
                "mochat.call-service.queue.consumer-enabled", false,
                "mochat.call-service.dependencies.postgres-enabled", false,
                "mochat.call-service.dependencies.redis-enabled", false,
                "mochat.call-service.dependencies.mq-enabled", false,
                "mochat.livekit.url", "ws://livekit.local",
                "mochat.livekit.api-key", "local-key",
                "mochat.livekit.api-secret", "local-secret"
            ))
            .start()) {
            assertEquals("redis://127.0.0.1:6379", context.getRequiredProperty("mochat.redis.uri", String.class));
            assertEquals(
                "jdbc:postgresql://127.0.0.1:5432/mochat",
                context.getRequiredProperty("mochat.postgres.url", String.class)
            );
            assertEquals("127.0.0.1:9876", context.getRequiredProperty("mochat.rocketmq.name-server", String.class));
            assertEquals("ws://livekit.local", context.getRequiredProperty("mochat.livekit.url", String.class));
        }
    }
}
```

- [ ] **Step 6: Run tests and verify they fail**

Run:

```bash
rtk ./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest.localProfilePointsMessageServiceGrpcClientAtLoopback
rtk ./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceApplicationContextTest.localProfileUsesLoopbackApiAndStaticGatewayTarget
rtk ./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest.localProfileUsesConfiguredGatewayIdentityAndLoopbackUpstreams
rtk ./gradlew :persistence-service-app:test --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest.localProfileUsesLoopbackInfrastructureAddresses
rtk ./gradlew :call-service-app:test --tests com.github.lystran.mochat.callservice.CallServiceApplicationContextTest.localProfileUsesLoopbackInfrastructureAddressesAndEnvironmentDrivenLivekit
```

Expected: at least the api, message, access-gateway, and persistence tests fail because `application-local.yml` files do not exist for those service apps. The call-service test may fail because the test class is new and the local profile file is missing.

- [ ] **Step 7: Commit failing tests**

```bash
rtk git add api-service-app/src/test/java/com/github/lystran/mochat/apiservice/ApiServiceApplicationContextTest.java message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceApplicationContextTest.java access-gateway-app/src/test/java/com/github/lystran/mochat/accessgateway/AccessGatewayApplicationContextTest.java persistence-service-app/src/test/java/com/github/lystran/mochat/persistenceservice/PersistenceServiceApplicationContextTest.java call-service-app/src/test/java/com/github/lystran/mochat/callservice/CallServiceApplicationContextTest.java
rtk git commit -m "test: 覆盖local环境配置"
```

### Task 2: Add Micronaut Local Profile Configuration Files

**Files:**
- Create: `api-service-app/src/main/resources/application-local.yml`
- Create: `message-service-app/src/main/resources/application-local.yml`
- Create: `access-gateway-app/src/main/resources/application-local.yml`
- Create: `persistence-service-app/src/main/resources/application-local.yml`
- Create: `call-service-app/src/main/resources/application-local.yml`

- [ ] **Step 1: Create api-service local profile**

Create `api-service-app/src/main/resources/application-local.yml`:

```yaml
grpc:
  channels:
    message-service:
      address: ${MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS:127.0.0.1:19092}
      plaintext: true
```

- [ ] **Step 2: Create message-service local profile**

Create `message-service-app/src/main/resources/application-local.yml`:

```yaml
mochat:
  runtime:
    gateway:
      identity-mode: ${MOCHAT_GATEWAY_IDENTITY_MODE:CONFIGURED}
      identity-value: ${MOCHAT_RUNTIME_GATEWAY_IDENTITY_VALUE:gateway-a}
      discovery-mode: ${MOCHAT_GATEWAY_DISCOVERY_MODE:STATIC_MAP}
  message-service:
    route:
      gateway-targets:
        gateway-a: ${MOCHAT_LOCAL_GATEWAY_A_GRPC_ADDRESS:127.0.0.1:19093}

grpc:
  channels:
    api-service:
      address: ${MOCHAT_API_SERVICE_GRPC_ADDRESS:127.0.0.1:19091}
      plaintext: true
```

- [ ] **Step 3: Create access-gateway local profile**

Create `access-gateway-app/src/main/resources/application-local.yml`:

```yaml
mochat:
  runtime:
    gateway:
      identity-mode: ${MOCHAT_GATEWAY_IDENTITY_MODE:CONFIGURED}
      identity-value: ${MOCHAT_RUNTIME_GATEWAY_IDENTITY_VALUE:gateway-a}
      discovery-mode: ${MOCHAT_GATEWAY_DISCOVERY_MODE:STATIC_MAP}
  access-gateway:
    route:
      gateway-pod: ${MOCHAT_ACCESS_GATEWAY_ROUTE_GATEWAY_POD:gateway-a}

grpc:
  channels:
    api-service:
      address: ${MOCHAT_API_SERVICE_GRPC_ADDRESS:127.0.0.1:19091}
      plaintext: true
    message-service:
      address: ${MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS:127.0.0.1:19092}
      plaintext: true
```

- [ ] **Step 4: Create persistence-service local profile**

Create `persistence-service-app/src/main/resources/application-local.yml`:

```yaml
mochat:
  redis:
    uri: ${MOCHAT_REDIS_URI:redis://127.0.0.1:6379}
  postgres:
    url: ${MOCHAT_POSTGRES_URL:`jdbc:postgresql://127.0.0.1:5432/mochat`}
    username: ${MOCHAT_POSTGRES_USERNAME:mochat}
    password: ${MOCHAT_POSTGRES_PASSWORD:mochat}
  rocketmq:
    name-server: ${MOCHAT_ROCKETMQ_NAME_SERVER:127.0.0.1:9876}
```

- [ ] **Step 5: Create call-service local profile**

Create `call-service-app/src/main/resources/application-local.yml`:

```yaml
mochat:
  redis:
    uri: ${MOCHAT_REDIS_URI:redis://127.0.0.1:6379}
  postgres:
    url: ${MOCHAT_POSTGRES_URL:`jdbc:postgresql://127.0.0.1:5432/mochat`}
    username: ${MOCHAT_POSTGRES_USERNAME:mochat}
    password: ${MOCHAT_POSTGRES_PASSWORD:mochat}
  rocketmq:
    name-server: ${MOCHAT_ROCKETMQ_NAME_SERVER:127.0.0.1:9876}
```

- [ ] **Step 6: Run local profile tests and verify they pass**

Run:

```bash
rtk ./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest.localProfilePointsMessageServiceGrpcClientAtLoopback
rtk ./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceApplicationContextTest.localProfileUsesLoopbackApiAndStaticGatewayTarget
rtk ./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest.localProfileUsesConfiguredGatewayIdentityAndLoopbackUpstreams
rtk ./gradlew :persistence-service-app:test --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest.localProfileUsesLoopbackInfrastructureAddresses
rtk ./gradlew :call-service-app:test --tests com.github.lystran.mochat.callservice.CallServiceApplicationContextTest.localProfileUsesLoopbackInfrastructureAddressesAndEnvironmentDrivenLivekit
```

Expected: all five commands pass.

- [ ] **Step 7: Commit local profile files**

```bash
rtk git add api-service-app/src/main/resources/application-local.yml message-service-app/src/main/resources/application-local.yml access-gateway-app/src/main/resources/application-local.yml persistence-service-app/src/main/resources/application-local.yml call-service-app/src/main/resources/application-local.yml
rtk git commit -m "feat: 增加local环境服务配置"
```

### Task 3: Add Local Environment Template And Script Contract Tests

**Files:**
- Create: `.env.example`
- Modify: `.gitignore`
- Create: `service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java`

- [ ] **Step 1: Create root `.env.example`**

Create `.env.example`:

```dotenv
MOCHAT_LIVEKIT_URL=
MOCHAT_LIVEKIT_API_KEY=
MOCHAT_LIVEKIT_API_SECRET=
```

- [ ] **Step 2: Ignore root `.env`**

Add this line under the `### Local Runtime Data ###` section of `.gitignore`:

```gitignore
.env
```

- [ ] **Step 3: Create script contract test**

Create `service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java`:

```java
package com.github.lystran.mochat.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRunScriptContractTest {
    @TempDir
    Path tempDir;

    @Test
    void missingEnvFileFailsBeforeStartingServices() throws Exception {
        ProcessResult result = runScript(tempDir.resolve("missing.env"), "print-commands");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("Missing local env file"));
        assertFalse(result.output().contains("./gradlew :api-service-app:run"));
    }

    @Test
    void blankLivekitValuesFailBeforeStartingServices() throws Exception {
        Path envFile = tempDir.resolve("blank.env");
        Files.writeString(envFile, """
            MOCHAT_LIVEKIT_URL=
            MOCHAT_LIVEKIT_API_KEY=
            MOCHAT_LIVEKIT_API_SECRET=
            """);

        ProcessResult result = runScript(envFile, "print-commands");

        assertEquals(2, result.exitCode());
        assertTrue(result.output().contains("MOCHAT_LIVEKIT_URL is required"));
        assertFalse(result.output().contains("./gradlew :api-service-app:run"));
    }

    @Test
    void printCommandsShowsFiveLocalGradleProcesses() throws Exception {
        Path envFile = tempDir.resolve("valid.env");
        Files.writeString(envFile, """
            MOCHAT_LIVEKIT_URL=ws://livekit.local
            MOCHAT_LIVEKIT_API_KEY=local-key
            MOCHAT_LIVEKIT_API_SECRET=local-secret
            """);

        ProcessResult result = runScript(envFile, "print-commands");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("MICRONAUT_ENVIRONMENTS=local ./gradlew :api-service-app:run"));
        assertTrue(result.output().contains("MICRONAUT_ENVIRONMENTS=local ./gradlew :message-service-app:run"));
        assertTrue(result.output().contains("MICRONAUT_ENVIRONMENTS=local ./gradlew :persistence-service-app:run"));
        assertTrue(result.output().contains("MICRONAUT_ENVIRONMENTS=local ./gradlew :access-gateway-app:run"));
        assertTrue(result.output().contains("MICRONAUT_ENVIRONMENTS=local ./gradlew :call-service-app:run"));
    }

    private ProcessResult runScript(Path envFile, String command) throws Exception {
        Path root = repositoryRoot();
        ProcessBuilder builder = new ProcessBuilder("bash", "scripts/run-local.sh", command)
            .directory(root.toFile())
            .redirectErrorStream(true);
        builder.environment().put("MOCHAT_ENV_FILE", envFile.toString());
        builder.environment().remove("MOCHAT_LIVEKIT_URL");
        builder.environment().remove("MOCHAT_LIVEKIT_API_KEY");
        builder.environment().remove("MOCHAT_LIVEKIT_API_SECRET");
        Process process = builder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("scripts/run-local.sh timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("Could not locate repository root");
        }
        return current;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
```

- [ ] **Step 4: Run script contract tests and verify they fail because script is missing**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.local.LocalRunScriptContractTest
```

Expected: FAIL with output indicating `scripts/run-local.sh` cannot be found.

- [ ] **Step 5: Commit template and failing script tests**

```bash
rtk git add .env.example .gitignore service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java
rtk git commit -m "test: 约束local启动脚本"
```

### Task 4: Implement Local Run Script

**Files:**
- Create: `scripts/run-local.sh`
- Modify: `service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java`

- [ ] **Step 1: Create executable local runner**

Create `scripts/run-local.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${MOCHAT_ENV_FILE:-$repo_root/.env}"
log_dir="$repo_root/.local/logs"
pid_dir="$repo_root/.local/pids"

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

pid_file_for() {
  local service="$1"
  printf '%s/%s.pid' "$pid_dir" "$service"
}

log_file_for() {
  local service="$1"
  printf '%s/%s.log' "$log_dir" "$service"
}

is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
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
  mkdir -p "$log_dir" "$pid_dir"
  for service in "${services[@]}"; do
    local pid_file
    pid_file="$(pid_file_for "$service")"
    if [[ -f "$pid_file" ]]; then
      local existing_pid
      existing_pid="$(cat "$pid_file")"
      if is_running "$existing_pid"; then
        fail "$service is already running with pid $existing_pid"
      fi
      rm -f "$pid_file"
    fi
  done

  for service in "${services[@]}"; do
    local log_file pid_file
    log_file="$(log_file_for "$service")"
    pid_file="$(pid_file_for "$service")"
    (
      cd "$repo_root"
      MICRONAUT_ENVIRONMENTS=local ./gradlew ":$service:run"
    ) >"$log_file" 2>&1 &
    echo "$!" >"$pid_file"
    echo "Started $service pid=$(cat "$pid_file") log=$log_file"
  done
}

stop_services() {
  mkdir -p "$pid_dir"
  for service in "${services[@]}"; do
    local pid_file
    pid_file="$(pid_file_for "$service")"
    if [[ ! -f "$pid_file" ]]; then
      echo "$service is not running"
      continue
    fi
    local pid
    pid="$(cat "$pid_file")"
    if is_running "$pid"; then
      kill "$pid"
      echo "Stopped $service pid=$pid"
    else
      echo "$service pid file was stale: $pid"
    fi
    rm -f "$pid_file"
  done
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
    if is_running "$pid"; then
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
    stop_services
    ;;
  status)
    status_services
    ;;
  restart)
    stop_services
    start_services
    ;;
  *)
    fail "Usage: scripts/run-local.sh {start|stop|status|restart|print-commands}"
    ;;
esac
```

- [ ] **Step 2: Make script executable**

Run:

```bash
rtk chmod +x scripts/run-local.sh
```

Expected: command exits 0.

- [ ] **Step 3: Run script contract tests and verify they pass**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.local.LocalRunScriptContractTest
```

Expected: PASS.

- [ ] **Step 4: Manually verify print-commands with a temporary env file**

Run:

```bash
rtk env MOCHAT_ENV_FILE=/tmp/mochat-local.env sh -c 'printf "%s\n" "MOCHAT_LIVEKIT_URL=ws://livekit.local" "MOCHAT_LIVEKIT_API_KEY=local-key" "MOCHAT_LIVEKIT_API_SECRET=local-secret" > /tmp/mochat-local.env && scripts/run-local.sh print-commands'
```

Expected output includes:

```text
MICRONAUT_ENVIRONMENTS=local ./gradlew :api-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :message-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :persistence-service-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :access-gateway-app:run
MICRONAUT_ENVIRONMENTS=local ./gradlew :call-service-app:run
```

- [ ] **Step 5: Commit local runner**

```bash
rtk git add scripts/run-local.sh service-runtime/src/test/java/com/github/lystran/mochat/runtime/local/LocalRunScriptContractTest.java
rtk git commit -m "feat: 增加local一键启动脚本"
```

### Task 5: Add Dev Helm Values And Helm Contract Tests

**Files:**
- Create: `deploy/helm/mochat/values-dev.yaml`
- Modify: `service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java`

- [ ] **Step 1: Add failing dev values rendering test**

Add this test method to `HelmMoChatChartContractTest`:

```java
    @Test
    void devValuesRenderK3sDevelopmentEnvironmentLabelsAndRuntimeConfig() throws Exception {
        List<Map<String, Object>> manifests = renderChartWithValues("values-dev.yaml");

        Map<String, Object> apiDeployment = manifest(manifests, "Deployment", "api-service");
        Map<String, Object> labels = nestedMap(apiDeployment, "spec", "template", "metadata", "labels");
        assertEquals("mochat-dev", labels.get("mochat.lystran.io/project-id"));
        assertEquals("dev", labels.get("mochat.lystran.io/environment"));

        Map<String, Object> runtimeConfig = manifest(manifests, "ConfigMap", "mochat-runtime-config");
        Map<String, Object> runtimeData = nestedMap(runtimeConfig, "data");
        assertEquals("api-service:19091", runtimeData.get("MOCHAT_API_SERVICE_GRPC_ADDRESS"));
        assertEquals("message-service:19092", runtimeData.get("MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS"));
        assertEquals("access-gateway-headless", runtimeData.get("MOCHAT_GATEWAY_HEADLESS_SERVICE"));

        Map<String, Object> gateway = manifest(manifests, "StatefulSet", "access-gateway");
        Map<String, Object> gatewayContainer = firstContainer(gateway);
        List<Map<String, Object>> env = nestedList(gatewayContainer, "env");
        assertTrue(env.stream().anyMatch(entry -> Objects.equals("MOCHAT_RUNTIME_POD_NAME", entry.get("name"))));
        assertTrue(env.stream().anyMatch(entry -> Objects.equals("MOCHAT_RUNTIME_POD_NAMESPACE", entry.get("name"))));

        Map<String, Object> callServiceContainer = firstContainer(manifest(manifests, "Deployment", "call-service"));
        assertEnvFromSecret(callServiceContainer, "mochat-livekit");
    }
```

- [ ] **Step 2: Add helper method for explicit values file**

Add this helper method below `renderChart` in `HelmMoChatChartContractTest`:

```java
    private List<Map<String, Object>> renderChartWithValues(String valuesFileName, String... extraArgs) throws Exception {
        Path root = repositoryRoot();
        List<String> command = new ArrayList<>(List.of(
            "helm",
            "template",
            "mochat",
            root.resolve("deploy/helm/mochat").toString(),
            "-f",
            root.resolve("deploy/helm/mochat").resolve(valuesFileName).toString()
        ));
        command.addAll(List.of(extraArgs));
        Process process = new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start();
        ProcessOutput processOutput = ProcessOutput.capture(process);

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            processOutput.awaitReader();
            fail("helm template timed out:\n" + processOutput.text());
        }

        processOutput.awaitReader();
        String output = processOutput.text();
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
        assertTrue(!result.isEmpty(), () -> "helm template rendered no yaml documents:\n" + output);
        return result;
    }
```

- [ ] **Step 3: Run Helm contract test and verify it fails**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest.devValuesRenderK3sDevelopmentEnvironmentLabelsAndRuntimeConfig
```

Expected: FAIL because `deploy/helm/mochat/values-dev.yaml` does not exist.

- [ ] **Step 4: Create dev values file**

Create `deploy/helm/mochat/values-dev.yaml`:

```yaml
global:
  namespace: mochat
  createNamespace: false
  imageRegistry: localhost/mochat
  imagePullPolicy: IfNotPresent
  projectId: mochat-dev
  environment: dev
  clusterDomain: cluster.local
  serviceAccountName: mochat
  commonLabels: {}

externalDependencies:
  redisUri: redis://host.k3d.internal:6379
  postgresUrl: jdbc:postgresql://host.k3d.internal:5432/mochat
  rocketmqNameServer: host.k3d.internal:9876
  rocketmqTopic: mochat.messages
  secret:
    create: true
    name: mochat-external-dependency-secrets
    postgresUsername: mochat
    postgresPassword: mochat

observability:
  prometheus:
    scrape: false
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

- [ ] **Step 5: Run Helm contract tests and verify they pass**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected: PASS.

- [ ] **Step 6: Commit dev Helm values**

```bash
rtk git add deploy/helm/mochat/values-dev.yaml service-runtime/src/test/java/com/github/lystran/mochat/runtime/kubernetes/HelmMoChatChartContractTest.java
rtk git commit -m "feat: 增加dev环境Helm配置"
```

### Task 6: Update Documentation And Codebase Memory

**Files:**
- Modify: `README.md`
- Modify: `docs/runbook.md`
- Modify: `docs/codebase/deployment/README.md`
- Modify: `docs/codebase/api-service/README.md`
- Modify: `docs/codebase/message-service/README.md`
- Modify: `docs/codebase/access-gateway/README.md`
- Modify: `docs/codebase/persistence-service/README.md`
- Modify: `docs/codebase/call-service/README.md`

- [ ] **Step 1: Update README local quick start**

In `README.md`, replace the current Quick Start service startup section with:

```markdown
## Quick Start

### local: 本机直接运行五个 Gradle 进程

1. 启动共享基础设施：

```bash
docker compose up -d
```

2. 准备本地环境变量：

```bash
cp .env.example .env
```

编辑 `.env`，填写 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。

3. 启动五个 dedicated services：

```bash
scripts/run-local.sh start
```

4. 查看状态和日志：

```bash
scripts/run-local.sh status
tail -f .local/logs/api-service-app.log
```

5. 停止本地服务：

```bash
scripts/run-local.sh stop
```

### dev: 本地 k3s Helm 部署

`dev` 环境使用 `deploy/helm/mochat/values-dev.yaml` 部署到本地 k3s。Pod 必须能访问 PostgreSQL、Redis、RocketMQ 和 LiveKit，真实凭据通过 Helm values、`--set-file` 或预建 Secret 注入。详细步骤见 [docs/runbook.md](docs/runbook.md)。
```
```

- [ ] **Step 2: Update runbook environment split**

In `docs/runbook.md`, ensure the top section starts with this text:

```markdown
# MoChat local / dev 部署操作手册

这份 runbook 区分两个开发环境：

- `local`：本机直接运行五个 Gradle 进程，使用根目录 Docker Compose 提供 PostgreSQL、Redis、RocketMQ。
- `dev`：部署到本地 k3s，使用 Helm chart `deploy/helm/mochat` 和 `deploy/helm/mochat/values-dev.yaml`。

`prod` 环境只预留命名，尚未在仓库中交付。
```

Also replace Helm commands that use `deploy/helm/mochat/values-local.yaml` with `deploy/helm/mochat/values-dev.yaml`.

- [ ] **Step 3: Update deployment memory**

In `docs/codebase/deployment/README.md`, add this section under "职责":

```markdown
## 环境语义

- `local`：直接运行五个 Gradle 进程；推荐入口是 `scripts/run-local.sh`；服务配置来自各 app 的 `application-local.yml` 和根目录 `.env`。
- `dev`：本地 k3s Helm 部署；推荐 values 是 `deploy/helm/mochat/values-dev.yaml`；服务发现使用 Kubernetes Service/headless Service。
- `prod`：当前只预留命名，尚未交付生产 values 或 overlay。
```

- [ ] **Step 4: Update service memory configuration entries**

Update each service memory file to mention its new local profile:

```markdown
- 本机 `local` profile：`application-local.yml`，通过 `MICRONAUT_ENVIRONMENTS=local` 激活。
```

For `api-service`, also state:

```markdown
- `local` 下 `MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS` 默认 `127.0.0.1:19092`。
```

For `message-service`, also state:

```markdown
- `local` 下 `MOCHAT_API_SERVICE_GRPC_ADDRESS` 默认 `127.0.0.1:19091`，`gateway-a` 默认 `127.0.0.1:19093`。
```

For `access-gateway`, also state:

```markdown
- `local` 下 gateway identity 默认 `gateway-a`，上游 api/message gRPC 默认使用 `127.0.0.1`。
```

For `persistence-service` and `call-service`, also state:

```markdown
- `local` 下 PostgreSQL、Redis、RocketMQ 默认使用本机 Docker Compose 暴露的 `127.0.0.1` 地址。
```

For `call-service`, additionally state:

```markdown
- `local` 启动脚本要求根目录 `.env` 提供 `MOCHAT_LIVEKIT_URL`、`MOCHAT_LIVEKIT_API_KEY`、`MOCHAT_LIVEKIT_API_SECRET`。
```

- [ ] **Step 5: Run documentation-sensitive tests**

Run:

```bash
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.KubernetesKindOverlayAssetsTest
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected: both commands pass after runbook and Helm wording are aligned.

- [ ] **Step 6: Commit documentation updates**

```bash
rtk git add README.md docs/runbook.md docs/codebase/deployment/README.md docs/codebase/api-service/README.md docs/codebase/message-service/README.md docs/codebase/access-gateway/README.md docs/codebase/persistence-service/README.md docs/codebase/call-service/README.md
rtk git commit -m "docs: 说明local和dev环境入口"
```

### Task 7: Full Verification

**Files:**
- No file changes expected.

- [ ] **Step 1: Run focused module tests**

Run:

```bash
rtk ./gradlew :api-service-app:test --tests com.github.lystran.mochat.apiservice.ApiServiceApplicationContextTest
rtk ./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceApplicationContextTest
rtk ./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayApplicationContextTest
rtk ./gradlew :persistence-service-app:test --tests com.github.lystran.mochat.persistenceservice.PersistenceServiceApplicationContextTest
rtk ./gradlew :call-service-app:test --tests com.github.lystran.mochat.callservice.CallServiceApplicationContextTest
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.local.LocalRunScriptContractTest
rtk ./gradlew :service-runtime:test --tests com.github.lystran.mochat.runtime.kubernetes.HelmMoChatChartContractTest
```

Expected: all commands pass.

- [ ] **Step 2: Run local script dry-run**

Run:

```bash
rtk env MOCHAT_ENV_FILE=/tmp/mochat-local.env sh -c 'printf "%s\n" "MOCHAT_LIVEKIT_URL=ws://livekit.local" "MOCHAT_LIVEKIT_API_KEY=local-key" "MOCHAT_LIVEKIT_API_SECRET=local-secret" > /tmp/mochat-local.env && scripts/run-local.sh print-commands'
```

Expected output includes all five Gradle run commands with `MICRONAUT_ENVIRONMENTS=local`.

- [ ] **Step 3: Render dev Helm chart**

Run:

```bash
rtk helm template mochat deploy/helm/mochat -f deploy/helm/mochat/values-dev.yaml
```

Expected: command exits 0 and rendered manifests include `Deployment/api-service`, `Deployment/message-service`, `Deployment/persistence-service`, `StatefulSet/access-gateway`, and `Deployment/call-service`.

- [ ] **Step 4: Check git status**

Run:

```bash
rtk git status --short
```

Expected: only unrelated pre-existing user changes remain. Files changed by this implementation should be committed.

## Plan Self-Review

- Spec coverage: local Gradle process mode is covered by Tasks 1-4; dev k3s Helm mode is covered by Task 5; documentation and codebase memory updates are covered by Task 6; final verification is covered by Task 7.
- 未完成标记扫描：the plan contains no incomplete markers or empty implementation instructions.
- Type consistency: property names match the existing Micronaut keys in current `application.yml` files; script command names are consistent across tests and implementation.
