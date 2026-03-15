package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 通过事件总线把消息交给连接层做在线投递。
 */
@Singleton
@Requires(bean = EventBus.class)
@Requires(missingBeans = MessageRecipientDispatcher.class)
public final class EventBusMessageRecipientDispatcher implements MessageRecipientDispatcher {
    private final EventBus eventBus;
    // 这里是发给连接层的事件主题，也就是“把消息交给连接层继续发给客户端”的那条通道。
    private final String outboundTopic;

    /**
     * 使用默认主题创建投递器。
     */
    public EventBusMessageRecipientDispatcher(EventBus eventBus) {
        this(eventBus, MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定主题创建投递器。
     */
    EventBusMessageRecipientDispatcher(EventBus eventBus, String outboundTopic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 把一条私聊消息发给目标用户。
     */
    @Override
    public MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery) {
        publish(delivery.recipientUid(), ReplayableDeliveryPayloadCodec.encodePrivate(delivery));
        return MessageDeliveryStatus.DELIVERED;
    }

    /**
     * 把一条群消息分别发给每个目标成员。
     */
    @Override
    public Map<Long, MessageDeliveryStatus> dispatchGroup(GroupMessageDelivery delivery) {
        Map<Long, MessageDeliveryStatus> statuses = new LinkedHashMap<>();
        for (Long recipientUid : delivery.recipientUids()) {
            if (recipientUid == null || recipientUid <= 0L || recipientUid == delivery.senderUid()) {
                continue;
            }
            publish(recipientUid, ReplayableDeliveryPayloadCodec.encodeGroup(delivery));
            statuses.put(recipientUid, MessageDeliveryStatus.DELIVERED);
        }
        return statuses;
    }

    /**
     * 用“用户 ID + 竖线 + 字符串消息”的格式发给连接层。
     */
    private void publish(long userId, String payload) {
        eventBus.publish(outboundTopic, userId + "|" + payload);
    }
}
