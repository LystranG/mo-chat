package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalImageBuildScriptContractTest {
    private static final List<String> EXPECTED_IMAGE_NAMES = List.of(
        "access-gateway",
        "api-service",
        "message-service",
        "persistence-service",
        "call-service"
    );

    @Test
    void buildLocalImagesScriptBuildsAllDeployableServiceImages() throws IOException {
        Path scriptPath = repositoryRoot().resolve("scripts/build-local-images.sh");

        assertTrue(Files.isRegularFile(scriptPath), "scripts/build-local-images.sh should exist");
        assertTrue(Files.isExecutable(scriptPath), "scripts/build-local-images.sh should be executable");

        String script = Files.readString(scriptPath);

        assertTrue(script.startsWith("#!/usr/bin/env bash"), "script should be directly runnable");
        assertTrue(script.contains("set -euo pipefail"), "script should fail fast");
        assertTrue(script.contains("IMAGE_REGISTRY=\"${IMAGE_REGISTRY:-localhost}\""), "default registry should be localhost");
        assertTrue(script.contains("IMAGE_NAMESPACE=\"${IMAGE_NAMESPACE:-mochat}\""), "default namespace should be mochat");
        assertTrue(script.contains("IMAGE_TAG=\"${IMAGE_TAG:-dev}\""), "default tag should be dev");
        assertTrue(
            script.contains("LOCAL_IMAGE_MODE=\"${LOCAL_IMAGE_MODE:-native-container}\""),
            "default local image mode should build Linux native binaries in a container"
        );
        assertTrue(script.contains("docker buildx build"), "script should use Docker CLI buildx when available");
        assertTrue(script.contains("docker-buildx build"), "script should fall back to standalone docker-buildx");
        assertTrue(script.contains("--load"), "local builds should load images into the local Docker image store");
        assertTrue(script.contains("./gradlew --no-daemon"), "script should build artifacts on the host first");
        assertTrue(script.contains("ghcr.1ms.run/graalvm/native-image-community:25"), "native-container mode should use Linux GraalVM builder image");
        assertTrue(script.contains("-v \"$repo_root:/workspace\""), "native-container mode should mount the working tree");
        assertTrue(script.contains("--entrypoint /bin/bash"), "native-container mode should override the native-image entrypoint");
        assertTrue(script.contains("NATIVE_CONTAINER_MEMORY"), "native-container mode should allow tuning builder memory");
        assertTrue(script.contains("Native container build failed"), "script should print a clear native build failure hint");
        assertTrue(script.contains("native-container"), "script should expose native-container mode");
        assertTrue(
            script.contains(":access-gateway-app:installDist")
                && script.contains(":api-service-app:installDist")
                && script.contains(":message-service-app:installDist")
                && script.contains(":persistence-service-app:installDist")
                && script.contains(":call-service-app:installDist"),
            "jvm mode should build JVM install distributions on the host"
        );
        assertTrue(
            script.contains("build/docker/local-images"),
            "script should use generated packaging Dockerfiles instead of service Dockerfiles"
        );
        assertTrue(
            script.contains("build_context_for"),
            "script should resolve a packaging build context that is not filtered by repository .dockerignore"
        );
        assertTrue(script.contains("COPY --chown=65532:65532 . /app"), "JVM images should copy from the installDist directory context");
        assertTrue(script.contains("COPY --chown=65532:65532 . /app/"), "native images should copy from the native binary directory context");
        assertTrue(!script.contains(" -t \"$image_ref\" ."), "packaging builds should not use repository root as build context");
        assertTrue(script.contains("LOCAL_IMAGE_MODE=native-host"), "script should document native-host mode in errors");
        assertTrue(script.contains(":access-gateway-app:nativeCompile"), "native-host mode should compile access-gateway native binary");

        for (String imageName : EXPECTED_IMAGE_NAMES) {
            assertTrue(script.contains(imageName), "missing image name: " + imageName);
        }
    }

    @Test
    void buildLocalJvmImagesScriptForcesJvmModeWithoutNativeImage() throws IOException {
        Path scriptPath = repositoryRoot().resolve("scripts/build-local-jvm-images.sh");

        assertTrue(Files.isRegularFile(scriptPath), "scripts/build-local-jvm-images.sh should exist");
        assertTrue(Files.isExecutable(scriptPath), "scripts/build-local-jvm-images.sh should be executable");

        String script = Files.readString(scriptPath);

        assertTrue(script.startsWith("#!/usr/bin/env bash"), "script should be directly runnable");
        assertTrue(script.contains("set -euo pipefail"), "script should fail fast");
        assertTrue(script.contains("LOCAL_IMAGE_MODE=jvm"), "script should force JVM image mode");
        assertTrue(script.contains("exec \"$repo_root/scripts/build-local-images.sh\""), "script should delegate to the main image builder");
        assertTrue(!script.contains("nativeCompile"), "JVM wrapper should not mention nativeCompile");
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Could not locate repository root");
        return current;
    }
}
