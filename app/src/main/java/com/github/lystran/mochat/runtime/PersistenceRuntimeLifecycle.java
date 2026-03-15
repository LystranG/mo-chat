package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * 负责在应用启动和关闭时拉起或停掉 RocketMQ 持久化消费者。
 */
@Singleton
@Context
public final class PersistenceRuntimeLifecycle implements AutoCloseable {
    private final RocketMqPersistenceConsumer persistenceConsumer;
    private final boolean consumerEnabled;

    private boolean started;

    /**
     * 收下持久化消费者和对应的开关配置。
     */
    public PersistenceRuntimeLifecycle(
        RocketMqPersistenceConsumer persistenceConsumer,
        @Property(name = "mochat.rocketmq.consumer.enabled", defaultValue = "true") boolean consumerEnabled
    ) {
        this.persistenceConsumer = persistenceConsumer;
        this.consumerEnabled = consumerEnabled;
    }

    /**
     * 应用启动时按配置决定要不要开始消费持久化消息。
     */
    @PostConstruct
    void start() {
        if (!consumerEnabled) {
            return;
        }

        persistenceConsumer.start();
        started = true;
    }

    /**
     * 应用关闭时停掉已经启动的持久化消费者。
     */
    @PreDestroy
    @Override
    public void close() {
        if (!started) {
            return;
        }

        persistenceConsumer.shutdown();
        started = false;
    }
}
