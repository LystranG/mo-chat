package com.github.lystran.mochat.messageservice;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageServiceGrpcWiringTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void exposesCommandGrpcServerAndGatewayClientFactory() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "mochat.message-service.inbound-consumer.enabled", false,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Class serviceType = Class.forName("com.github.lystran.mochat.messageservice.grpc.MessageCommandGrpcService");
            Class apiStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc$SessionAuthorityApiBlockingStub"
            );
            Class gatewayStubType = Class.forName(
                "com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc$AccessGatewayDispatchApiBlockingStub"
            );
            Class gatewayFactoryType = Class.forName(
                "com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory"
            );
            assertTrue(context.containsBean(serviceType));
            assertTrue(context.containsBean(apiStubType));
            assertTrue(context.containsBean(gatewayFactoryType));
            assertFalse(context.containsBean(gatewayStubType));
        }
    }

    @Test
    void createsGatewayClientPerTargetAddress() throws Exception {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "mochat.message-service.inbound-consumer.enabled", false,
            "grpc.channels.api-service.address", "localhost:19091",
            "grpc.channels.api-service.plaintext", true
        ))) {
            Object factory = context.getBean(Class.forName(
                "com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory"
            ));
            Object first = factory.getClass().getMethod("createBlockingStub", String.class).invoke(factory, "gateway-a:19093");
            Object second = factory.getClass().getMethod("createBlockingStub", String.class).invoke(factory, "gateway-b:19093");
            Object firstChannel = first.getClass().getMethod("getChannel").invoke(first);
            Object secondChannel = second.getClass().getMethod("getChannel").invoke(second);

            assertNotSame(firstChannel, secondChannel);
        }
    }
}
