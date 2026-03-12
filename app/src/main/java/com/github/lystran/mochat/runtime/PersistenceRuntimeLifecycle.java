package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

@Singleton
@Context
@Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
public final class PersistenceRuntimeLifecycle implements AutoCloseable {
    private final RocketMqPersistenceConsumer persistenceConsumer;
    private final boolean consumerEnabled;

    private boolean started;

    public PersistenceRuntimeLifecycle(
        RocketMqPersistenceConsumer persistenceConsumer,
        @Property(name = "mochat.rocketmq.consumer.enabled", defaultValue = "true") boolean consumerEnabled
    ) {
        this.persistenceConsumer = persistenceConsumer;
        this.consumerEnabled = consumerEnabled;
    }

    @PostConstruct
    void start() {
        if (!consumerEnabled) {
            return;
        }

        persistenceConsumer.start();
        started = true;
    }

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
