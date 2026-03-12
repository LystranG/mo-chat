package com.github.lystran.mochat.apiservice;

import io.lettuce.core.RedisClient;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServiceApplicationContextTest {
    @Test
    void bindsDedicatedApiServiceConfigurationNamespace() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.api-service.grpc.port", 19191,
            "mochat.api-service.dependencies.postgres-enabled", false
        ))) {
            assertEquals(19191, context.getRequiredProperty("mochat.api-service.grpc.port", Integer.class));
            assertTrue(context.getRequiredProperty("mochat.api-service.dependencies.redis-enabled", Boolean.class));
            assertFalse(context.getRequiredProperty("mochat.api-service.dependencies.postgres-enabled", Boolean.class));
            assertFalse(context.getRequiredProperty("mochat.message-service.inbound-consumer.enabled", Boolean.class));
            assertEquals("redis://127.0.0.1:6379", context.getRequiredProperty("mochat.redis.uri", String.class));
            assertTrue(context.containsBean(RedisClient.class));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dedicatedRuntimeDoesNotMaterializeReceiptStateFallbackOwner() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.api-service.dependencies.postgres-enabled", false
        ))) {
            Class receiptStateStoreType = Class.forName("com.github.lystran.mochat.logic.chat.ReceiptConversationStateStore");
            Class inMemoryReceiptStateStoreType = Class.forName("com.github.lystran.mochat.logic.chat.InMemoryReceiptConversationStateStore");
            Class receiptServiceType = Class.forName("com.github.lystran.mochat.logic.chat.ReceiptService");

            assertFalse(context.containsBean(receiptStateStoreType));
            assertFalse(context.containsBean(inMemoryReceiptStateStoreType));
            assertFalse(context.containsBean(receiptServiceType));
        }
    }
}
