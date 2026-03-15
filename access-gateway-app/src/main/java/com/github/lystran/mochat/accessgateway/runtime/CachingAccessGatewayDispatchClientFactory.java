package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
@Requires(property = "micronaut.application.name", value = "access-gateway")
/**
 * 按目标地址缓存 gRPC Channel，避免每次踢旧连接都重新建一条连接。
 */
public final class CachingAccessGatewayDispatchClientFactory implements AccessGatewayDispatchClientFactory {
    /**
     * 每个目标网关地址共用一条底层 gRPC 连接，减少跨网关调度开销。
     */
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    /**
     * 返回一个发往指定目标网关的踢连接客户端。
     */
    public AccessGatewayDispatchClient createClient(String targetAddress) {
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            AccessGatewayDispatchApiGrpc.newBlockingStub(channel(targetAddress));
        return stub::kickConnection;
    }

    /**
     * 懒加载并缓存目标地址对应的底层 gRPC Channel。
     */
    private ManagedChannel channel(String targetAddress) {
        if (targetAddress == null || targetAddress.isBlank()) {
            throw new IllegalArgumentException("targetAddress must not be blank");
        }
        return channels.computeIfAbsent(targetAddress, address -> ManagedChannelBuilder.forTarget(address)
            .usePlaintext()
            .build());
    }

    @PreDestroy
    /**
     * 关闭所有缓存的 gRPC Channel。
     */
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
