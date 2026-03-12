package com.github.lystran.mochat.apiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.logic.http.ConversationController;
import com.github.lystran.mochat.logic.http.HistoryController;
import com.github.lystran.mochat.logic.repository.ConversationStateRepository;
import com.github.lystran.mochat.logic.repository.HistoryRepository;
import com.github.lystran.mochat.logic.service.SessionService;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServiceHistoryOwnershipTest {
    private static final String SPEC_NAME = "api-service-history-ownership";
    private static final long OWNER_UID = 1001L;
    private static final long CONVERSATION_ID = 55L;
    private static final long LATEST_SEQ = 12L;
    private static final long LATEST_MESSAGE_TIME = 1_700_000_000_123L;
    private static final long PENDING_SEQ = 13L;
    private static final long PENDING_MSG_ID = 8_003L;
    private static final long PENDING_MESSAGE_TIME = 1_700_000_000_999L;
    private static final List<HistoryRepository.HistoryMessage> PERSISTED_HISTORY = List.of(
        new HistoryRepository.HistoryMessage(LATEST_SEQ, 8_002L, LATEST_MESSAGE_TIME, "payload-12"),
        new HistoryRepository.HistoryMessage(11L, 8_001L, LATEST_MESSAGE_TIME - 1_000L, "payload-11"),
        new HistoryRepository.HistoryMessage(10L, 8_000L, LATEST_MESSAGE_TIME - 2_000L, "payload-10")
    );

    @Test
    void exposesHistoryQueryEntrypointFromDedicatedApiRuntime() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "micronaut.server.port", 0,
            "mochat.api-service.dependencies.redis-enabled", false,
            "mochat.api-service.dependencies.postgres-enabled", false
        ))) {
            ApplicationContext context = server.getApplicationContext();
            SessionService sessionService = context.getBean(SessionService.class);
            String sessionId = sessionService.issueSession(OWNER_UID);

            assertTrue(context.containsBean(HistoryController.class));
            assertTrue(context.containsBean(ConversationController.class));

            JsonNode historyBody = readJson(context, historyRequest(server.getURI(), sessionId).body());
            assertEquals(2, historyBody.path("items").size());
            assertEquals(LATEST_SEQ, historyBody.path("items").get(0).path("seq").longValue());
            assertEquals(8_002L, historyBody.path("items").get(0).path("msgId").longValue());
            assertEquals("payload-12", historyBody.path("items").get(0).path("payloadBase64").textValue());

            JsonNode stateBody = readJson(context, conversationStateRequest(server.getURI(), sessionId).body());
            assertEquals(CONVERSATION_ID, stateBody.path("conversationId").longValue());
            assertEquals(LATEST_SEQ, stateBody.path("latestSeq").longValue());
            assertEquals(LATEST_MESSAGE_TIME, stateBody.path("latestMessageTime").longValue());
        }
    }

    @Test
    void historyAndConversationStateRemainOnCommittedViewUntilPersistenceCommits() throws Exception {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", SPEC_NAME,
            "grpc.server.port", 0,
            "micronaut.server.port", 0,
            "mochat.api-service.dependencies.redis-enabled", false,
            "mochat.api-service.dependencies.postgres-enabled", false
        ))) {
            ApplicationContext context = server.getApplicationContext();
            SessionService sessionService = context.getBean(SessionService.class);
            EventuallyConsistentHistoryReadModel readModel = context.getBean(EventuallyConsistentHistoryReadModel.class);
            String sessionId = sessionService.issueSession(OWNER_UID);

            readModel.markRealtimeDelivered(
                new HistoryRepository.HistoryMessage(PENDING_SEQ, PENDING_MSG_ID, PENDING_MESSAGE_TIME, "payload-13"),
                new ConversationStateRepository.ConversationLatestState(CONVERSATION_ID, PENDING_SEQ, PENDING_MESSAGE_TIME)
            );

            JsonNode historyBeforeCommit = readJson(context, historyRequest(server.getURI(), sessionId).body());
            assertEquals(2, historyBeforeCommit.path("items").size());
            assertEquals(LATEST_SEQ, historyBeforeCommit.path("items").get(0).path("seq").longValue());
            assertEquals(8_002L, historyBeforeCommit.path("items").get(0).path("msgId").longValue());

            JsonNode stateBeforeCommit = readJson(context, conversationStateRequest(server.getURI(), sessionId).body());
            assertEquals(LATEST_SEQ, stateBeforeCommit.path("latestSeq").longValue());
            assertEquals(LATEST_MESSAGE_TIME, stateBeforeCommit.path("latestMessageTime").longValue());

            readModel.commitPending();

            JsonNode historyAfterCommit = readJson(context, historyRequest(server.getURI(), sessionId).body());
            assertEquals(PENDING_SEQ, historyAfterCommit.path("items").get(0).path("seq").longValue());
            assertEquals(PENDING_MSG_ID, historyAfterCommit.path("items").get(0).path("msgId").longValue());
            assertEquals("payload-13", historyAfterCommit.path("items").get(0).path("payloadBase64").textValue());

            JsonNode stateAfterCommit = readJson(context, conversationStateRequest(server.getURI(), sessionId).body());
            assertEquals(PENDING_SEQ, stateAfterCommit.path("latestSeq").longValue());
            assertEquals(PENDING_MESSAGE_TIME, stateAfterCommit.path("latestMessageTime").longValue());
        }
    }

    private static HttpResponse<String> historyRequest(URI serverUri, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(serverUri.resolve(
            "/history?sessionId=" + sessionId + "&conversationId=" + CONVERSATION_ID + "&limit=2"
        )).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response;
    }

    private static HttpResponse<String> conversationStateRequest(URI serverUri, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(serverUri.resolve(
            "/conversations/" + CONVERSATION_ID + "/state?sessionId=" + sessionId
        )).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response;
    }

    private static JsonNode readJson(ApplicationContext context, String body) throws Exception {
        return context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).readTree(body);
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static final class TestBeans {
        @Singleton
        @Replaces(RedisCommands.class)
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            Map<String, String> values = new ConcurrentHashMap<>();
            return (RedisCommands<String, String>) java.lang.reflect.Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        yield "OK";
                    }
                    case "get" -> values.get((String) args[0]);
                    case "del" -> values.remove((String) args[0]) == null ? 0L : 1L;
                    case "toString" -> "ApiServiceHistoryOwnershipTestRedisCommands";
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

        @Singleton
        EventuallyConsistentHistoryReadModel eventuallyConsistentHistoryReadModel() {
            return new EventuallyConsistentHistoryReadModel();
        }

        @Singleton
        HistoryRepository historyRepository(EventuallyConsistentHistoryReadModel readModel) {
            return readModel;
        }

        @Singleton
        ConversationStateRepository conversationStateRepository(EventuallyConsistentHistoryReadModel readModel) {
            return readModel;
        }
    }

    static final class EventuallyConsistentHistoryReadModel implements HistoryRepository, ConversationStateRepository {
        private final List<HistoryMessage> committedHistory = new ArrayList<>(PERSISTED_HISTORY);
        private ConversationLatestState committedState = new ConversationLatestState(CONVERSATION_ID, LATEST_SEQ, LATEST_MESSAGE_TIME);
        private HistoryMessage pendingMessage;
        private ConversationLatestState pendingState;

        void markRealtimeDelivered(HistoryMessage message, ConversationLatestState state) {
            this.pendingMessage = message;
            this.pendingState = state;
        }

        void commitPending() {
            if (pendingMessage == null || pendingState == null) {
                return;
            }
            committedHistory.removeIf(message -> message.seq() == pendingMessage.seq() || message.msgId() == pendingMessage.msgId());
            committedHistory.add(0, pendingMessage);
            committedState = pendingState;
            pendingMessage = null;
            pendingState = null;
        }

        @Override
        public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, int limit) {
            if (conversationId != CONVERSATION_ID) {
                return List.of();
            }
            return committedHistory.stream().limit(limit).toList();
        }

        @Override
        public List<HistoryMessage> findHistory(long conversationId, Long cursorSeq, Long startSeq, Long endSeq, int limit) {
            return findHistory(conversationId, cursorSeq, limit);
        }

        @Override
        public boolean hasConversationAccess(long conversationId, long requesterUid) {
            return conversationId == CONVERSATION_ID && requesterUid == OWNER_UID;
        }

        @Override
        public Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid) {
            if (conversationId == CONVERSATION_ID && requesterUid == OWNER_UID) {
                return Optional.of(9L);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ConversationLatestState> findConversationLatestState(long conversationId) {
            if (conversationId != CONVERSATION_ID) {
                return Optional.empty();
            }
            return Optional.of(committedState);
        }
    }
}
