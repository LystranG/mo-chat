package com.github.lystran.mochat.persistenceservice;

import com.github.lystran.mochat.persistence.ConversationRepository;
import com.github.lystran.mochat.persistence.MessageRepository;
import com.github.lystran.mochat.persistence.MqConsumer;
import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import com.github.lystran.mochat.persistence.cache.GroupMessageCache;
import com.github.lystran.mochat.logic.chat.JdbcReceiptConversationStateStore;
import com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;

class PersistenceServiceApplicationContextTest {
    private static final String PERSISTENCE_RUNTIME_SPEC = "persistence-service-runtime-context";
    private static final String DEFAULT_CONFIG_SPEC = "persistence-service-default-config-context";

    @Test
    void bindsDedicatedPersistenceServiceConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.persistence-service.queue.consumer-enabled", false,
            "mochat.persistence-service.dependencies.postgres-enabled", false,
            "mochat.persistence-service.dependencies.redis-enabled", false,
            "mochat.persistence-service.dependencies.mq-enabled", false
        ))) {
            assertFalse(context.getRequiredProperty("mochat.persistence-service.queue.consumer-enabled", Boolean.class));
            assertFalse(context.getRequiredProperty("mochat.persistence-service.dependencies.mq-enabled", Boolean.class));
        }
    }

    @Test
    void assemblesDedicatedPersistenceRuntimeBeanGraphWhenInfrastructureBeansProvided() {
        RecordingPushConsumer.resetCounts();

        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", PERSISTENCE_RUNTIME_SPEC,
            "mochat.persistence-service.flyway.migrate-on-start", false,
            "mochat.persistence-service.queue.consumer-enabled", false,
            "mochat.persistence-service.dependencies.postgres-enabled", false,
            "mochat.persistence-service.dependencies.redis-enabled", false,
            "mochat.persistence-service.dependencies.mq-enabled", false
        ))) {
            assertNotNull(context.getBean(MessageRepository.class));
            assertNotNull(context.getBean(ConversationRepository.class));
            assertNotNull(context.getBean(GroupMessageCache.class));
            assertSame(JdbcReceiptConversationStateStore.class, context.getBean(ReceiptConversationStateStore.class).getClass());
            assertNotNull(context.getBean(MqConsumer.class));
            assertNotNull(context.getBean(DefaultMQPushConsumer.class));
            assertNotNull(context.getBean(RocketMqPersistenceConsumer.class));
            assertNotNull(context.getBean(Class.forName(
                "com.github.lystran.mochat.persistenceservice.runtime.PersistenceServiceRuntimeLifecycle"
            )));
            assertEquals(0, RecordingPushConsumer.startCount());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Persistence service runtime lifecycle should be present", exception);
        }

        assertEquals(0, RecordingPushConsumer.shutdownCount());
    }

    @Test
    void dedicatedRuntimeDoesNotStartConsumerWhenQueueIsDisabled() {
        RecordingPushConsumer.resetCounts();

        try (ApplicationContext ignored = ApplicationContext.run(Map.of(
            "spec.name", PERSISTENCE_RUNTIME_SPEC,
            "mochat.persistence-service.flyway.migrate-on-start", false,
            "mochat.persistence-service.queue.consumer-enabled", false,
            "mochat.persistence-service.dependencies.postgres-enabled", false,
            "mochat.persistence-service.dependencies.redis-enabled", false,
            "mochat.persistence-service.dependencies.mq-enabled", false
        ))) {
            assertEquals(0, RecordingPushConsumer.startCount());
        }

        assertEquals(0, RecordingPushConsumer.shutdownCount());
    }

    @Test
    void dedicatedRuntimeStartsAndStopsConsumerWhenQueueIsEnabled() {
        RecordingPushConsumer.resetCounts();

        try (ApplicationContext ignored = ApplicationContext.run(Map.of(
            "spec.name", PERSISTENCE_RUNTIME_SPEC,
            "mochat.persistence-service.flyway.migrate-on-start", false,
            "mochat.persistence-service.queue.consumer-enabled", true,
            "mochat.persistence-service.dependencies.postgres-enabled", false,
            "mochat.persistence-service.dependencies.redis-enabled", false,
            "mochat.persistence-service.dependencies.mq-enabled", false
        ))) {
            assertEquals(1, RecordingPushConsumer.startCount());
            assertEquals(0, RecordingPushConsumer.shutdownCount());
        }

        assertEquals(1, RecordingPushConsumer.shutdownCount());
    }

    @Test
    void defaultApplicationConfigurationMaterializesRealSharedInfrastructureBeans() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "spec.name", DEFAULT_CONFIG_SPEC,
            "mochat.persistence-service.flyway.migrate-on-start", false,
            "mochat.persistence-service.queue.consumer-enabled", false,
            "mochat.persistence-service.dependencies.redis-enabled", false
        ))) {
            DataSource dataSource = context.getBean(DataSource.class);
            Flyway flyway = context.getBean(Flyway.class);
            DefaultMQPushConsumer consumer = context.getBean(DefaultMQPushConsumer.class);

            assertTrue(dataSource instanceof PGSimpleDataSource);
            assertNotNull(flyway);
            assertNotNull(consumer);
            assertSame(MessageRepository.class, context.getBean(MessageRepository.class).getClass());
            assertSame(ConversationRepository.class, context.getBean(ConversationRepository.class).getClass());
            assertSame(GroupMessageCache.class, context.getBean(GroupMessageCache.class).getClass());
            assertSame(JdbcReceiptConversationStateStore.class, context.getBean(ReceiptConversationStateStore.class).getClass());
            assertSame(MqConsumer.class, context.getBean(MqConsumer.class).getClass());
            assertSame(RocketMqPersistenceConsumer.class, context.getBean(RocketMqPersistenceConsumer.class).getClass());
            assertNotNull(context.getBean(Class.forName(
                "com.github.lystran.mochat.persistenceservice.runtime.PersistenceServiceRuntimeLifecycle"
            )));
        }
    }

    @Factory
    @Requires(property = "spec.name", value = PERSISTENCE_RUNTIME_SPEC)
    static final class PersistenceRuntimeTestFactory {
        @Singleton
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Singleton
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            return mock(RedisCommands.class);
        }

        @Singleton
        DefaultMQPushConsumer defaultMqPushConsumer() {
            return new RecordingPushConsumer();
        }
    }

    @Factory
    @Requires(property = "spec.name", value = DEFAULT_CONFIG_SPEC)
    static final class DefaultConfigRedisBridgeFactory {
        @Singleton
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands() {
            return mock(RedisCommands.class);
        }
    }

    static final class RecordingPushConsumer extends DefaultMQPushConsumer {
        private static final AtomicInteger START_COUNT = new AtomicInteger();
        private static final AtomicInteger SHUTDOWN_COUNT = new AtomicInteger();

        RecordingPushConsumer() {
            super("persistence-service-runtime-test");
        }

        static void resetCounts() {
            START_COUNT.set(0);
            SHUTDOWN_COUNT.set(0);
        }

        static int startCount() {
            return START_COUNT.get();
        }

        static int shutdownCount() {
            return SHUTDOWN_COUNT.get();
        }

        @Override
        public void start() throws MQClientException {
            START_COUNT.incrementAndGet();
        }

        @Override
        public void shutdown() {
            SHUTDOWN_COUNT.incrementAndGet();
        }
    }
}
