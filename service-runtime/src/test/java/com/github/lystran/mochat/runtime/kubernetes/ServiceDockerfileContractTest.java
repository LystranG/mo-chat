package com.github.lystran.mochat.runtime.kubernetes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDockerfileContractTest {
    private static final List<String> SERVICE_DOCKERFILES = List.of(
        "access-gateway-app/Dockerfile",
        "api-service-app/Dockerfile",
        "message-service-app/Dockerfile",
        "persistence-service-app/Dockerfile",
        "call-service-app/Dockerfile"
    );

    @Test
    void serviceDockerfilesCacheGradleDownloadsAcrossBuilds() throws IOException {
        for (String relativePath : SERVICE_DOCKERFILES) {
            String dockerfile = Files.readString(repositoryRoot().resolve(relativePath));

            assertTrue(dockerfile.startsWith("# syntax=docker/dockerfile:"), relativePath);
            assertTrue(dockerfile.contains("ENV GRADLE_USER_HOME=/workspace/.gradle-cache"), relativePath);
            assertTrue(dockerfile.contains("RUN --mount=type=cache,target=/workspace/.gradle-cache"), relativePath);
            assertTrue(dockerfile.contains("./gradlew --no-daemon"), relativePath);
        }
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
