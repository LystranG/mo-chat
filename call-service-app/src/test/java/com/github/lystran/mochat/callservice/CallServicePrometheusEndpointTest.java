package com.github.lystran.mochat.callservice;

import com.github.lystran.mochat.call.mq.CallOfflineNotificationMqProducer;
import com.github.lystran.mochat.call.service.CallRelationshipService;
import com.github.lystran.mochat.call.service.CallSessionResolver;
import com.github.lystran.mochat.common.id.IdGenerator;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallServicePrometheusEndpointTest {
    @Test
    void exposesPrometheusMetricsEndpoint() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "call-service-prometheus-endpoint",
            "micronaut.server.port", -1,
            "mochat.livekit.url", "ws://livekit.local",
            "mochat.livekit.api-key", "local-key",
            "mochat.livekit.api-secret", "local-secret",
            "mochat.call-service.dependencies.postgres-enabled", false,
            "mochat.call-service.dependencies.redis-enabled", false,
            "mochat.call-service.dependencies.mq-enabled", false,
            "mochat.call-service.flyway.migrate-on-start", false,
            "mochat.call-service.queue.consumer-enabled", false
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
    @Requires(property = "spec.name", value = "call-service-prometheus-endpoint")
    static final class TestBeanFactory {
        @Singleton
        @Primary
        CallSessionResolver callSessionResolver() {
            return Mockito.mock(CallSessionResolver.class);
        }

        @Singleton
        @Primary
        CallRelationshipService callRelationshipService() {
            return Mockito.mock(CallRelationshipService.class);
        }

        @Singleton
        @Primary
        RedisCommands<String, String> redisCommands() {
            return Mockito.mock(RedisCommands.class);
        }

        @Singleton
        @Primary
        javax.sql.DataSource dataSource() {
            return Mockito.mock(javax.sql.DataSource.class);
        }

        @Singleton
        @Primary
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory() {
            return Mockito.mock(org.apache.ibatis.session.SqlSessionFactory.class);
        }

        @Singleton
        @Primary
        IdGenerator idGenerator() {
            IdGenerator mock = Mockito.mock(IdGenerator.class);
            when(mock.nextId()).thenReturn(9001L);
            return mock;
        }

        @Singleton
        @Primary
        CallOfflineNotificationMqProducer mqProducer() {
            CallOfflineNotificationMqProducer mock = Mockito.mock(CallOfflineNotificationMqProducer.class);
            when(mock.sendBatch(Mockito.anyList())).thenReturn(true);
            return mock;
        }
    }
}
