package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import io.netty.channel.Channel;

import java.util.Objects;

public final class OutboundEventSubscriber implements AutoCloseable {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";

    private final EventBus eventBus;
    private final UserChannelDirectory<Channel> userChannelDirectory;
    private final String topic;
    private AutoCloseable subscription = () -> {
    };

    public OutboundEventSubscriber(EventBus eventBus, UserChannelDirectory<Channel> userChannelDirectory) {
        this(eventBus, userChannelDirectory, DEFAULT_OUTBOUND_TOPIC);
    }

    public OutboundEventSubscriber(EventBus eventBus, UserChannelDirectory<Channel> userChannelDirectory, String topic) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.userChannelDirectory = Objects.requireNonNull(userChannelDirectory, "userChannelDirectory");
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
        userChannelDirectory.find(userId).ifPresent(channel -> channel.writeAndFlush(payload));
    }
}
