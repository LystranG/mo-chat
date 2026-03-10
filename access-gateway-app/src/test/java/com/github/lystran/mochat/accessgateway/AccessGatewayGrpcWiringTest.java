package com.github.lystran.mochat.accessgateway;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayGrpcWiringTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesGatewayGrpcServerAndApiClientStub() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.accessgateway.grpc.AccessGatewayInternalGrpcService");
            Class blockingStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc$SessionAuthorityApiBlockingStub"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(blockingStubType));
        }
    }
}
