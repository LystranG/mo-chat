package com.github.lystran.mochat.runtime.local;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProfileConfigurationContractTest {
    private static final List<String> LOCAL_PROFILE_FILES = List.of(
        "api-service-app/src/main/resources/application-local.yml",
        "message-service-app/src/main/resources/application-local.yml",
        "persistence-service-app/src/main/resources/application-local.yml",
        "access-gateway-app/src/main/resources/application-local.yml",
        "call-service-app/src/main/resources/application-local.yml"
    );

    @Test
    void localProfilesUseLiteralLoopbackDefaultsForIdeaRunConfigurations() throws IOException {
        for (String relativePath : LOCAL_PROFILE_FILES) {
            Path profile = repositoryRoot().resolve(relativePath);
            String content = Files.readString(profile);

            assertFalse(content.contains("${MOCHAT_REDIS_URI"), relativePath);
            assertFalse(content.contains("${MOCHAT_POSTGRES_URL"), relativePath);
            assertFalse(content.contains("${MOCHAT_ROCKETMQ_NAME_SERVER"), relativePath);
            assertFalse(content.contains("${MOCHAT_API_SERVICE_GRPC_ADDRESS"), relativePath);
            assertFalse(content.contains("${MOCHAT_MESSAGE_SERVICE_GRPC_ADDRESS"), relativePath);
            assertFalse(content.contains("${MOCHAT_LOCAL_GATEWAY_A_GRPC_ADDRESS"), relativePath);
            assertFalse(content.contains("${MOCHAT_LIVEKIT_URL"), relativePath);
            assertFalse(content.contains("${MOCHAT_LIVEKIT_API_KEY"), relativePath);
            assertFalse(content.contains("${MOCHAT_LIVEKIT_API_SECRET"), relativePath);
        }
    }

    @Test
    void localProfilesStillPointAtLoopbackInfrastructure() throws IOException {
        String allProfiles = "";
        for (String relativePath : LOCAL_PROFILE_FILES) {
            allProfiles += Files.readString(repositoryRoot().resolve(relativePath)) + "\n";
        }

        assertTrue(allProfiles.contains("redis://127.0.0.1:6379"));
        assertTrue(allProfiles.contains("jdbc:postgresql://127.0.0.1:5432/mochat"));
        assertTrue(allProfiles.contains("127.0.0.1:9876"));
        assertTrue(allProfiles.contains("127.0.0.1:19091"));
        assertTrue(allProfiles.contains("127.0.0.1:19092"));
        assertTrue(allProfiles.contains("127.0.0.1:19093"));
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
