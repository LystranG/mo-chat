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
public final class CachingAccessGatewayDispatchClientFactory implements AccessGatewayDispatchClientFactory {
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    public AccessGatewayDispatchClient createClient(String targetAddress) {
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            AccessGatewayDispatchApiGrpc.newBlockingStub(channel(targetAddress));
        return stub::kickConnection;
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
