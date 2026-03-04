package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

@Singleton
public final class ReceiptService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final ReceiptConversationStateStore stateStore;
    private final EventBus eventBus;
    private final Clock clock;
    private final String outboundTopic;

    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus) {
        this(stateStore, eventBus, Clock.systemUTC(), DEFAULT_OUTBOUND_TOPIC);
    }

    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock) {
        this(stateStore, eventBus, clock, DEFAULT_OUTBOUND_TOPIC);
    }

    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock, String outboundTopic) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    public boolean handleClientReceiveAck(long receiverUid, long conversationId, long latestReceivedSeq) {
        if (latestReceivedSeq < 0) {
            return false;
        }

        var stateOptional = stateStore.findPrivateConversation(conversationId);
        if (stateOptional.isEmpty()) {
            return false;
        }

        var state = stateOptional.get();
        if (!state.isParticipant(receiverUid)) {
            return false;
        }
        if (latestReceivedSeq > state.latestSeq()) {
            return false;
        }

        long updatedSeq = stateStore.updateLatestReceivedSeq(conversationId, receiverUid, latestReceivedSeq);
        emitDeliveredAck(state.peerUid(receiverUid), conversationId, receiverUid, updatedSeq, clock.millis());
        return true;
    }

    private void emitDeliveredAck(
        long senderUid,
        long conversationId,
        long receiverUid,
        long latestReceivedSeq,
        long serverTimeMs
    ) {
        var deliveredAck = Mochat.DeliveredAck.newBuilder()
            .setConversationId(conversationId)
            .setToUid(receiverUid)
            .setLatestReceivedSeq(latestReceivedSeq)
            .setServerTimeMs(serverTimeMs)
            .build();

        String encodedPayload = Base64.getEncoder().encodeToString(deliveredAck.toByteArray());
        String outboundEvent = senderUid
            + "|"
            + MsgType.DELIVERED_ACK.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + encodedPayload;
        eventBus.publish(outboundTopic, outboundEvent);
    }
}
