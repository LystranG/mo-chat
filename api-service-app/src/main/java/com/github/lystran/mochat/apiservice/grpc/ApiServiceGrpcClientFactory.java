package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import io.grpc.Channel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

@Factory
public final class ApiServiceGrpcClientFactory {
    @Singleton
    @Requires(property = "grpc.channels.message-service.address")
    MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub(
        @GrpcChannel("message-service") Channel channel
    ) {
        return MessageCommandApiGrpc.newBlockingStub(channel);
    }
}
