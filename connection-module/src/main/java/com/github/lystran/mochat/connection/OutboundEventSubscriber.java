package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import io.netty.channel.Channel;

import java.util.Objects;

public final class OutboundEventSubscriber implements AutoCloseable {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    public static final int OFFLINE_QUEUE_MAX_SIZE = 50;

    private final EventBus eventBus;
    private final UserChannelDirectory<Channel> userChannelDirectory;
    private final OfflineQueue offlineQueue;
    private final String topic;
    private AutoCloseable subscription = () -> {
    };

    public OutboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        this(eventBus, userChannelDirectory, offlineQueue, DEFAULT_OUTBOUND_TOPIC);
    }

    public OutboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue,
        String topic
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    public void start() {
        subscription = eventBus.subscribe(topic, this::handleOutboundEvent);
    }

    @Override
    public void close() throws Exception {
        subscription.close();
    }

    private void handleOutboundEvent(String event) {
        int separator = event.indexOf('|');
        if (separator <= 0) {
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(event.substring(0, separator));
        } catch (NumberFormatException ignored) {
            return;
        }

        String payload = event.substring(separator + 1);
        userChannelDirectory.find(userId).ifPresentOrElse(
            channel -> {
                try {
                    channel.writeAndFlush(payload).addListener(future -> {
                        if (!future.isSuccess()) {
                            queueOffline(userId, payload);
                        }
                    });
                } catch (RuntimeException ignored) {
                    queueOffline(userId, payload);
                }
            },
            () -> queueOffline(userId, payload)
        );
    }

    private void queueOffline(long userId, String payload) {
        offlineQueue.enqueue(userId, payload, OFFLINE_QUEUE_MAX_SIZE);
    }
}
