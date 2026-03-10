package com.github.lystran.mochat.accessgateway.grpc;

import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import io.grpc.Channel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

@Factory
public final class AccessGatewayGrpcClientFactory {
    @Singleton
    @Requires(property = "mochat.access-gateway.dependencies.api-grpc-enabled", notEquals = "false", defaultValue = "true")
    SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
        @GrpcChannel("api-service") Channel channel
    ) {
        return SessionAuthorityApiGrpc.newBlockingStub(channel);
    }
}
