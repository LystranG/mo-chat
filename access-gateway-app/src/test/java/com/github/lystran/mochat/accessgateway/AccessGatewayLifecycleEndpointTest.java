package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.accessgateway.runtime.GatewayDrainManager;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayLifecycleEndpointTest {
    @Test
    void readinessTurnsUnreadyAfterDrainEndpointIsTriggered() {
        try (
            EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, settings());
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())
        ) {
            assertEquals(
                HttpStatus.OK,
                client.toBlocking().exchange(HttpRequest.GET("/internal/lifecycle/readyz"), String.class).getStatus()
            );

            assertEquals(
                HttpStatus.OK,
                client.toBlocking().exchange(HttpRequest.GET("/internal/lifecycle/drain"), String.class).getStatus()
            );

            HttpClientResponseException exception = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/internal/lifecycle/readyz"), String.class)
            );
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
            assertTrue(server.getApplicationContext().getBean(GatewayDrainManager.class).isDraining());
        }
    }

    @Test
    void livenessRemainsAvailableDuringDrain() {
        try (
            EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, settings());
            HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())
        ) {
            client.toBlocking().exchange(HttpRequest.GET("/internal/lifecycle/drain"), String.class);

            assertEquals(
                HttpStatus.OK,
                client.toBlocking().exchange(HttpRequest.GET("/internal/lifecycle/livez"), String.class).getStatus()
            );
        }
    }

    private static Map<String, Object> settings() {
        return Map.of(
            "spec.name", "access-gateway-lifecycle-endpoints",
            "micronaut.server.port", -1,
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        );
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-lifecycle-endpoints")
    static final class LifecycleEndpointTestFactory {
        @Singleton
        SessionResolver sessionResolver() {
            return new SessionResolver() {
                @Override
                public Optional<ResolvedSession> resolveSession(String sessionId) {
                    return Optional.empty();
                }
            };
        }
    }
}
