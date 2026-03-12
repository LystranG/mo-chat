package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Singleton
@Requires(bean = EventBus.class)
@Requires(missingBeans = MessageRecipientDispatcher.class)
public final class EventBusMessageRecipientDispatcher implements MessageRecipientDispatcher {
    private final EventBus eventBus;
    private final String outboundTopic;

    public EventBusMessageRecipientDispatcher(EventBus eventBus) {
        this(eventBus, MessageIngestService.DEFAULT_OUTBOUND_TOPIC);
    }

    EventBusMessageRecipientDispatcher(EventBus eventBus, String outboundTopic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    @Override
    public MessageDeliveryStatus dispatchPrivate(PrivateMessageDelivery delivery) {
        publish(delivery.recipientUid(), ReplayableDeliveryPayloadCodec.encodePrivate(delivery));
        return MessageDeliveryStatus.DELIVERED;
    }

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

    private void publish(long userId, String payload) {
        eventBus.publish(outboundTopic, userId + "|" + payload);
    }
}
