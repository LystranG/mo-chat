package com.github.lystran.mochat.apiservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import io.grpc.Channel;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.http.HttpStatus;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiServiceSessionAuthorityIntegrationTest {
    private static final String SPEC_NAME = "api-service-session-authority-integration";

    @Test
    void loginIssuedSessionIsResolvedByGrpcAndPreviousSessionBecomesReplaced() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "micronaut.server.port", -1
        ))) {
            ApplicationContext context = server.getApplicationContext();
            HttpClient httpClient = HttpClient.newHttpClient();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub stub =
                context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);

            Map<?, ?> firstBody = login(httpClient, objectMapper, server.getURI(), "alice", encodeKey((byte) 1));
            String firstSessionId = (String) firstBody.get("sessionId");
            long userId = ((Number) firstBody.get("userId")).longValue();

            var firstResolution = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(firstSessionId).build());

            Map<?, ?> secondBody = login(httpClient, objectMapper, server.getURI(), "alice", encodeKey((byte) 1));
            String secondSessionId = (String) secondBody.get("sessionId");

            var replacedResolution = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(firstSessionId).build());
            var activeResolution = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId(secondSessionId).build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, firstResolution.getStatus());
            assertEquals(userId, firstResolution.getPrincipal().getUserId());
            assertEquals(1L, firstResolution.getPrincipal().getSessionVersion());

            assertNotEquals(firstSessionId, secondSessionId);
            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED, replacedResolution.getStatus());
            assertEquals(userId, replacedResolution.getPrincipal().getUserId());
            assertEquals(1L, replacedResolution.getPrincipal().getSessionVersion());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, activeResolution.getStatus());
            assertEquals(userId, activeResolution.getPrincipal().getUserId());
            assertEquals(2L, activeResolution.getPrincipal().getSessionVersion());
        }
    }

    private static Map<?, ?> login(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        URI serverUri,
        String username,
        String publicKey
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(serverUri.resolve("/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                "username",
                username,
                "publicKey",
                publicKey
            ))))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.OK.getCode(), response.statusCode());
        return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
    }

    private static String encodeKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestBeans {
        @Singleton
        @Primary
        SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return SessionAuthorityApiGrpc.newBlockingStub(channel);
        }

        @Singleton
        @Replaces(RedisCommands.class)
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            Map<String, String> values = new ConcurrentHashMap<>();
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        yield "OK";
                    }
                    case "get" -> values.get((String) args[0]);
                    case "del" -> values.remove((String) args[0]) == null ? 0L : 1L;
                    case "toString" -> "ApiServiceSessionAuthorityIntegrationTestRedisCommands";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
                }
            );
        }

        @Singleton
        EventBus eventBus() {
            return new EventBus() {
                @Override
                public void publish(String topic, String event) {
                }

                @Override
                public AutoCloseable subscribe(String topic, java.util.function.Consumer<String> subscriber) {
                    return () -> {
                    };
                }
            };
        }

        @Singleton
        OfflineQueue offlineQueue() {
            return new OfflineQueue() {
                @Override
                public void enqueue(long userId, String payload, int maxQueueSize) {
                }

                @Override
                public List<String> drain(long userId, int maxItems) {
                    return List.of();
                }
            };
        }
    }
}
