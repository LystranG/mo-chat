package com.github.lystran.mochat;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.connection.NettyChatServer;
import com.github.lystran.mochat.logic.chat.InboundMessageConsumer;
import com.github.lystran.mochat.logic.chat.JdbcReceiptConversationStateStore;
import com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore;
import com.github.lystran.mochat.logic.chat.ReceiptService;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.logic.http.FriendsController;
import com.github.lystran.mochat.logic.http.GroupsController;
import com.github.lystran.mochat.logic.service.UserService;
import com.github.lystran.mochat.persistence.ConversationRepository;
import com.github.lystran.mochat.persistence.MessageRepository;
import com.github.lystran.mochat.persistence.MqConsumer;
import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import com.github.lystran.mochat.runtime.PersistenceRuntimeLifecycle;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import io.netty.channel.Channel;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

class AppRuntimeAssemblyTest {
    @Test
    void embeddedServerSeesLogicControllersAndServicesWhenLegacyPersistenceCompatibilityEnabled() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, AppTestSupport.runtimeAssemblyServerProperties())) {
            var context = server.getApplicationContext();

            assertTrue(server.isRunning());
            assertNotNull(context.getBean(FriendsController.class));
            assertNotNull(context.getBean(GroupsController.class));
            assertNotNull(context.getBean(UserService.class));
            assertNotNull(context.getBean(NettyChatServer.class));
            assertNotNull(context.getBean(MqConsumer.class));
            assertNotNull(context.getBean(DefaultMQPushConsumer.class));
            assertNotNull(context.getBean(RocketMqPersistenceConsumer.class));
            assertNotNull(context.getBean(PersistenceRuntimeLifecycle.class));
        }
    }

    @Test
    void applicationContextAssemblesRuntimeBeansWhenLegacyPersistenceCompatibilityEnabled() {
        try (ApplicationContext context = ApplicationContext.run(AppTestSupport.runtimeAssemblyContextProperties())) {
            assertNotNull(context.getBean(DataSource.class));
            assertNotNull(context.getBean(RedisClient.class));
            assertNotNull(context.getBean(StatefulRedisConnection.class));
            assertNotNull(context.getBean(RedisCommands.class));
            assertNotNull(context.getBean(StatefulRedisPubSubConnection.class));
            assertNotNull(context.getBean(EventBus.class));
            assertNotNull(context.getBean(OfflineQueue.class));
            assertNotNull(context.getBean(ConversationLock.class));
            assertNotNull(context.getBean(ConversationSeqGenerator.class));
            assertNotNull(context.getBean(IdempotencyStore.class));
            assertNotNull(context.getBean(UserChannelDirectory.class));
            assertNotNull(context.getBean(IdGenerator.class));
            assertNotNull(context.getBean(MessageRepository.class));
            assertNotNull(context.getBean(ConversationRepository.class));
            assertNotNull(context.getBean(GroupMessageCache.class));
            assertNotNull(context.getBean(MqConsumer.class));
            assertNotNull(context.getBean(DefaultMQPushConsumer.class));
            assertNotNull(context.getBean(RocketMqPersistenceConsumer.class));
            assertNotNull(context.getBean(PersistenceRuntimeLifecycle.class));
            assertNotNull(context.getBean(DefaultMQProducer.class));
            assertNotNull(context.getBean(RocketMqProducer.class));
            assertNotNull(context.getBean(NettyChatServer.class));
        }
    }

    @Test
    void applicationContextUsesApplicationYamlDefaultRuntimePropertiesWithoutPersistenceOwners() {
        try (ApplicationContext context = ApplicationContext.run(AppTestSupport.runtimeAssemblyDefaultConfigProperties())) {
            assertNotNull(context.getBean(DataSource.class));
            assertNotNull(context.getBean(RedisClient.class));
            assertNotNull(context.getBean(DefaultMQPushConsumer.class));
            assertNotNull(context.getBean(DefaultMQProducer.class));
            assertNotNull(context.getBean(NettyChatServer.class));
            assertTrue(context.findBean(MessageRepository.class).isEmpty());
            assertTrue(context.findBean(ConversationRepository.class).isEmpty());
            assertTrue(context.findBean(GroupMessageCache.class).isEmpty());
            assertTrue(context.findBean(MqConsumer.class).isEmpty());
            assertTrue(context.findBean(DefaultMQPushConsumer.class).isEmpty());
            assertTrue(context.findBean(RocketMqPersistenceConsumer.class).isEmpty());
            assertTrue(context.findBean(PersistenceRuntimeLifecycle.class).isEmpty());
            assertTrue(context.findBean(ReceiptConversationStateStore.class).isEmpty());
            assertTrue(context.findBean(InboundMessageConsumer.class).isEmpty());
        }
    }

    @Test
    void applicationContextAssemblesInboundConsumerWhenLegacyPersistenceCompatibilityExplicitlyEnabled() {
        try (ApplicationContext context = ApplicationContext.run(AppTestSupport.runtimeAssemblyInboundConsumerCompatibilityProperties())) {
            assertInstanceOf(JdbcReceiptConversationStateStore.class, context.getBean(ReceiptConversationStateStore.class));
            assertNotNull(context.getBean(ReceiptService.class));
            assertNotNull(context.getBean(InboundMessageConsumer.class));
        }
    }
}

@Factory
@Requires(property = "spec.name", value = AppTestSupport.RUNTIME_ASSEMBLY_SPEC)
final class AppRuntimeAssemblyTestFactory {
    @Singleton
    InMemoryRedisRuntime inMemoryRedisRuntime() {
        return new InMemoryRedisRuntime();
    }

    @Singleton
    @Replaces(StatefulRedisConnection.class)
    @SuppressWarnings("unchecked")
    StatefulRedisConnection<String, String> redisConnection(InMemoryRedisRuntime runtime) {
        return runtime.redisConnection();
    }

    @Singleton
    @Replaces(RedisCommands.class)
    @SuppressWarnings("unchecked")
    RedisCommands<String, String> redisCommands(InMemoryRedisRuntime runtime) {
        return runtime.redisCommands();
    }

    @Singleton
    @Replaces(StatefulRedisPubSubConnection.class)
    @SuppressWarnings("unchecked")
    StatefulRedisPubSubConnection<String, String> redisPubSubConnection(InMemoryRedisRuntime runtime) {
        return runtime.redisPubSubConnection();
    }

    @Singleton
    @Replaces(DefaultMQProducer.class)
    DefaultMQProducer defaultMqProducer() {
        return new DefaultMQProducer("app-runtime-assembly-test");
    }

    private static final class InMemoryRedisRuntime {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, ConcurrentLinkedDeque<String>> lists = new ConcurrentHashMap<>();
        private final Map<String, NavigableMap<Double, List<String>>> sortedSets = new ConcurrentHashMap<>();
        private final Map<String, CopyOnWriteArrayList<RedisPubSubListener<String, String>>> listenersByTopic = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<RedisPubSubListener<String, String>> listeners = new CopyOnWriteArrayList<>();
        private final RedisCommands<String, String> redisCommands = createRedisCommands();
        private final StatefulRedisConnection<String, String> redisConnection = createRedisConnection();
        private final StatefulRedisPubSubConnection<String, String> redisPubSubConnection = createRedisPubSubConnection();

        RedisCommands<String, String> redisCommands() {
            return redisCommands;
        }

        StatefulRedisConnection<String, String> redisConnection() {
            return redisConnection;
        }

        StatefulRedisPubSubConnection<String, String> redisPubSubConnection() {
            return redisPubSubConnection;
        }

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> createRedisCommands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                new InMemoryRedisCommandsInvocationHandler()
            );
        }

        @SuppressWarnings("unchecked")
        private StatefulRedisConnection<String, String> createRedisConnection() {
            return (StatefulRedisConnection<String, String>) Proxy.newProxyInstance(
                StatefulRedisConnection.class.getClassLoader(),
                new Class<?>[] {StatefulRedisConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "sync" -> redisCommands;
                    case "close" -> null;
                    case "isOpen" -> true;
                    case "toString" -> "InMemoryRedisConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                        "Unsupported StatefulRedisConnection method: " + method.getName()
                    );
                }
            );
        }

        @SuppressWarnings("unchecked")
        private StatefulRedisPubSubConnection<String, String> createRedisPubSubConnection() {
            RedisPubSubCommands<String, String> syncCommands = (RedisPubSubCommands<String, String>) Proxy.newProxyInstance(
                RedisPubSubCommands.class.getClassLoader(),
                new Class<?>[] {RedisPubSubCommands.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals("subscribe")) {
                        forEachTopic(args, topic -> listenersByTopic
                            .computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>())
                            .addAll(listeners));
                        return null;
                    }
                    if (methodName.equals("unsubscribe")) {
                        forEachTopic(args, listenersByTopic::remove);
                        return null;
                    }
                    if (methodName.equals("toString")) {
                        return "InMemoryRedisPubSubCommands";
                    }
                    if (methodName.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (methodName.equals("equals")) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(
                        "Unsupported RedisPubSubCommands method: " + methodName
                    );
                }
            );

            return (StatefulRedisPubSubConnection<String, String>) Proxy.newProxyInstance(
                StatefulRedisPubSubConnection.class.getClassLoader(),
                new Class<?>[] {StatefulRedisPubSubConnection.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if (methodName.equals("sync")) {
                        return syncCommands;
                    }
                    if (methodName.equals("addListener")) {
                        listeners.add((RedisPubSubListener<String, String>) args[0]);
                        return null;
                    }
                    if (methodName.equals("removeListener")) {
                        listeners.remove(args[0]);
                        return null;
                    }
                    if (methodName.equals("close")) {
                        listeners.clear();
                        listenersByTopic.clear();
                        return null;
                    }
                    if (methodName.equals("isOpen")) {
                        return true;
                    }
                    if (methodName.equals("toString")) {
                        return "InMemoryRedisPubSubConnection";
                    }
                    if (methodName.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (methodName.equals("equals")) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(
                        "Unsupported StatefulRedisPubSubConnection method: " + methodName
                    );
                }
            );
        }

        private final class InMemoryRedisCommandsInvocationHandler implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String methodName = method.getName();
                if (methodName.equals("set")) {
                    String key = (String) args[0];
                    String value = (String) args[1];
                    if (args.length == 3) {
                        values.putIfAbsent(key, value);
                        return values.get(key).equals(value) ? "OK" : null;
                    }
                    values.put(key, value);
                    return "OK";
                }
                if (methodName.equals("setnx")) {
                    return values.putIfAbsent((String) args[0], (String) args[1]) == null;
                }
                if (methodName.equals("get")) {
                    return values.get((String) args[0]);
                }
                if (methodName.equals("del")) {
                    return values.remove((String) args[0]) == null ? 0L : 1L;
                }
                if (methodName.equals("incr")) {
                    String key = (String) args[0];
                    long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
                    values.put(key, Long.toString(next));
                    return next;
                }
                if (methodName.equals("rpush")) {
                    String key = (String) args[0];
                    String payload = (String) args[1];
                    var deque = lists.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
                    deque.addLast(payload);
                    return (long) deque.size();
                }
                if (methodName.equals("ltrim")) {
                    String key = (String) args[0];
                    long start = ((Number) args[1]).longValue();
                    long stop = ((Number) args[2]).longValue();
                    trimList(key, start, stop);
                    return "OK";
                }
                if (methodName.equals("lpop") && args.length == 2) {
                    String key = (String) args[0];
                    long count = ((Number) args[1]).longValue();
                    var deque = lists.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
                    List<String> drained = new ArrayList<>((int) Math.max(0L, count));
                    for (int index = 0; index < count; index++) {
                        String value = deque.pollFirst();
                        if (value == null) {
                            break;
                        }
                        drained.add(value);
                    }
                    return drained;
                }
                if (methodName.equals("publish")) {
                    String topic = (String) args[0];
                    String payload = (String) args[1];
                    for (RedisPubSubListener<String, String> listener : listenersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
                        listener.message(topic, payload);
                    }
                    return (long) listenersByTopic.getOrDefault(topic, new CopyOnWriteArrayList<>()).size();
                }
                if (methodName.equals("zadd")) {
                    String key = (String) args[0];
                    double score = ((Number) args[1]).doubleValue();
                    String member = (String) args[2];
                    sortedSets.computeIfAbsent(key, ignored -> new TreeMap<>())
                        .computeIfAbsent(score, ignored -> new ArrayList<>())
                        .add(member);
                    return true;
                }
                if (methodName.equals("zcard")) {
                    String key = (String) args[0];
                    return sortedSets.getOrDefault(key, new TreeMap<>())
                        .values()
                        .stream()
                        .mapToLong(List::size)
                        .sum();
                }
                if (methodName.equals("zremrangebyrank")) {
                    String key = (String) args[0];
                    long start = ((Number) args[1]).longValue();
                    long stop = ((Number) args[2]).longValue();
                    return removeSortedSetRangeByRank(key, start, stop);
                }
                if (methodName.equals("toString")) {
                    return "InMemoryRedisCommands";
                }
                if (methodName.equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (methodName.equals("equals")) {
                    return proxy == args[0];
                }

                throw new UnsupportedOperationException("Unsupported RedisCommands method: " + methodName);
            }

            private void trimList(String key, long start, long stop) {
                var deque = lists.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
                List<String> snapshot = new ArrayList<>(deque);
                if (snapshot.isEmpty()) {
                    return;
                }

                int fromIndex = normalizeIndex(start, snapshot.size());
                int toIndex = normalizeIndex(stop, snapshot.size());
                if (fromIndex > toIndex) {
                    deque.clear();
                    return;
                }

                List<String> retained = snapshot.subList(fromIndex, toIndex + 1);
                deque.clear();
                deque.addAll(retained);
            }

            private int normalizeIndex(long index, int size) {
                long resolved = index < 0 ? size + index : index;
                if (resolved < 0) {
                    return 0;
                }
                if (resolved >= size) {
                    return size - 1;
                }
                return (int) resolved;
            }

            private long removeSortedSetRangeByRank(String key, long start, long stop) {
                NavigableMap<Double, List<String>> sortedSet = sortedSets.get(key);
                if (sortedSet == null || sortedSet.isEmpty()) {
                    return 0L;
                }

                List<Map.Entry<Double, String>> flattened = new ArrayList<>();
                for (Map.Entry<Double, List<String>> entry : sortedSet.entrySet()) {
                    for (String value : entry.getValue()) {
                        flattened.add(Map.entry(entry.getKey(), value));
                    }
                }

                int from = (int) Math.max(0L, start);
                int to = (int) Math.min(flattened.size() - 1L, stop);
                if (from > to) {
                    return 0L;
                }

                List<Map.Entry<Double, String>> retained = new ArrayList<>(flattened);
                retained.subList(from, to + 1).clear();

                NavigableMap<Double, List<String>> rebuilt = new TreeMap<>();
                for (Map.Entry<Double, String> entry : retained) {
                    rebuilt.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
                }

                sortedSets.put(key, rebuilt);
                return to - from + 1L;
            }
        }

        private void forEachTopic(Object[] args, java.util.function.Consumer<String> consumer) {
            if (args == null || args.length == 0) {
                return;
            }

            if (args.length == 1 && args[0] instanceof String[] topics) {
                for (String topic : topics) {
                    consumer.accept(topic);
                }
                return;
            }

            for (Object arg : args) {
                consumer.accept((String) arg);
            }
        }
    }
}
