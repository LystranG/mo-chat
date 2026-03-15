package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import com.github.lystran.mochat.protocol.proto.Mochat;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

/**
 * 处理客户端“我已经收到消息了”的回执，并把结果通知给发送方。
 */
@Singleton
@Requires(property = "micronaut.application.name", notEquals = "api-service", defaultValue = "")
public final class ReceiptService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final ReceiptConversationStateStore stateStore;
    private final EventBus eventBus;
    private final Clock clock;
    private final String outboundTopic;

    /**
     * 使用默认时间源和默认主题创建回执服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus) {
        this(stateStore, eventBus, Clock.systemUTC(), DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定时间源创建回执服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock) {
        this(stateStore, eventBus, clock, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 创建回执服务。
     */
    public ReceiptService(ReceiptConversationStateStore stateStore, EventBus eventBus, Clock clock, String outboundTopic) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 处理客户端上报的“已收到最大顺序号”回执。
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

        // 只把顺序号往前推进，不会被旧回执倒退。
        long updatedSeq = stateStore.updateLatestReceivedSeq(conversationId, receiverUid, latestReceivedSeq);
        emitDeliveredAck(state.peerUid(receiverUid), conversationId, receiverUid, updatedSeq, clock.millis());
        return true;
    }

    /**
     * 给发送方发一条“对方已经收到到这里了”的通知。
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

        // 连接层消费的是用竖线拼出来的字符串消息，最后一段才是真正的消息正文。
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
