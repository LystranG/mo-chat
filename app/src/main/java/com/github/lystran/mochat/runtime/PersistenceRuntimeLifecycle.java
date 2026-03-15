package com.github.lystran.mochat.runtime;

import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

/**
 * 兼容旧持久化流程时，负责管理消息入库消费者的启停。
 */
@Singleton
@Context
@Requires(property = "mochat.legacy.persistence.enabled", value = "true", defaultValue = "false")
public final class PersistenceRuntimeLifecycle implements AutoCloseable {
    private final RocketMqPersistenceConsumer persistenceConsumer; // 把消息队列里的数据拉出来，交给旧入库流程处理。
    private final boolean consumerEnabled; // 允许部署时只保留 Bean，但先不真正启动消费。

    private boolean started; // 记录是否已启动，避免重复关闭。

    /**
     * 组装旧持久化消费者的生命周期管理器。
     */
    public PersistenceRuntimeLifecycle(
        RocketMqPersistenceConsumer persistenceConsumer,
        @Property(name = "mochat.rocketmq.consumer.enabled", defaultValue = "true") boolean consumerEnabled
    ) {
        this.persistenceConsumer = persistenceConsumer;
        this.consumerEnabled = consumerEnabled;
    }

    /**
     * 按开关决定是否启动旧持久化消费者。
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
     * 在进程退出前关闭旧持久化消费者。
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
