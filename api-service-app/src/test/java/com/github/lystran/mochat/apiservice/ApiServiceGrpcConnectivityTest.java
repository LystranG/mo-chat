package com.github.lystran.mochat.apiservice;

import com.github.lystran.mochat.protocol.internal.api.v1.ResolveSessionRequest;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.api.v1.SessionResolutionStatus;
import io.grpc.Channel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiServiceGrpcConnectivityTest {
    @Test
    void resolvesSessionOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0,
            "mochat.message-service.inbound-consumer.enabled", false
        ))) {
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
            var response = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("session-42").build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, response.getStatus());
            assertEquals("session-42", response.getPrincipal().getSessionId());
            assertEquals(1L, response.getPrincipal().getSessionVersion());
        }
    }

    @Factory
    static final class TestGrpcClientFactory {
        @Singleton
        SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return SessionAuthorityApiGrpc.newBlockingStub(channel);
        }
    }
}
