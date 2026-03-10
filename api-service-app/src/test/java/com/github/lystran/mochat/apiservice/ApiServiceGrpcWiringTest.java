package com.github.lystran.mochat.apiservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServiceGrpcWiringTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesInternalGrpcServerBean() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "mochat.message-service.inbound-consumer.enabled", false
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.apiservice.grpc.ApiInternalGrpcService");
            assertTrue(context.containsBean(serviceType));
        }
    }
}
