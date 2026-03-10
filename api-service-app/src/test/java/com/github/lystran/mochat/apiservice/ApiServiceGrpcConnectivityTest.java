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
            "grpc.server.port", 0
        ))) {
            var stub = context.getBean(SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub.class);
            var active = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("active:42:7").build());
            var expired = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("expired:42:7").build());
            var replaced = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("replaced:42:8").build());
            var invalid = stub.resolveSession(ResolveSessionRequest.newBuilder().setSessionId("session-42").build());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_ACTIVE, active.getStatus());
            assertEquals(42L, active.getPrincipal().getUserId());
            assertEquals(7L, active.getPrincipal().getSessionVersion());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_EXPIRED, expired.getStatus());
            assertEquals(42L, expired.getPrincipal().getUserId());
            assertEquals(7L, expired.getPrincipal().getSessionVersion());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_REPLACED, replaced.getStatus());
            assertEquals(42L, replaced.getPrincipal().getUserId());
            assertEquals(8L, replaced.getPrincipal().getSessionVersion());

            assertEquals(SessionResolutionStatus.SESSION_RESOLUTION_STATUS_INVALID, invalid.getStatus());
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
