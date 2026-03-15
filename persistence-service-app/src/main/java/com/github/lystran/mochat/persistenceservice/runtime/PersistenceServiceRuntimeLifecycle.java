package com.github.lystran.mochat.persistenceservice.runtime;

import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;

/**
 * 在应用启动和关闭时管理 RocketMQ 持久化消费线程。
 */
@Singleton
@Context
@Requires(bean = RocketMqPersistenceConsumer.class)
public final class PersistenceServiceRuntimeLifecycle implements AutoCloseable {
    private final RocketMqPersistenceConsumer persistenceConsumer;
    private final boolean consumerEnabled;

    private boolean started;

    /**
     * 收下持久化消费组件和开关配置。
     */
    public PersistenceServiceRuntimeLifecycle(
        RocketMqPersistenceConsumer persistenceConsumer,
        @Property(name = "mochat.persistence-service.queue.consumer-enabled", defaultValue = "true") boolean consumerEnabled
    ) {
        this.persistenceConsumer = persistenceConsumer;
        this.consumerEnabled = consumerEnabled;
    }

    /**
     * 应用启动后按配置决定是否开始从 RocketMQ 拉消息。
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
     * 应用关闭时停掉消费线程，避免服务退出后还继续拉消息。
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

/**
 * 在持久化服务启动时执行 Flyway 数据库迁移。
 */
@Singleton
@Context
@Requires(bean = Flyway.class)
@Requires(property = "mochat.persistence-service.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class PersistenceServiceFlywayMigrationBootstrap {
    private final Flyway flyway;

    /**
     * 收下已经创建好的 Flyway 实例。
     */
    PersistenceServiceFlywayMigrationBootstrap(Flyway flyway) {
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
