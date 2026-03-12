package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Base64;
import java.util.Objects;

@Singleton
@Requires(bean = EventBus.class)
@Requires(missingBeans = SenderAckPublisher.class)
public final class EventBusSenderAckPublisher implements SenderAckPublisher {
    private final EventBus eventBus;
    private final String outboundTopic;

    public EventBusSenderAckPublisher(EventBus eventBus) {
        this(eventBus, MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
    }

    EventBusSenderAckPublisher(EventBus eventBus, String outboundTopic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    @Override
    public void publishSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();
        String outboundEvent = senderUid
            + "|"
            + MsgType.SEND_ACK.name()
            + "|"
            + SerializerType.PROTOBUF.name()
            + "|"
            + Base64.getEncoder().encodeToString(sendAck.toByteArray());
        eventBus.publish(outboundTopic, outboundEvent);
    }
}
