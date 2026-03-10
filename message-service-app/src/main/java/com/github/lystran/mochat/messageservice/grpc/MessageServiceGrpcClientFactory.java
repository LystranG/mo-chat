package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import io.grpc.Channel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

@Factory
public final class MessageServiceGrpcClientFactory {
    @Singleton
    @Requires(property = "mochat.message-service.dependencies.api-grpc-enabled", notEquals = "false", defaultValue = "true")
    SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
        @GrpcChannel("api-service") Channel channel
    ) {
        return SessionAuthorityApiGrpc.newBlockingStub(channel);
    }

    @Singleton
    @Requires(property = "mochat.message-service.dependencies.gateway-grpc-enabled", notEquals = "false", defaultValue = "true")
    AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub accessGatewayDispatchApiBlockingStub(
        @GrpcChannel("access-gateway") Channel channel
    ) {
        return AccessGatewayDispatchApiGrpc.newBlockingStub(channel);
    }
}
