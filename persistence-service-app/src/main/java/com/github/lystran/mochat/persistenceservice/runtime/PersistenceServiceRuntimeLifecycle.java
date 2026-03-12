package com.github.lystran.mochat.persistenceservice.runtime;

import com.github.lystran.mochat.persistence.RocketMqPersistenceConsumer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;

@Singleton
@Context
@Requires(bean = RocketMqPersistenceConsumer.class)
public final class PersistenceServiceRuntimeLifecycle implements AutoCloseable {
    private final RocketMqPersistenceConsumer persistenceConsumer;
    private final boolean consumerEnabled;

    private boolean started;

    public PersistenceServiceRuntimeLifecycle(
        RocketMqPersistenceConsumer persistenceConsumer,
        @Property(name = "mochat.persistence-service.queue.consumer-enabled", defaultValue = "true") boolean consumerEnabled
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

@Singleton
@Context
@Requires(bean = Flyway.class)
@Requires(property = "mochat.persistence-service.flyway.migrate-on-start", value = "true", defaultValue = "true")
final class PersistenceServiceFlywayMigrationBootstrap {
    private final Flyway flyway;

    PersistenceServiceFlywayMigrationBootstrap(Flyway flyway) {
        this.flyway = flyway;
    }

    @PostConstruct
    void migrate() {
        flyway.migrate();
    }
}
