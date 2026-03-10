package com.github.lystran.mochat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTopologyStructureTest {
    @Test
    void repositoryDefinesDedicatedServiceRuntimeAndAppModules() throws IOException {
        Path repoRoot = repoRoot();
        String settings = Files.readString(repoRoot.resolve("settings.gradle.kts"));

        assertAll(
            () -> assertTrue(settings.contains("\"service-runtime\""), "missing service-runtime module"),
            () -> assertTrue(settings.contains("\"access-gateway-app\""), "missing access-gateway-app module"),
            () -> assertTrue(settings.contains("\"api-service-app\""), "missing api-service-app module"),
            () -> assertTrue(settings.contains("\"message-service-app\""), "missing message-service-app module"),
            () -> assertTrue(settings.contains("\"persistence-service-app\""), "missing persistence-service-app module"),
            () -> assertTrue(Files.exists(repoRoot.resolve("service-runtime/build.gradle.kts")), "service-runtime build file missing"),
            () -> assertTrue(Files.exists(repoRoot.resolve("access-gateway-app/build.gradle.kts")), "access-gateway-app build file missing"),
            () -> assertTrue(Files.exists(repoRoot.resolve("api-service-app/build.gradle.kts")), "api-service-app build file missing"),
            () -> assertTrue(Files.exists(repoRoot.resolve("message-service-app/build.gradle.kts")), "message-service-app build file missing"),
            () -> assertTrue(Files.exists(repoRoot.resolve("persistence-service-app/build.gradle.kts")), "persistence-service-app build file missing")
        );
    }

    @Test
    void serviceSkeletonDefinesDedicatedConfigNamespacesAndBoundaryDocumentation() throws IOException {
        Path repoRoot = repoRoot();
        Path accessGatewayConfig = repoRoot.resolve("access-gateway-app/src/main/resources/application.yml");
        Path apiServiceConfig = repoRoot.resolve("api-service-app/src/main/resources/application.yml");
        Path messageServiceConfig = repoRoot.resolve("message-service-app/src/main/resources/application.yml");
        Path persistenceServiceConfig = repoRoot.resolve("persistence-service-app/src/main/resources/application.yml");
        Path ownershipDoc = repoRoot.resolve("docs/architecture/decompose-im-into-core-services-skeleton.md");

        assertAll(
            () -> assertFileContains(accessGatewayConfig, "mochat:"),
            () -> assertFileContains(accessGatewayConfig, "access-gateway:"),
            () -> assertFileContains(accessGatewayConfig, "grpc:"),
            () -> assertFileContains(accessGatewayConfig, "api-service:"),
            () -> assertFileContains(apiServiceConfig, "mochat:"),
            () -> assertFileContains(apiServiceConfig, "api-service:"),
            () -> assertFileContains(apiServiceConfig, "grpc:"),
            () -> assertFileContains(apiServiceConfig, "server:"),
            () -> assertFileContains(messageServiceConfig, "mochat:"),
            () -> assertFileContains(messageServiceConfig, "message-service:"),
            () -> assertFileContains(persistenceServiceConfig, "mochat:"),
            () -> assertFileContains(persistenceServiceConfig, "persistence-service:"),
            () -> assertFileContains(ownershipDoc, "access-gateway"),
            () -> assertFileContains(ownershipDoc, "api-service"),
            () -> assertFileContains(ownershipDoc, "message-service"),
            () -> assertFileContains(ownershipDoc, "persistence-service"),
            () -> assertFileContains(ownershipDoc, "shared types"),
            () -> assertFileContains(ownershipDoc, "internal-only types")
        );
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from test working directory");
    }

    private static void assertFileContains(Path path, String expectedText) throws IOException {
        assertTrue(Files.exists(path), "missing file: " + path);
        assertTrue(Files.readString(path).contains(expectedText), () -> "expected '" + expectedText + "' in " + path);
    }
}
