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
            "grpc.channels.message-service.address", "localhost:19092",
            "grpc.channels.message-service.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.apiservice.grpc.ApiInternalGrpcService");
            Class blockingStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc$MessageCommandApiBlockingStub"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(blockingStubType));
        }
    }
}
