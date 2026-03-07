package com.github.lystran.mochat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationBootstrapTest {
    @Test
    void startupMigrationAttemptsFlywayMigrateWhenEnabled() {
        var properties = new HashMap<>(AppTestSupport.runtimeAssemblyContextProperties());
        properties.put("mochat.flyway.migrate-on-start", true);

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> ApplicationContext.run(properties)
        );

        assertTrue(rootCause(exception).getMessage().contains("Flyway migrate attempted"));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

@Factory
@Requires(property = "spec.name", value = AppTestSupport.RUNTIME_ASSEMBLY_SPEC)
final class FlywayMigrationBootstrapTestFactory {
    @Singleton
    DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> throw new UnsupportedOperationException("Flyway migrate attempted");
                case "getLoginTimeout" -> 0;
                case "setLoginTimeout", "setLogWriter" -> null;
                case "unwrap" -> throw new UnsupportedOperationException("unwrap unsupported");
                case "isWrapperFor" -> false;
                case "getLogWriter" -> null;
                case "getParentLogger" -> java.util.logging.Logger.getGlobal();
                case "toString" -> "FlywayMigrationBootstrapTestDataSource";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Unsupported DataSource method: " + method.getName());
            }
        );
    }
}
