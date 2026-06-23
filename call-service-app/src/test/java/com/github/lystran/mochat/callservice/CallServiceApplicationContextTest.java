package com.github.lystran.mochat.callservice;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CallServiceApplicationContextTest {
    private static final String LOCAL_PROFILE_SPEC = "call-service-local-profile-config";

    @Test
    void localProfileUsesLoopbackInfrastructureAddressesAndLocalLivekitPlaceholders() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "spec.name", LOCAL_PROFILE_SPEC,
                "mochat.call-service.flyway.migrate-on-start", false,
                "mochat.call-service.queue.consumer-enabled", false,
                "mochat.call-service.dependencies.postgres-enabled", false,
                "mochat.call-service.dependencies.redis-enabled", false,
                "mochat.call-service.dependencies.mq-enabled", false
            ))
            .start()) {
            assertEquals("redis://127.0.0.1:6379", context.getRequiredProperty("mochat.redis.uri", String.class));
            assertEquals(
                "jdbc:postgresql://127.0.0.1:5432/mochat",
                context.getRequiredProperty("mochat.postgres.url", String.class)
            );
            assertEquals("127.0.0.1:9876", context.getRequiredProperty("mochat.rocketmq.name-server", String.class));
            assertEquals("ws://127.0.0.1:7880", context.getRequiredProperty("mochat.livekit.url", String.class));
            assertEquals("local-key", context.getRequiredProperty("mochat.livekit.api-key", String.class));
            assertEquals("local-secret", context.getRequiredProperty("mochat.livekit.api-secret", String.class));
        }
    }

    @Factory
    @Requires(property = "spec.name", value = LOCAL_PROFILE_SPEC)
    static final class LocalProfileConfigTestFactory {
        @Singleton
        DataSource dataSource() {
            return new StubDataSource();
        }
    }

    private static final class StubDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLFeatureNotSupportedException("Test DataSource does not open connections");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("Test DataSource does not open connections");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("No parent logger");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException("No wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
