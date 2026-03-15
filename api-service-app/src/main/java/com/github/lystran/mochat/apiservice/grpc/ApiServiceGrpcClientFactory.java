package com.github.lystran.mochat.apiservice.grpc;

import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import io.grpc.Channel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

/**
 * 创建 `api-service` 调其他内部服务时用到的 gRPC 客户端。
 */
@Factory
public final class ApiServiceGrpcClientFactory {
    /**
     * 创建连到 `message-service` 的阻塞式 gRPC 客户端，给登录后补发离线消息等流程使用。
     */
    @Singleton
    @Requires(property = "grpc.channels.message-service.address")
    MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub(
        @GrpcChannel("message-service") Channel channel
    ) {
        return MessageCommandApiGrpc.newBlockingStub(channel);
    }
}
