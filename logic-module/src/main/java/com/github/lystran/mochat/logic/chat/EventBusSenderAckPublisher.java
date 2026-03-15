package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Base64;
import java.util.Objects;

/**
 * 通过事件总线给发送方回“消息已被接受”的确认。
 */
@Singleton
@Requires(bean = EventBus.class)
@Requires(missingBeans = SenderAckPublisher.class)
public final class EventBusSenderAckPublisher implements SenderAckPublisher {
    private final EventBus eventBus;
    // 这里是发给连接层的事件主题。
    private final String outboundTopic;

    /**
     * 使用默认主题创建确认发送器。
     */
    public EventBusSenderAckPublisher(EventBus eventBus) {
        this(eventBus, MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定主题创建确认发送器。
     */
    EventBusSenderAckPublisher(EventBus eventBus, String outboundTopic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 组装并发出“给发送方回确认”的事件。
     */
    @Override
    public void publishSendAck(long senderUid, long clientMsgId, long msgId, long seq, long serverTimeMs) {
        var sendAck = Mochat.SendAck.newBuilder()
            .setClientMsgId(clientMsgId)
            .setMsgId(msgId)
            .setSeq(seq)
            .setServerTimeMs(serverTimeMs)
            .build();
        // 连接层目前消费的是“用户 ID + 消息类型 + 序列化方式 + 消息正文”的竖线拼接格式。
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
