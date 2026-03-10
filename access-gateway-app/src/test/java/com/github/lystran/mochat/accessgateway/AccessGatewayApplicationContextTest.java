package com.github.lystran.mochat.accessgateway;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AccessGatewayApplicationContextTest {
    @Test
    void bindsDedicatedGatewayConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.access-gateway.grpc.port", 19093,
            "mochat.access-gateway.tcp.port", 19000,
            "mochat.access-gateway.dependencies.api-grpc-enabled", false
        ))) {
            assertEquals(19093, context.getRequiredProperty("mochat.access-gateway.grpc.port", Integer.class));
            assertEquals(19000, context.getRequiredProperty("mochat.access-gateway.tcp.port", Integer.class));
            assertFalse(context.getRequiredProperty("mochat.access-gateway.dependencies.api-grpc-enabled", Boolean.class));
        }
    }
}
