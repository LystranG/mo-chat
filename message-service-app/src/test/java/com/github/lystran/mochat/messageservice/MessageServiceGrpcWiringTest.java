package com.github.lystran.mochat.messageservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceGrpcWiringTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesCommandGrpcServerAndDependencyClientStubs() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "mochat.message-service.inbound-consumer.enabled", false,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true,
            "grpc.channels.access-gateway.address", "localhost:19093",
            "grpc.channels.access-gateway.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.messageservice.grpc.MessageCommandGrpcService");
            Class apiStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc$SessionAuthorityApiBlockingStub"
            );
            Class gatewayStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc$AccessGatewayDispatchApiBlockingStub"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(apiStubType));
            assertTrue(context.containsBean(gatewayStubType));
        }
    }
}
