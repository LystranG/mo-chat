package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import jakarta.inject.Singleton;

import java.util.Objects;

@Singleton
public final class OfflineReplayService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    public static final int MAX_REPLAY_ITEMS = 50;

    private final OfflineQueue offlineQueue;
    private final EventBus eventBus;
    private final String outboundTopic;

    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus) {
        this(offlineQueue, eventBus, DEFAULT_OUTBOUND_TOPIC);
    }

    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus, String outboundTopic) {
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    public void replayOnLogin(long userId) {
        for (String payload : offlineQueue.drain(userId, MAX_REPLAY_ITEMS)) {
            eventBus.publish(outboundTopic, userId + "|" + payload);
        }
    }
}
