package com.github.lystran.mochat.persistenceservice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceServiceDockerPackagingContractTest {
    @Test
    void packagesPersistenceServiceAsJvmDistribution() throws IOException {
        Path dockerfile = repositoryRoot()
            .resolve("persistence-service-app")
            .resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile), "missing persistence-service Dockerfile");

        String dockerfileText = Files.readString(dockerfile);
        assertTrue(dockerfileText.contains(":persistence-service-app:installDist"));
        assertTrue(dockerfileText.contains("ENTRYPOINT [\"/app/bin/persistence-service-app\"]"));
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
