package com.github.lystran.mochat.accessgateway;

import com.github.lystran.mochat.protocol.internal.common.v1.DeliveryEnvelope;
import com.github.lystran.mochat.protocol.internal.common.v1.PrivateDeliveryContent;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import io.grpc.Channel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessGatewayGrpcConnectivityTest {
    @Test
    void deliversRequestsOverMicronautGrpcServerChannel() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "grpc.server.port", 0
        ))) {
            var stub = context.getBean(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
            var validEnvelope = DeliveryEnvelope.newBuilder()
                .setConversationId(10L)
                .setMsgId(11L)
                .setSeq(12L)
                .setServerTimeMs(13L)
                .setFromUid(14L)
                .setPrivateContent(PrivateDeliveryContent.newBuilder()
                    .setToUid(15L)
                    .build())
                .build();

            var delivered = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(21L)
                .setConnectionId("conn-21")
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setExpectedRouteEpoch(4L)
                .setEnvelope(validEnvelope)
                .build());
            var offline = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(21L)
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setExpectedRouteEpoch(4L)
                .setEnvelope(validEnvelope)
                .build());
            var stale = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(21L)
                .setConnectionId("conn-21")
                .setSessionVersion(3L)
                .setExpectedRouteEpoch(4L)
                .setEnvelope(validEnvelope)
                .build());
            var writeFailed = stub.deliverToConnection(DeliverToConnectionRequest.newBuilder()
                .setUserId(21L)
                .setConnectionId("conn-21")
                .setSessionId("active:21:3")
                .setSessionVersion(3L)
                .setExpectedRouteEpoch(4L)
                .build());

            assertEquals(DeliveryStatus.DELIVERY_STATUS_DELIVERED, delivered.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE, offline.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE, stale.getStatus());
            assertEquals(DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED, writeFailed.getStatus());
        }
    }

    @Factory
    static final class TestGrpcClientFactory {
        @Singleton
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub accessGatewayDispatchApiBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME) Channel channel
        ) {
            return AccessGatewayDispatchApiGrpc.newBlockingStub(channel);
        }
    }
}
