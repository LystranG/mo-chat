package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.util.Base64;
import java.util.Objects;

public final class OutboundEventSubscriber implements AutoCloseable {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    public static final int OFFLINE_QUEUE_MAX_SIZE = 50;
    private static final int PROTOCOL_MAGIC = 0x4D4F4348;
    private static final String DELIVERED_ACK = MsgType.DELIVERED_ACK.name();
    private static final String DELIVERED_ACK_PREFIX = DELIVERED_ACK + "|";

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
                ByteBuf frame = null;
                try {
                    frame = encodeFrame(channel, payload);
                    channel.writeAndFlush(frame).addListener(future -> {
                        if (!future.isSuccess()) {
                            queueOffline(userId, payload);
                        }
                    });
                } catch (RuntimeException ignored) {
                    if (frame != null) {
                        frame.release();
                    }
                    queueOffline(userId, payload);
                }
            },
            () -> queueOffline(userId, payload)
        );
    }

    private static ByteBuf encodeFrame(Channel channel, String payload) {
        String[] segments = payload.split("\\|", 3);
        if (segments.length != 3) {
            throw new IllegalArgumentException("invalid outbound payload format");
        }

        MsgType msgType = MsgType.valueOf(segments[0]);
        SerializerType serializerType = SerializerType.valueOf(segments[1]);
        byte[] body = Base64.getDecoder().decode(segments[2]);

        ByteBuf frame = channel.alloc().buffer(FrameConstants.HEADER_LENGTH + body.length);
        frame.writeInt(PROTOCOL_MAGIC);
        frame.writeByte(FrameConstants.PROTOCOL_VERSION);
        frame.writeByte(msgType.code());
        frame.writeByte(serializerType.code());
        frame.writeInt(body.length);
        frame.writeBytes(body);
        return frame;
    }

    private void queueOffline(long userId, String payload) {
        if (isDeliveredAckPayload(payload)) {
            return;
        }
        offlineQueue.enqueue(userId, payload, OFFLINE_QUEUE_MAX_SIZE);
    }

    private boolean isDeliveredAckPayload(String payload) {
        return payload.equals(DELIVERED_ACK) || payload.startsWith(DELIVERED_ACK_PREFIX);
    }
}
