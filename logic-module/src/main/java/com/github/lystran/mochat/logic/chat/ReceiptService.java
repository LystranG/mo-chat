package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/**
 * 处理客户端“我收到消息了”的确认，并把最新确认进度发回给发送方。
 */
@Singleton
public final class ReceiptService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final ReceiptConversationStateStore stateStore;
    private final EventBus eventBus;
    private final Clock clock;
    private final String outboundTopic;

    /**
     * 使用系统时钟和默认发送事件通道构造送达确认服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus) {
        this(stateStore, eventBus, Clock.systemUTC(), DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定时钟和默认发送事件通道构造送达确认服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock) {
        this(stateStore, eventBus, clock, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用完整依赖构造送达确认服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock, String outboundTopic) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 校验并保存客户端上报的“已经收到哪条消息”。
     */
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

        // 发回给发送方的进度，以保存后的结果为准；这样重复确认或乱序确认都不会把进度倒退回去。
        long updatedSeq = stateStore.updateLatestReceivedSeq(conversationId, receiverUid, latestReceivedSeq);
        emitDeliveredAck(state.peerUid(receiverUid), conversationId, receiverUid, updatedSeq, clock.millis());
        return true;
    }

    /**
     * 把“对方已经收到到哪条”为止的确认发回给发送方。
     */
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
