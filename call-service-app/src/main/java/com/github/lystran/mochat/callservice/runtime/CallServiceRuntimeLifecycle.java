package com.github.lystran.mochat.callservice.runtime;

import com.github.lystran.mochat.call.mq.CallOfflineNotificationMqConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.exception.MQClientException;
import org.flywaydb.core.Flyway;

/**
 * 在 call-service 启动和关闭时管理 Flyway 迁移和离线通知 MQ 消费者。
 */
@Singleton
@Context
@Requires(bean = CallOfflineNotificationMqConsumer.class)
public final class CallServiceRuntimeLifecycle implements AutoCloseable {
    private final CallOfflineNotificationMqConsumer offlineNotificationConsumer;
    private final boolean consumerEnabled;

    private boolean started;


    public CallServiceRuntimeLifecycle(
        CallOfflineNotificationMqConsumer offlineNotificationConsumer,
        @Property(name = "mochat.call-service.queue.consumer-enabled", defaultValue = "true") boolean consumerEnabled
    ) {
        this.offlineNotificationConsumer = offlineNotificationConsumer;
        this.consumerEnabled = consumerEnabled;
    }

    @PostConstruct
    void start() {
        if (!consumerEnabled) {
            return;
        }
        try {
            offlineNotificationConsumer.start();
            started = true;
        } catch (MQClientException exception) {
            throw new IllegalStateException("Failed to start call offline notification consumer", exception);
        }
    }


    @PreDestroy
    @Override
    public void close() {
        if (!started) {
            return;
        }
        offlineNotificationConsumer.stop();
        started = false;
    }
}

/**
 * 在 call-service 启动时执行 Flyway 数据库迁移。
 */
@Singleton
@Context
@Requires(bean = Flyway.class)
@Requires(property = "mochat.call-service.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class CallServiceFlywayMigrationBootstrap {
    private final Flyway flyway;

    /**
     * 收下已经创建好的 Flyway 实例。
     */
    CallServiceFlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    /**
     * 执行数据库迁移，把表结构推进到当前版本。
     */
    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
