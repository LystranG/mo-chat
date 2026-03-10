package com.github.lystran.mochat.persistenceservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PersistenceServiceApplicationContextTest {
    @Test
    void bindsDedicatedPersistenceServiceConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.persistence-service.queue.consumer-enabled", false,
            "mochat.persistence-service.dependencies.mq-enabled", false
        ))) {
            assertFalse(context.getRequiredProperty("mochat.persistence-service.queue.consumer-enabled", Boolean.class));
            assertFalse(context.getRequiredProperty("mochat.persistence-service.dependencies.mq-enabled", Boolean.class));
        }
    }
}
