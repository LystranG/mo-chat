package com.github.lystran.mochat.multimediaservice.runtime;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.event.InProcessEventBus;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

@Factory
public final class MultimediaServiceRuntimeFactory {

    @Singleton
    EventBus eventBus() {
        return new InProcessEventBus();
    }

    @Singleton
    @Requires(property = "mochat.multimedia-service.dependencies.postgres-enabled", notEquals = "false", defaultValue = "true")
    @Requires(missingBeans = DataSource.class)
    DataSource dataSource(
        @Property(name = "mochat.postgres.url") String url,
        @Property(name = "mochat.postgres.username") String username,
        @Property(name = "mochat.postgres.password") String password
    ) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Singleton
    @Requires(bean = DataSource.class)
    @Requires(missingBeans = Flyway.class)
    Flyway flyway(DataSource dataSource, @Property(name = "mochat.flyway.locations") String flywayLocations) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations(flywayLocations)
            .load();
    }
}
