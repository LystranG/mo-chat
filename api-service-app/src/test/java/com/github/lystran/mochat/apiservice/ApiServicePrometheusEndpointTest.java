package com.github.lystran.mochat.apiservice;

import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServicePrometheusEndpointTest {
    @Test
    void exposesPrometheusMetricsEndpoint() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "api-service-prometheus-endpoint",
            "micronaut.server.port", -1,
            "grpc.server.port", 0,
            "mochat.api-service.dependencies.postgres-enabled", false
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
    @Requires(property = "spec.name", value = "api-service-prometheus-endpoint")
    static final class TestBeans {
        @Singleton
        @Replaces(RedisCommands.class)
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            return (RedisCommands<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "ApiServicePrometheusEndpointTestRedisCommands";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
            );
        }
    }
}
