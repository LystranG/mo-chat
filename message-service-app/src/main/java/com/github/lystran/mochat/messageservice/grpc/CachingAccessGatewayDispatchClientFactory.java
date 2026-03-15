package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 按目标地址缓存 access-gateway 的 gRPC 通道，避免每次投递都重复建连。
 */
@Singleton
@Requires(property = "mochat.message-service.dependencies.gateway-grpc-enabled", notEquals = "false", defaultValue = "true")
public final class CachingAccessGatewayDispatchClientFactory implements AccessGatewayDispatchClientFactory {
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    /**
     * 返回一个可直接调用目标网关的阻塞式 gRPC 客户端。
     */
    @Override
    public AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub createBlockingStub(String targetAddress) {
        return AccessGatewayDispatchApiGrpc.newBlockingStub(channel(targetAddress));
    }

    /**
     * 复用同一地址的 gRPC 通道，没有就现场新建。
     */
    private ManagedChannel channel(String targetAddress) {
        if (targetAddress == null || targetAddress.isBlank()) {
            throw new IllegalArgumentException("targetAddress must not be blank");
        }
        return channels.computeIfAbsent(targetAddress, address -> ManagedChannelBuilder.forTarget(address)
            .usePlaintext()
            .build());
    }

    /**
     * 关闭缓存里的所有 gRPC 通道。
     */
    @PreDestroy
    void close() {
        for (ManagedChannel channel : channels.values()) {
            channel.shutdown();
            try {
                channel.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        channels.clear();
    }
}
