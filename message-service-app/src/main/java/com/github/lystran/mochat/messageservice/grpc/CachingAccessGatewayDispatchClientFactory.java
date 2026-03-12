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

@Singleton
@Requires(property = "mochat.message-service.dependencies.gateway-grpc-enabled", notEquals = "false", defaultValue = "true")
public final class CachingAccessGatewayDispatchClientFactory implements AccessGatewayDispatchClientFactory {
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    public AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub createBlockingStub(String targetAddress) {
        return AccessGatewayDispatchApiGrpc.newBlockingStub(channel(targetAddress));
    }

    private ManagedChannel channel(String targetAddress) {
        if (targetAddress == null || targetAddress.isBlank()) {
            throw new IllegalArgumentException("targetAddress must not be blank");
        }
        return channels.computeIfAbsent(targetAddress, address -> ManagedChannelBuilder.forTarget(address)
            .usePlaintext()
            .build());
    }

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
