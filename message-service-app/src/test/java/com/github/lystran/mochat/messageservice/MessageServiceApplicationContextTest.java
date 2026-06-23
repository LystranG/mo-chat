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
            "mochat.message-service.dependencies.gateway-grpc-enabled", false
        ))) {
            assertEquals(19192, context.getRequiredProperty("mochat.message-service.grpc.port", Integer.class));
            assertFalse(context.getRequiredProperty("mochat.message-service.inbound-consumer.enabled", Boolean.class));
            assertFalse(context.getRequiredProperty("mochat.message-service.dependencies.gateway-grpc-enabled", Boolean.class));
        }
    }

    @Test
    void localProfileUsesLoopbackApiAndStaticGatewayTarget() {
        try (ApplicationContext context = ApplicationContext.builder()
            .environments("local")
            .properties(Map.of(
                "mochat.message-service.dependencies.api-grpc-enabled", false,
                "mochat.message-service.dependencies.gateway-grpc-enabled", false,
                "mochat.message-service.dependencies.mq-enabled", false,
                "mochat.message-service.dependencies.redis-enabled", false
            ))
            .start()) {
            assertEquals(
                "127.0.0.1:19091",
                context.getRequiredProperty("grpc.channels.api-service.address", String.class)
            );
            assertEquals(
                "STATIC_MAP",
                context.getRequiredProperty("mochat.runtime.gateway.discovery-mode", String.class)
            );
            assertEquals(
                "127.0.0.1:19093",
                context.getRequiredProperty("mochat.message-service.route.gateway-targets.gateway-a", String.class)
            );
        }
    }
}
