package com.github.lystran.mochat.messageservice.grpc;

import com.github.lystran.mochat.logic.chat.GroupMessageDelivery;
import com.github.lystran.mochat.logic.chat.MessageDeliveryStatus;
import com.github.lystran.mochat.logic.chat.MessageRecipientDispatcher;
import com.github.lystran.mochat.logic.chat.PrivateMessageDelivery;
import com.github.lystran.mochat.runtime.topology.GatewayAddressResolver;
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

/**
 * 根据 Redis 在线路由找到真正持有连接的网关，再通过 gRPC 把消息送过去。
 */
public final class GrpcMessageRecipientDispatcher implements MessageRecipientDispatcher {
    private final RedisCommands<String, String> redisCommands;
    private final AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory;
    private final GatewayAddressResolver gatewayAddressResolver;

    /**
     * 创建一个基于 Redis 路由和 gRPC 的在线投递器。
     */
    public GrpcMessageRecipientDispatcher(
        RedisCommands<String, String> redisCommands,
        AccessGatewayDispatchClientFactory accessGatewayDispatchClientFactory,
        GatewayAddressResolver gatewayAddressResolver
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.accessGatewayDispatchClientFactory =
            Objects.requireNonNull(accessGatewayDispatchClientFactory, "accessGatewayDispatchClientFactory");
        this.gatewayAddressResolver = Objects.requireNonNull(gatewayAddressResolver, "gatewayAddressResolver");
    }

    /**
     * 把一条私聊消息发给接收方当前在线的连接。
     */
    @Override
    public MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        return dispatchToRecipient(
            delivery.recipientUid(),
            buildPrivateEnvelope(delivery)
        );
    }

    /**
     * 把一条群消息分别发给每个群成员当前在线的连接。
     */
    @Override
    public Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        // 同一条群消息先整理成统一内部格式，再复用给每个接收人。
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

    /**
     * 查询接收方当前归哪个网关管，再发起一次定向 gRPC 投递。
     */
    private MessageDeliveryStatus dispatchToRecipient(long recipientUid, DeliveryEnvelope envelope) {
        OnlineRoute route = resolveRoute(recipientUid);
        if (route == null) {
            return MessageDeliveryStatus.USER_OFFLINE;
        }
        // 先把 Redis 里记的网关身份换成真正可访问的 gRPC 地址。
        String targetAddress = gatewayAddressResolver.resolve(route.gatewayPod());
        if (targetAddress == null || targetAddress.isBlank()) {
            return MessageDeliveryStatus.WRITE_FAILED;
        }

        DeliveryStatus status;
        try {
            status = accessGatewayDispatchClientFactory.createBlockingStub(targetAddress)
                .deliverToConnection(DeliverToConnectionRequest.newBuilder()
                    .setUserId(recipientUid)
                    // 这些字段一起用来确认“这条消息是不是发给当前仍然有效的那条连接”。
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

    /**
     * 从 Redis 里读出现在是谁在持有这个用户的连接。
     */
    private OnlineRoute resolveRoute(long userId) {
        String payload = redisCommands.get("online:user:" + userId);
        if (payload == null || payload.isBlank()) {
            return null;
        }
        // Redis 里存的是一串字符串，这里先拆成键值对再取出关键字段。
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

    /**
     * 把 Redis 路由记录那串“分号 + 等号”字符串拆成键值表。
     */
    private static Map<String, String> deserializeRouteRecord(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : payload.split(";")) {
            int separatorIndex = entry.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            /*
            * gatewayPod=YWNjZXNzLWdhdGV3YXktMA==;connectionId=Y29ubi0xMjM0NTY3;sessionId=c2VzLTQy;sessionVersion=7;routeEpoch=3;             22% used
     leaseDurationSeconds=60;leaseExpiresAtEpochMilli=1900000000000 */
            String key = entry.substring(0, separatorIndex);
            String value = entry.substring(separatorIndex + 1);
            if ("gatewayPod".equals(key) || "connectionId".equals(key) || "sessionId".equals(key)) {
                // 这几个字段在 Redis 里做了 Base64 URL 编码，这里要先还原成人能读的字符串。
                values.put(key, decodeString(value));
            } else {
                values.put(key, value);
            }
        }
        return values;
    }

    /**
     * 把私聊业务消息正文整理成发给 gateway 的内部投递消息。
     */
    private static DeliveryEnvelope buildPrivateEnvelope(PrivateMessageDelivery delivery) {
        try {
            var request = Mochat.PrivateMessageReq.parseFrom(Base64.getDecoder().decode(delivery.payloadBase64()));
            // 这里不是原样透传客户端请求，而是只保留 gateway 真正要写给对方的内容。
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

    /**
     * 把群聊业务消息正文整理成发给 gateway 的内部投递消息。
     */
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

    /**
     * 还原 Redis 路由记录里被编码过的字符串字段。
     */
    private static String decodeString(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    /**
     * 表示 Redis 里记录的一条在线路由。
     */
    private record OnlineRoute(
        String gatewayPod,
        String connectionId,
        String sessionId,
        long sessionVersion,
        long routeEpoch
    ) {
    }
}
