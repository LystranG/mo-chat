package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.logic.chat.GroupMessageDelivery;
import com.github.lystran.mochat.logic.chat.MessageDeliveryStatus;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.logic.chat.PrivateMessageDelivery;
import com.github.lystran.mochat.protocol.internal.common.v1.DeliveryEnvelope;
import com.github.lystran.mochat.protocol.internal.common.v1.GroupDeliveryContent;
import com.github.lystran.mochat.protocol.internal.common.v1.PrivateDeliveryContent;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliverToConnectionRequest;
import com.github.lystran.mochat.protocol.internal.gateway.v1.DeliveryStatus;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GrpcMessageRecipientDispatcher implements MessageRecipientDispatcher {
    private final RedisCommands<String, String> redisCommands;
    private final AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory;
    private final Map<String, String> gatewayTargets;

    public GrpcMessageRecipientDispatcher(
        RedisCommands<String, String> redisCommands,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        Map<String, String> gatewayTargets
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.accessGatewayDispatchClientFactory =
            Objects.requireNonNull(accessGatewayDispatchClientFactory, "accessGatewayDispatchClientFactory");
        this.gatewayTargets = Map.copyOf(Objects.requireNonNull(gatewayTargets, "gatewayTargets"));
    }

    @Override
    public MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        return dispatchToRecipient(
            delivery.recipientUid(),
            buildPrivateEnvelope(delivery)
        );
    }

    @Override
    public Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        DeliveryEnvelope envelope = buildGroupEnvelope(delivery);
        Map<Long, MessageDeliveryStatus> statuses = new LinkedHashMap<>();
        for (Long recipientUid : delivery.recipientUids()) {
            if (recipientUid == null || recipientUid <= 0L || recipientUid == delivery.senderUid()) {
                continue;
            }
            statuses.put(recipientUid, dispatchToRecipient(recipientUid, envelope));
        }
        return statuses;
    }

    private MessageDeliveryStatus dispatchToRecipient(long recipientUid, DeliveryEnvelope envelope) {
        OnlineRoute route = resolveRoute(recipientUid);
        if (route == null) {
            return MessageDeliveryStatus.USER_OFFLINE;
        }
        String targetAddress = gatewayTargets.get(route.gatewayPod());
        if (targetAddress == null || targetAddress.isBlank()) {
            return MessageDeliveryStatus.WRITE_FAILED;
        }

        DeliveryStatus status;
        try {
            status = accessGatewayDispatchClientFactory.createBlockingStub(targetAddress)
                .deliverToConnection(DeliverToConnectionRequest.newBuilder()
                    .setUserId(recipientUid)
                    .setConnectionId(route.connectionId())
                    .setSessionId(route.sessionId())
                    .setSessionVersion(route.sessionVersion())
                    .setExpectedRouteEpoch(route.routeEpoch())
                    .setEnvelope(envelope)
                    .build())
                .getStatus();
        } catch (RuntimeException deliveryFailure) {
            return MessageDeliveryStatus.WRITE_FAILED;
        }
        return switch (status) {
            case DELIVERY_STATUS_DELIVERED -> MessageDeliveryStatus.DELIVERED;
            case DELIVERY_STATUS_ROUTE_STALE -> MessageDeliveryStatus.ROUTE_STALE;
            case DELIVERY_STATUS_USER_OFFLINE -> MessageDeliveryStatus.USER_OFFLINE;
            case DELIVERY_STATUS_WRITE_FAILED, DELIVERY_STATUS_UNSPECIFIED -> MessageDeliveryStatus.WRITE_FAILED;
            default -> MessageDeliveryStatus.WRITE_FAILED;
        };
    }

    private OnlineRoute resolveRoute(long userId) {
        String payload = redisCommands.get("online:user:" + userId);
        if (payload == null || payload.isBlank()) {
            return null;
        }
        Map<String, String> values = deserializeRouteRecord(payload);
        String gatewayPod = values.get("gatewayPod");
        String connectionId = values.get("connectionId");
        String sessionId = values.get("sessionId");
        String sessionVersion = values.get("sessionVersion");
        String routeEpoch = values.get("routeEpoch");
        if (gatewayPod == null || connectionId == null || sessionId == null || sessionVersion == null || routeEpoch == null) {
            return null;
        }
        try {
            return new OnlineRoute(
                gatewayPod,
                connectionId,
                sessionId,
                Long.parseLong(sessionVersion),
                Long.parseLong(routeEpoch)
            );
        } catch (NumberFormatException invalidRoute) {
            return null;
        }
    }

    private static Map<String, String> deserializeRouteRecord(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : payload.split(";")) {
            int separatorIndex = entry.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = entry.substring(0, separatorIndex);
            String value = entry.substring(separatorIndex + 1);
            if ("gatewayPod".equals(key) || "connectionId".equals(key) || "sessionId".equals(key)) {
                values.put(key, decodeString(value));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    private static DeliveryEnvelope buildPrivateEnvelope(PrivateMessageDelivery delivery) {
        try {
            var request = Mochat.PrivateMessageReq.parseFrom(Base64.getDecoder().decode(delivery.payloadBase64()));
            return DeliveryEnvelope.newBuilder()
                .setConversationId(delivery.conversationId())
                .setMsgId(delivery.msgId())
                .setSeq(delivery.seq())
                .setServerTimeMs(delivery.serverTimeMs())
                .setFromUid(delivery.senderUid())
                .setPrivateContent(PrivateDeliveryContent.newBuilder()
                    .setToUid(delivery.recipientUid())
                    .setNonce(request.getNonce())
                    .setCiphertext(request.getCiphertext())
                    .build())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException invalidPayload) {
            throw new IllegalStateException("Unable to build private delivery envelope", invalidPayload);
        }
    }

    private static DeliveryEnvelope buildGroupEnvelope(GroupMessageDelivery delivery) {
        try {
            var request = Mochat.GroupMessageReq.parseFrom(Base64.getDecoder().decode(delivery.payloadBase64()));
            return DeliveryEnvelope.newBuilder()
                .setConversationId(delivery.conversationId())
                .setMsgId(delivery.msgId())
                .setSeq(delivery.seq())
                .setServerTimeMs(delivery.serverTimeMs())
                .setFromUid(delivery.senderUid())
                .setGroupContent(GroupDeliveryContent.newBuilder()
                    .setGroupId(delivery.groupId())
                    .setText(request.getText())
                    .build())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException invalidPayload) {
            throw new IllegalStateException("Unable to build group delivery envelope", invalidPayload);
        }
    }

    private static String decodeString(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private record OnlineRoute(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        long routeEpoch
    ) {
    }
}
