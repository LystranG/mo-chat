package com.github.lystran.mochat.infra.redis;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestcontainersRuntimeAvailabilityTest {
    @Test
    void detectsLocalContainerRuntimeWhenSocketIsPresent() {
        Optional<Path> podmanSocket = podmanSocket();
        boolean dockerSocketExists = Files.exists(Path.of("/var/run/docker.sock"));
        boolean podmanSocketExists = podmanSocket.map(Files::exists).orElse(false);

        Assumptions.assumeTrue(
            dockerSocketExists || podmanSocketExists,
            "No local Docker or Podman socket is available on this machine"
        );

        assertTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            () -> "Testcontainers could not detect a local container runtime. DOCKER_HOST="
                + System.getenv("DOCKER_HOST")
                + ", podmanSocket="
                + podmanSocket.map(Path::toString).orElse("<missing>")
        );
    }

    private static Optional<Path> podmanSocket() {
        String xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (xdgRuntimeDir == null || xdgRuntimeDir.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(xdgRuntimeDir, "podman", "podman.sock"));
    }
}
