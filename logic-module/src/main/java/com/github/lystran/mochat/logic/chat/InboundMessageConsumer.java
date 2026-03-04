package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.logic.service.SessionService;
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

@Singleton
@Context
public final class InboundMessageConsumer implements AutoCloseable {
    public static final String DEFAULT_INBOUND_TOPIC = "connection.inbound";

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
        String[] segments = inboundEvent.split("\\|", 3);
        if (segments.length != 3) {
            return;
        }

        MsgType msgType;
        SerializerType serializerType;
        byte[] body;
        try {
            msgType = MsgType.valueOf(segments[0]);
            serializerType = SerializerType.valueOf(segments[1]);
            body = Base64.getDecoder().decode(segments[2]);
        } catch (IllegalArgumentException ignored) {
            return;
        }

        if (serializerType != SerializerType.PROTOBUF) {
            return;
        }

        if (msgType == MsgType.PRIVATE_MESSAGE) {
            consumePrivate(body);
        } else if (msgType == MsgType.GROUP_MESSAGE) {
            consumeGroup(body);
        } else if (msgType == MsgType.CLIENT_RECEIVE_ACK) {
            consumeReceipt(body);
        }
    }

    private void consumePrivate(byte[] body) {
        try {
            var request = Mochat.PrivateMessageReq.parseFrom(body);
            var senderUid = sessionService.resolveUserId(request.getSessionId());
            if (senderUid.isEmpty()) {
                return;
            }

            long peerUidLow = Math.min(senderUid.get(), request.getToUid());
            long peerUidHigh = Math.max(senderUid.get(), request.getToUid());
            messageIngestService.ingest(
                MessageIngestRequest.privateMessage(
                    senderUid.get(),
                    request.getConversationId(),
                    request.getClientMsgId(),
                    peerUidLow,
                    peerUidHigh,
                    Base64.getEncoder().encodeToString(body)
                )
            );
        } catch (InvalidProtocolBufferException ignored) {
        }
    }

    private void consumeGroup(byte[] body) {
        try {
            var request = Mochat.GroupMessageReq.parseFrom(body);
            var senderUid = sessionService.resolveUserId(request.getSessionId());
            if (senderUid.isEmpty()) {
                return;
            }

            messageIngestService.ingest(
                MessageIngestRequest.groupMessage(
                    senderUid.get(),
                    request.getConversationId(),
                    request.getClientMsgId(),
                    request.getGroupId(),
                    Base64.getEncoder().encodeToString(body)
                )
            );
        } catch (InvalidProtocolBufferException ignored) {
        }
    }

    private void consumeReceipt(byte[] body) {
        try {
            var receiptAck = Mochat.ClientReceiveAck.parseFrom(body);
            var receiverUid = sessionService.resolveUserId(receiptAck.getSessionId());
            if (receiverUid.isEmpty()) {
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
}
