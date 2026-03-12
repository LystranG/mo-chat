package com.github.lystran.mochat.accessgateway;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessGatewayGrpcWiringTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesGatewayGrpcServerAndUpstreamClientStubs() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "mochat.access-gateway.runtime.enabled", false,
            "grpc.server.port", 0,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "grpc.channels.message-service.address", "localhost:19092",
            "grpc.channels.message-service.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.accessgateway.grpc.AccessGatewayInternalGrpcService");
            Class apiBlockingStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc$SessionAuthorityApiBlockingStub"
            );
            Class messageBlockingStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc$MessageCommandApiBlockingStub"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(apiBlockingStubType));
            assertTrue(context.containsBean(messageBlockingStubType));
        }
    }
}
