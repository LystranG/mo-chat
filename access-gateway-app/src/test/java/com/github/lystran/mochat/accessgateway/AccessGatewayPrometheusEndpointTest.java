package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayPrometheusEndpointTest {
    @Test
    void exposesPrometheusMetricsEndpoint() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "access-gateway-prometheus-endpoint",
            "micronaut.server.port", -1,
            "grpc.server.port", 0,
            "mochat.access-gateway.runtime.enabled", false,
            "mochat.access-gateway.tcp.enabled", true,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false,
            "mochat.access-gateway.dependencies.redis-enabled", false
        ))) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.getURL() + "/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("jvm_"), () -> response.body().substring(0, Math.min(200, response.body().length())));
        }
    }

    @Factory
    @Requires(property = "spec.name", value = "access-gateway-prometheus-endpoint")
    static final class TestBeans {
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
