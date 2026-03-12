package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.protocol.ErrorCode;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.util.Base64;
import java.util.Objects;

@Singleton
@Context
@Requires(property = "mochat.message-service.inbound-consumer.enabled", value = "true", defaultValue = "true")
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

    public InboundMessageConsumer(
        EventBus eventBus,
        SessionService sessionService,
        MessageIngestService messageIngestService,
        ReceiptService receiptService
    ) {
        this(eventBus, sessionService, messageIngestService, receiptService, DEFAULT_INBOUND_TOPIC);
    }

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

    @PostConstruct
    public void start() {
        subscription = eventBus.subscribe(inboundTopic, this::consume);
    }

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

    private ParsedInboundEvent parseInboundEvent(String inboundEvent) {
        String[] segments = inboundEvent.split("\\|", 4);
        try {
            return switch (segments.length) {
                case 3 -> new ParsedInboundEvent(
                    null,
                    MsgType.valueOf(segments[0]),
                    SerializerType.valueOf(segments[1]),
                    Base64.getDecoder().decode(segments[2])
                );
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

    private void consumePrivate(Long routingUserId, byte[] body) {
        try {
            var request = Mochat.PrivateMessageReq.parseFrom(body);
            var senderUid = sessionService.resolveUserId(request.getSessionId());
            if (senderUid.isEmpty()) {
                emitInvalidSessionIfRouted(routingUserId);
                return;
            }

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

    private void emitInvalidSessionIfRouted(Long routingUserId) {
        if (routingUserId != null) {
            emitErrorResponse(routingUserId, ErrorCode.SESSION_INVALID, SESSION_INVALID_MESSAGE);
        }
    }

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

    private static String encodeWithoutSession(Mochat.PrivateMessageReq request) {
        return Base64.getEncoder().encodeToString(request.toBuilder().clearSessionId().build().toByteArray());
    }

    private static String encodeWithoutSession(Mochat.GroupMessageReq request) {
        return Base64.getEncoder().encodeToString(request.toBuilder().clearSessionId().build().toByteArray());
    }

    private record ParsedInboundEvent(Long routingUserId, MsgType msgType, SerializerType serializerType, byte[] body) {
        private ParsedInboundEvent {
            Objects.requireNonNull(msgType, "msgType");
            Objects.requireNonNull(serializerType, "serializerType");
            Objects.requireNonNull(body, "body");
        }
    }
}
