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
    private final String outboundTopic;

    public MessageIngestService(
        ConversationLock conversationLock,
        IdempotencyStore idempotencyStore,
        ConversationSeqGenerator conversationSeqGenerator,
        IdGenerator idGenerator,
        RocketMqProducer rocketMqProducer,
        EventBus eventBus
    ) {
        this(
            conversationLock,
            idempotencyStore,
            conversationSeqGenerator,
            idGenerator,
            Clock.systemUTC(),
            rocketMqProducer,
            eventBus,
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
        String outboundTopic
    ) {
        this.conversationLock = Objects.requireNonNull(conversationLock, "conversationLock");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        this.conversationSeqGenerator = Objects.requireNonNull(conversationSeqGenerator, "conversationSeqGenerator");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rocketMqProducer = Objects.requireNonNull(rocketMqProducer, "rocketMqProducer");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
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
            return new MessageIngestResult(request.clientMsgId(), msgId, seq, serverTimeMs);
        } finally {
            closeLock(lockHandle);
        }
    }

    private void emitSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();

        String encodedPayload = Base64.getEncoder().encodeToString(sendAck.toByteArray());
        String outboundEvent = senderUid
            + "|"
            + MsgType.SEND_ACK.name()
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
