package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import com.github.lystran.mochat.logic.mq.RocketMqProducer;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import com.google.protobuf.InvalidProtocolBufferException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

@Singleton
public class MessageIngestService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final ConversationLock conversationLock;
    private final IdempotencyStore idempotencyStore;
    private final ConversationSeqGenerator conversationSeqGenerator;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final RocketMqProducer rocketMqProducer;
    private final EventBus eventBus;
    private final ReceiptConversationStateStore receiptConversationStateStore;
    private final String outboundTopic;

    @Inject
    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus,
        ReceiptConversationStateStore receiptConversationStateStore
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            Clock.systemUTC(),
            rocketMqProducer,
            eventBus,
            receiptConversationStateStore,
            DEFAULT_OUTBOUND_TOPIC
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            clock,
            rocketMqProducer,
            eventBus,
            new InMemoryReceiptConversationStateStore(),
            DEFAULT_OUTBOUND_TOPIC
        );
    }

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        Clock clock,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus,
        ReceiptConversationStateStore receiptConversationStateStore,
        String outboundTopic
    ) {
        this.conversationLock = Objects.requireNonNull(conversationLock, "conversationLock");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.conversationSeqGenerator = Objects.requireNonNull(conversationSeqGenerator, "conversationSeqGenerator");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rocketMqProducer = Objects.requireNonNull(rocketMqProducer, "rocketMqProducer");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.receiptConversationStateStore = Objects.requireNonNull(receiptConversationStateStore, "receiptConversationStateStore");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    public MessageIngestResult ingest(MessageIngestRequest request) {
        Objects.requireNonNull(request, "request");

        AutoCloseable lockHandle = conversationLock.acquire(request.conversationId());
        try {
            var storedResult = idempotencyStore.find(request.senderUid(), request.clientMsgId());
            if (storedResult.isPresent()) {
                var existing = storedResult.get();
                long serverTimeMs = clock.millis();
                emitSendAck(request.senderUid(), request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
                return new MessageIngestResult(request.clientMsgId(), existing.msgId(), existing.seq(), serverTimeMs);
            }

            long seq = conversationSeqGenerator.next(request.conversationId());
            trackPrivateConversation(request, seq);
            long msgId = idGenerator.nextId();
            long serverTimeMs = clock.millis();
            MessageIngestEnvelope envelope = new MessageIngestEnvelope(
                msgId,
                request.conversationId(),
                seq,
                request.clientMsgId(),
                request.kind(),
                request.senderUid(),
                request.peerUidLow(),
                request.peerUidHigh(),
                request.groupId(),
                serverTimeMs,
                request.payloadBase64()
            );

            boolean published = rocketMqProducer.publishOrdered(envelope, Long.toString(request.conversationId()));
            if (!published) {
                throw new IllegalStateException("Ordered publish failed for conversationId=" + request.conversationId());
            }

            idempotencyStore.storeIfAbsent(request.senderUid(), request.clientMsgId(), msgId, seq);
            emitSendAck(request.senderUid(), request.clientMsgId(), msgId, seq, serverTimeMs);
            emitPrivateDelivery(request, msgId, seq, serverTimeMs);
            return new MessageIngestResult(request.clientMsgId(), msgId, seq, serverTimeMs);
        } finally {
            closeLock(lockHandle);
        }
    }

    private void trackPrivateConversation(MessageIngestRequest request, long seq) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        if (request.peerUidLow() == null || request.peerUidHigh() == null) {
            throw new IllegalStateException("private message requires both peer uids");
        }

        receiptConversationStateStore.upsertPrivateConversation(
            request.conversationId(),
            request.peerUidLow(),
            request.peerUidHigh(),
            seq
        );
    }

    private void emitSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();

        emitOutboundEvent(senderUid, MsgType.SEND_ACK, sendAck.toByteArray());
    }

    private void emitPrivateDelivery(MessageIngestRequest request, long msgId, long seq, long serverTimeMs) {
        if (!MessageIngestRequest.KIND_PRIVATE.equals(request.kind())) {
            return;
        }

        long recipientUid = resolvePrivateRecipientUid(request.senderUid(), request.peerUidLow(), request.peerUidHigh());
        var delivery = Mochat.ChatMessageDelivery.newBuilder()
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .setConversationId(request.conversationId())
            .setFromUid(request.senderUid())
            .setPrivatePayload(buildPrivatePayload(request.payloadBase64(), recipientUid))
            .build();

        emitOutboundEvent(recipientUid, MsgType.PRIVATE_MESSAGE, delivery.toByteArray());
    }

    private Mochat.PrivatePayload buildPrivatePayload(String requestPayloadBase64, long recipientUid) {
        try {
            byte[] body = Base64.getDecoder().decode(requestPayloadBase64);
            var privateRequest = Mochat.PrivateMessageReq.parseFrom(body);
            return Mochat.PrivatePayload.newBuilder()
                .setToUid(recipientUid)
                .setNonce(privateRequest.getNonce())
                .setCiphertext(privateRequest.getCiphertext())
                .build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException parseFailure) {
            throw new IllegalStateException("Unable to build private delivery payload", parseFailure);
        }
    }

    private long resolvePrivateRecipientUid(long senderUid, Long peerUidLow, Long peerUidHigh) {
        if (peerUidLow == null || peerUidHigh == null) {
            throw new IllegalStateException("private message requires both peer uids");
        }

        if (senderUid == peerUidLow) {
            return peerUidHigh;
        }
        if (senderUid == peerUidHigh) {
            return peerUidLow;
        }

        throw new IllegalStateException("sender must be one of private conversation peers");
    }

    private void emitOutboundEvent(long userId, MsgType msgType, byte[] payloadBytes) {
        String encodedPayload = Base64.getEncoder().encodeToString(payloadBytes);
        String outboundEvent = userId
            + "|"
            + msgType.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + encodedPayload;
        eventBus.publish(outboundTopic, outboundEvent);
    }

    private static void closeLock(AutoCloseable lockHandle) {
        try {
            lockHandle.close();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to release conversation lock", exception);
        }
    }
}
