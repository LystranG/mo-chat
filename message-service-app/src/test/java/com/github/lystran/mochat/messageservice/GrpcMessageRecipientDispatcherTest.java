package com.github.lystran.mochat.messageservice;

import com.github.lystran.mochat.logic.chat.GroupMessageDelivery;
import com.github.lystran.mochat.logic.chat.MessageDeliveryStatus;
import com.github.lystran.mochat.logic.chat.PrivateMessageDelivery;
import com.github.lystran.mochat.messageservice.grpc.AccessGatewayDispatchClientFactory;
import com.github.lystran.mochat.messageservice.grpc.GrpcMessageRecipientDispatcher;
import com.github.lystran.mochat.runtime.topology.GatewayAddressResolver;
import com.github.lystran.mochat.protocol.internal.gateway.v1.AccessGatewayDispatchApiGrpc;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionResponse;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GrpcMessageRecipientDispatcherTest {
    @Test
    void privateDeliveryUsesResolvedOnlineRouteAndOwningGatewayStub() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(stub);
        when(stub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_DELIVERED)
            .build());

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-a", "gateway-a:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.DELIVERED, status);
        org.mockito.ArgumentCaptor<DeliverToConnectionRequest> requestCaptor =
            org.mockito.ArgumentCaptor.forClass(DeliverToConnectionRequest.class);
        verify(stub).deliverToConnection(requestCaptor.capture());
        DeliverToConnectionRequest request = requestCaptor.getValue();
        assertEquals(88L, request.getUserId());
        assertEquals("conn-1", request.getConnectionId());
        assertEquals("session-1", request.getSessionId());
        assertEquals(7L, request.getSessionVersion());
        assertEquals(11L, request.getExpectedRouteEpoch());
        assertEquals(55L, request.getEnvelope().getConversationId());
        assertEquals(9_001L, request.getEnvelope().getMsgId());
        assertEquals(77L, request.getEnvelope().getSeq());
        assertEquals(11L, request.getEnvelope().getFromUid());
        assertEquals(88L, request.getEnvelope().getPrivateContent().getToUid());
    }

    @Test
    void missingOnlineRouteIsReportedAsUserOfflineWithoutGatewayCall() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(null);
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-a", "gateway-a:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.USER_OFFLINE, status);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void gatewayRouteStaleResponseIsMappedWithoutFallback() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(stub);
        when(stub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_ROUTE_STALE)
            .build());

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-a", "gateway-a:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.ROUTE_STALE, status);
        verify(stub).deliverToConnection(any());
    }

    @Test
    void gatewayUserOfflineResponseIsMappedWithoutFallback() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(stub);
        when(stub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE)
            .build());

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-a", "gateway-a:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.USER_OFFLINE, status);
        verify(stub).deliverToConnection(any());
    }

    @Test
    void gatewayWriteFailedResponseIsMappedWithoutFallback() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(stub);
        when(stub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_WRITE_FAILED)
            .build());

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-a", "gateway-a:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.WRITE_FAILED, status);
        verify(stub).deliverToConnection(any());
    }

    @Test
    void missingGatewayTargetAddressIsReportedAsWriteFailedWithoutGatewayCall() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of("gateway-b", "gateway-b:19093"))
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.WRITE_FAILED, status);
        verifyNoInteractions(clientFactory);
    }

    @Test
    void groupDeliveryDispatchesPerRecipientRouteAndSkipsSenderAndInvalidRecipients() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        when(redisCommands.get("online:user:99")).thenReturn(routeRecord("gateway-b", "conn-2", "session-2", 8L, 12L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub gatewayAStub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub gatewayBStub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(gatewayAStub);
        when(clientFactory.createBlockingStub("gateway-b:19093")).thenReturn(gatewayBStub);
        when(gatewayAStub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_DELIVERED)
            .build());
        when(gatewayBStub.deliverToConnection(any())).thenReturn(DeliverToConnectionResponse.newBuilder()
            .setStatus(DeliveryStatus.DELIVERY_STATUS_USER_OFFLINE)
            .build());

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            staticResolver(Map.of(
                "gateway-a", "gateway-a:19093",
                "gateway-b", "gateway-b:19093"
            ))
        );

        Map<Long, MessageDeliveryStatus> statuses = dispatcher.dispatchGroup(new GroupMessageDelivery(
            66L,
            9_101L,
            88L,
            1_710_000_000_111L,
            11L,
            701L,
            encodedGroupPayload("hello-group-701"),
            Arrays.asList(11L, 88L, null, 0L, 99L)
        ));

        assertEquals(Map.of(
            88L, MessageDeliveryStatus.DELIVERED,
            99L, MessageDeliveryStatus.USER_OFFLINE
        ), statuses);
        ArgumentCaptor<DeliverToConnectionRequest> gatewayARequestCaptor =
            ArgumentCaptor.forClass(DeliverToConnectionRequest.class);
        ArgumentCaptor<DeliverToConnectionRequest> gatewayBRequestCaptor =
            ArgumentCaptor.forClass(DeliverToConnectionRequest.class);
        verify(gatewayAStub).deliverToConnection(gatewayARequestCaptor.capture());
        verify(gatewayBStub).deliverToConnection(gatewayBRequestCaptor.capture());
        DeliverToConnectionRequest gatewayARequest = gatewayARequestCaptor.getValue();
        DeliverToConnectionRequest gatewayBRequest = gatewayBRequestCaptor.getValue();
        assertEquals(88L, gatewayARequest.getUserId());
        assertEquals("conn-1", gatewayARequest.getConnectionId());
        assertEquals(701L, gatewayARequest.getEnvelope().getGroupContent().getGroupId());
        assertEquals("hello-group-701", gatewayARequest.getEnvelope().getGroupContent().getText());
        assertEquals(99L, gatewayBRequest.getUserId());
        assertEquals("conn-2", gatewayBRequest.getConnectionId());
        assertEquals(701L, gatewayBRequest.getEnvelope().getGroupContent().getGroupId());
        assertEquals("hello-group-701", gatewayBRequest.getEnvelope().getGroupContent().getText());
    }

    @Test
    void gatewayRpcFailureIsNormalizedToWriteFailed() {
        io.lettuce.core.api.sync.RedisCommands<String, String> redisCommands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(redisCommands.get("online:user:88")).thenReturn(routeRecord("gateway-a", "conn-1", "session-1", 7L, 11L));
        AccessGatewayDispatchClientFactory clientFactory = mock(AccessGatewayDispatchClientFactory.class);
        AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub stub =
            mock(AccessGatewayDispatchApiGrpc.AccessGatewayDispatchApiBlockingStub.class);
        when(clientFactory.createBlockingStub("gateway-a:19093")).thenReturn(stub);
        when(stub.deliverToConnection(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        GrpcMessageRecipientDispatcher dispatcher = new GrpcMessageRecipientDispatcher(
            redisCommands,
            clientFactory,
            gatewayPod -> "dns:///" + gatewayPod + ".access-gateway-headless.chat.svc.cluster.local:19093"
        );

        MessageDeliveryStatus status = dispatcher.dispatchPrivate(new PrivateMessageDelivery(
            55L,
            9_001L,
            77L,
            1_710_000_000_000L,
            11L,
            88L,
            encodedPrivatePayload(88L)
        ));

        assertEquals(MessageDeliveryStatus.WRITE_FAILED, status);
    }

    private static GatewayAddressResolver staticResolver(Map<String, String> gatewayTargets) {
        return gatewayTargets::get;
    }

    private static String routeRecord(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        long routeEpoch
    ) {
        return String.join(";",
            "gatewayPod=" + encode(gatewayPod),
            "connectionId=" + encode(connectionId),
            "sessionId=" + encode(sessionId),
            "sessionVersion=" + sessionVersion,
            "routeEpoch=" + routeEpoch,
            "leaseDurationSeconds=60",
            "leaseExpiresAtEpochMilli=1710000000000"
        );
    }

    private static String encodedPrivatePayload(long toUid) {
        return Base64.getEncoder().encodeToString(
            com.github.lystran.mochat.protocol.proto.Mochat.PrivateMessageReq.newBuilder()
                .setConversationId(55L)
                .setClientMsgId(1001L)
                .setToUid(toUid)
                .setNonce(com.google.protobuf.ByteString.copyFrom(new byte[12]))
                .setCiphertext(com.google.protobuf.ByteString.copyFromUtf8("ciphertext"))
                .build()
                .toByteArray()
        );
    }

    private static String encodedGroupPayload(String text) {
        return Base64.getEncoder().encodeToString(
            com.github.lystran.mochat.protocol.proto.Mochat.GroupMessageReq.newBuilder()
                .setConversationId(66L)
                .setClientMsgId(2002L)
                .setGroupId(701L)
                .setText(text)
                .build()
                .toByteArray()
        );
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
