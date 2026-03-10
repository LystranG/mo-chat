package com.github.lystran.mochat.messageservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessageServiceApplicationContextTest {
    @Test
    void bindsDedicatedMessageServiceConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.message-service.grpc.port", 19192,
            "mochat.message-service.inbound-consumer.enabled", false,
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            assertEquals(19192, context.getRequiredProperty("mochat.message-service.grpc.port", Integer.class));
            assertFalse(context.getRequiredProperty("mochat.message-service.dependencies.gateway-grpc-enabled", Boolean.class));
        }
    }
}
