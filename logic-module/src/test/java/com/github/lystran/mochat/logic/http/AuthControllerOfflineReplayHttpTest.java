package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.logic.chat.InboundMessageConsumer;
import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@Property(name = "spec.name", value = AuthControllerOfflineReplayHttpTest.SPEC_NAME)
class AuthControllerOfflineReplayHttpTest {
    static final String SPEC_NAME = "auth-controller-offline-replay-http";

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    RecordingOfflineQueue offlineQueue;

    @Inject
    RecordingEventBus eventBus;

    @Test
    void secondLoginReplaysOfflinePayloadsToOutboundTopicAndDrainsQueue() {
        String publicKey = encodeKey((byte) 9);

        var firstResponse = httpClient.toBlocking().exchange(
            HttpRequest.POST("/auth/login", Map.of("username", "http-offline-replay", "publicKey", publicKey)),
            Map.class
        );

        assertEquals(HttpStatus.OK, firstResponse.getStatus());
        Map<?, ?> firstBody = firstResponse.body();
        assertNotNull(firstBody);
        long userId = ((Number) firstBody.get("userId")).longValue();

        int drainCallsBeforeReplay = offlineQueue.drainCallsFor(userId).size();
        int outboundEventsBeforeReplay = eventBus.publishedEvents(OfflineReplayService.DEFAULT_OUTBOUND_TOPIC).size();
        String queuedPayload = "PRIVATE_MESSAGE|PROTOBUF|" + Base64.getEncoder().encodeToString("payload-1".getBytes(StandardCharsets.UTF_8));
        offlineQueue.seed(userId, queuedPayload);

        var secondResponse = httpClient.toBlocking().exchange(
            HttpRequest.POST("/auth/login", Map.of("username", "http-offline-replay", "publicKey", publicKey)),
            Map.class
        );

        List<RecordingOfflineQueue.DrainCall> drainCalls = offlineQueue.drainCallsFor(userId);
        List<String> outboundEvents = eventBus.publishedEvents(OfflineReplayService.DEFAULT_OUTBOUND_TOPIC);
        assertEquals(HttpStatus.OK, secondResponse.getStatus());
        assertEquals(outboundEventsBeforeReplay + 1, outboundEvents.size());
        assertEquals(userId + "|" + queuedPayload, outboundEvents.getLast());
        assertEquals(List.of(), offlineQueue.remaining(userId));
        assertEquals(drainCallsBeforeReplay + 1, drainCalls.size());
        assertEquals(OfflineReplayService.MAX_REPLAY_ITEMS, drainCalls.getLast().maxItems());
    }

    private static String encodeKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    @Factory
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class TestBeans {
        @Singleton
        @Primary
        RecordingEventBus eventBus() {
            return new RecordingEventBus();
        }

        @Singleton
        RecordingOfflineQueue offlineQueue() {
            return new RecordingOfflineQueue();
        }

        @Singleton
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
                    case "toString" -> "AuthControllerOfflineReplayHttpTestRedisCommands";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
                }
            );
        }

        @Singleton
        @Replaces(InboundMessageConsumer.class)
        InboundMessageConsumer inboundMessageConsumer() {
            return Mockito.mock(InboundMessageConsumer.class);
        }
    }

    static final class RecordingEventBus implements EventBus {
        private final Map<String, CopyOnWriteArrayList<Consumer<String>>> subscribersByTopic = new ConcurrentHashMap<>();
        private final Map<String, CopyOnWriteArrayList<String>> eventsByTopic = new ConcurrentHashMap<>();

        @Override
        public void publish(String topic, String event) {
            eventsByTopic.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(event);
            for (Consumer<String> subscriber : subscribersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
                subscriber.accept(event);
            }
        }

        @Override
        public AutoCloseable subscribe(String topic, Consumer<String> subscriber) {
            CopyOnWriteArrayList<Consumer<String>> subscribers = subscribersByTopic.computeIfAbsent(
                topic,
                ignored -> new CopyOnWriteArrayList<>()
            );
            subscribers.add(subscriber);
            return () -> subscribers.remove(subscriber);
        }

        List<String> publishedEvents(String topic) {
            return new ArrayList<>(eventsByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()));
        }
    }

    static final class RecordingOfflineQueue implements OfflineQueue {
        private final Map<Long, ArrayDeque<String>> payloadsByUser = new ConcurrentHashMap<>();
        private final List<DrainCall> drainCalls = new CopyOnWriteArrayList<>();

        void seed(long userId, String... payloads) {
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            for (String payload : payloads) {
                queue.addLast(payload);
            }
        }

        List<String> remaining(long userId) {
            return new ArrayList<>(payloadsByUser.getOrDefault(userId, new ArrayDeque<>()));
        }

        List<DrainCall> drainCallsFor(long userId) {
            return drainCalls.stream().filter(call -> call.userId() == userId).toList();
        }

        @Override
        public void enqueue(long userId, String payload, int maxQueueSize) {
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            while (queue.size() >= maxQueueSize) {
                queue.pollFirst();
            }
            queue.addLast(payload);
        }

        @Override
        public List<String> drain(long userId, int maxItems) {
            drainCalls.add(new DrainCall(userId, maxItems));
            ArrayDeque<String> queue = payloadsByUser.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
            List<String> drained = new ArrayList<>();
            while (drained.size() < maxItems && !queue.isEmpty()) {
                drained.add(queue.removeFirst());
            }
            if (queue.isEmpty()) {
                payloadsByUser.remove(userId, queue);
            }
            return drained;
        }

        record DrainCall(long userId, int maxItems) {
        }
    }
}
