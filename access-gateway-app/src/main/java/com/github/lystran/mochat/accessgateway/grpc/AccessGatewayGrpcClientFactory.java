package com.github.lystran.mochat.accessgateway.grpc;

import com.github.lystran.mochat.protocol.internal.api.v1.SessionAuthorityApiGrpc;
import com.github.lystran.mochat.protocol.internal.message.v1.MessageCommandApiGrpc;
import io.grpc.Channel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;

@Factory
@Requires(property = "micronaut.application.name", value = "access-gateway")
/**
 * 组装 access-gateway 依赖的内部 gRPC 阻塞客户端。
 */
public final class AccessGatewayGrpcClientFactory {
    @Singleton
    @Requires(property = "mochat.access-gateway.dependencies.api-grpc-enabled", notEquals = "false", defaultValue = "true")
    /**
     * 创建访问 api-service 的会话校验客户端。
     */
    SessionAuthorityApiGrpc.SessionAuthorityApiBlockingStub sessionAuthorityApiBlockingStub(
        @GrpcChannel("api-service") Channel channel
    ) {
        return SessionAuthorityApiGrpc.newBlockingStub(channel);
    }

    @Singleton
    @Requires(property = "grpc.channels.message-service.address")
    /**
     * 创建访问 message-service 的命令客户端。
     */
    MessageCommandApiGrpc.MessageCommandApiBlockingStub messageCommandApiBlockingStub(
        @GrpcChannel("message-service") Channel channel
    ) {
        return MessageCommandApiGrpc.newBlockingStub(channel);
    }
}
