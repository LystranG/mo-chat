package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.util.Base64;
import java.util.Objects;

/**
 * 接住连接层交上来的消息，再按消息类型分别交给消息发送或送达确认流程。
 */
@Singleton
@Context
public final class InboundMessageConsumer implements AutoCloseable {
    public static final String DEFAULT_INBOUND_TOPIC = "connection.inbound";
    private static final String SESSION_INVALID_MESSAGE = "session invalid";

    private final EventBus eventBus;
    private final SessionService sessionService;
    private final MessageIngestService messageIngestService;
    private final ReceiptService receiptService;
    private final String inboundTopic;
    private AutoCloseable subscription = () -> {
    };

    /**
     * 使用默认接收事件通道构造消息消费者。
     */
    public InboundMessageConsumer(
        EventBus eventBus,
        SessionService sessionService,
        MessageIngestService messageIngestService,
        ReceiptService receiptService
    ) {
        this(eventBus, sessionService, messageIngestService, receiptService, DEFAULT_INBOUND_TOPIC);
    }

    /**
     * 使用指定接收事件通道构造消息消费者。
     */
    public InboundMessageConsumer(
        EventBus eventBus,
        SessionService sessionService,
        MessageIngestService messageIngestService,
        ReceiptService receiptService,
        String inboundTopic
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService");
        this.messageIngestService = Objects.requireNonNull(messageIngestService, "messageIngestService");
        this.receiptService = Objects.requireNonNull(receiptService, "receiptService");
        this.inboundTopic = Objects.requireNonNull(inboundTopic, "inboundTopic");
    }

    /**
     * 启动时开始监听连接层转发过来的消息。
     */
    @PostConstruct
    public void start() {
        subscription = eventBus.subscribe(inboundTopic, this::consume);
    }

    /**
     * 停止监听连接层转发过来的消息。
     */
    @PreDestroy
    @Override
    public void close() {
        try {
            subscription.close();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to close inbound subscription", exception);
        }
    }

    /**
     * 解析一条连接层交上来的消息，并分发到对应处理分支。
     */
    private void consume(String inboundEvent) {
        ParsedInboundEvent parsedInboundEvent = parseInboundEvent(inboundEvent);
        if (parsedInboundEvent == null || parsedInboundEvent.serializerType() != SerializerType.PROTOBUF) {
            return;
        }

        if (parsedInboundEvent.msgType() == MsgType.PRIVATE_MESSAGE) {
            consumePrivate(parsedInboundEvent.routingUserId(), parsedInboundEvent.body());
        } else if (parsedInboundEvent.msgType() == MsgType.GROUP_MESSAGE) {
            consumeGroup(parsedInboundEvent.routingUserId(), parsedInboundEvent.body());
        } else if (parsedInboundEvent.msgType() == MsgType.CLIENT_RECEIVE_ACK) {
            consumeReceipt(parsedInboundEvent.routingUserId(), parsedInboundEvent.body());
        }
    }

    /**
     * 把连接层用竖线拼起来的字符串还原成结构化消息。
     */
    private ParsedInboundEvent parseInboundEvent(String inboundEvent) {
        String[] segments = inboundEvent.split("\\|", 4);
        try {
            return switch (segments.length) {
                // 没带 routingUserId，说明这条消息不是按某个登录用户直接路由过来的。
                case 3 -> new ParsedInboundEvent(
                    null,
                    MsgType.valueOf(segments[0]),
                    SerializerType.valueOf(segments[1]),
                    Base64.getDecoder().decode(segments[2])
                );
                // 带上 routingUserId，后面如果 session 无效，就能准确把错误回给对应用户。
                case 4 -> new ParsedInboundEvent(
                    Long.parseLong(segments[0]),
                    MsgType.valueOf(segments[1]),
                    SerializerType.valueOf(segments[2]),
                    Base64.getDecoder().decode(segments[3])
                );
                default -> null;
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 处理私聊消息：先验 session，再交给消息主流程。
     */
    private void consumePrivate(Long routingUserId, byte[] body) {
        try {
            var request = Mochat.PrivateMessageReq.parseFrom(body);
            var senderUid = sessionService.resolveUserId(request.getSessionId());
            if (senderUid.isEmpty()) {
                emitInvalidSessionIfRouted(routingUserId);
                return;
            }

            // 私聊双方始终按从小到大传下去，保证后面查会话和查关系时用的是同一组顺序。
            long peerUidLow = Math.min(senderUid.get(), request.getToUid());
            long peerUidHigh = Math.max(senderUid.get(), request.getToUid());
            try {
                messageIngestService.ingest(
                    MessageIngestRequest.privateMessage(
                        senderUid.get(),
                        request.getConversationId(),
                        request.getClientMsgId(),
                        peerUidLow,
                        peerUidHigh,
                        encodeWithoutSession(request)
                    )
                );
            } catch (MessageRejectException rejection) {
                emitErrorResponse(senderUid.get(), rejection.errorCode(), rejection.getMessage());
            }
        } catch (InvalidProtocolBufferException ignored) {
        }
    }

    /**
     * 处理群聊消息：先验 session，再交给消息主流程。
     */
    private void consumeGroup(Long routingUserId, byte[] body) {
        try {
            var request = Mochat.GroupMessageReq.parseFrom(body);
            var senderUid = sessionService.resolveUserId(request.getSessionId());
            if (senderUid.isEmpty()) {
                emitInvalidSessionIfRouted(routingUserId);
                return;
            }

            try {
                messageIngestService.ingest(
                    MessageIngestRequest.groupMessage(
                        senderUid.get(),
                        request.getConversationId(),
                        request.getClientMsgId(),
                        request.getGroupId(),
                        encodeWithoutSession(request)
                    )
                );
            } catch (MessageRejectException rejection) {
                emitErrorResponse(senderUid.get(), rejection.errorCode(), rejection.getMessage());
            }
        } catch (InvalidProtocolBufferException ignored) {
        }
    }

    /**
     * 处理客户端上报的“我已经收到哪条消息”。
     */
    private void consumeReceipt(Long routingUserId, byte[] body) {
        try {
            var receiptAck = Mochat.ClientReceiveAck.parseFrom(body);
            var receiverUid = sessionService.resolveUserId(receiptAck.getSessionId());
            if (receiverUid.isEmpty()) {
                emitInvalidSessionIfRouted(routingUserId);
                return;
            }

            receiptService.handleClientReceiveAck(
                receiverUid.get(),
                receiptAck.getConversationId(),
                receiptAck.getLatestReceivedSeq()
            );
        } catch (InvalidProtocolBufferException ignored) {
        }
    }

    /**
     * 在知道目标用户是谁时，把 session 无效错误回给他。
     */
    private void emitInvalidSessionIfRouted(Long routingUserId) {
        if (routingUserId != null) {
            emitErrorResponse(routingUserId, ErrorCode.SESSION_INVALID, SESSION_INVALID_MESSAGE);
        }
    }

    /**
     * 把标准错误响应发回连接层。
     */
    private void emitErrorResponse(long userId, ErrorCode errorCode, String message) {
        byte[] payload = Mochat.ErrorResponse.newBuilder()
            .setErrorCode(errorCode.code())
            .setMessage(message)
            .build()
            .toByteArray();
        String outboundEvent = userId
            + "|"
            + MsgType.ERROR_RESPONSE.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(payload);
        eventBus.publish(MessageIngestService.DEFAULT_OUTBOUND_TOPIC, outboundEvent);
    }

    /**
     * 去掉私聊请求里的临时 sessionId，再重新编码，供后面的存库和转发复用。
     */
    private static String encodeWithoutSession(Mochat.PrivateMessageReq request) {
        // sessionId 只用于当前这次鉴权，后面的存库、查历史和离线补发都不该带着它。
        return Base64.getEncoder().encodeToString(request.toBuilder().clearSessionId().build().toByteArray());
    }

    /**
     * 去掉群聊请求里的临时 sessionId，再重新编码，供后面的存库和转发复用。
     */
    private static String encodeWithoutSession(Mochat.GroupMessageReq request) {
        // 群消息也一样，进入后面的链路前先去掉登录态字段，避免把它写进消息内容里。
        return Base64.getEncoder().encodeToString(request.toBuilder().clearSessionId().build().toByteArray());
    }

    /**
     * 表示已经从字符串拆出来的一条结构化消息。
     */
    private record ParsedInboundEvent(Long routingUserId, MsgType msgType, SerializerType serializerType, byte[] body) {
        /**
         * 校验这条结构化消息的核心字段不为空。
         */
        private ParsedInboundEvent {
            Objects.requireNonNull(msgType, "msgType");
            Objects.requireNonNull(serializerType, "serializerType");
            Objects.requireNonNull(body, "body");
        }
    }
}
