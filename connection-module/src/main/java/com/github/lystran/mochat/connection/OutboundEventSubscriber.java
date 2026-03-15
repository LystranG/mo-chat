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

/**
 * 把事件总线里要发给客户端的数据取出来，优先写到在线连接，失败再放进离线队列。
 */
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

    /**
     * 用默认的“要发给客户端”事件名创建处理器。
     */
    public OutboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        this(eventBus, userChannelDirectory, offlineQueue, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 用指定的“要发给客户端”事件名创建处理器。
     */
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

    /**
     * 开始监听“要发给客户端”的事件。
     */
    public void start() {
        subscription = eventBus.subscribe(topic, this::handleOutboundEvent);
    }

    /**
     * 关闭当前这条事件监听关系。
     */
    @Override
    public void close() throws Exception {
        subscription.close();
    }

    /**
     * 解析要发给客户端的数据，并按“在线先发，失败再存离线”的规则处理。
     */
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
                    // 只有在线发送失败时才放进离线队列，免得同一条消息又在线发送又重复排队。
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

    /**
     * 把逻辑层给出的字符串数据重新拼成连接层要写回去的二进制消息。
     */
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

    /**
     * 在允许补发的消息类型下，把消息塞进用户离线队列。
     */
    private void queueOffline(long userId, String payload) {
        // DELIVERED_ACK 表示“对方已经收到”的回执，只是告诉发送方进度，不值得离线时一条条攒着补发。
        if (isDeliveredAckPayload(payload)) {
            return;
        }
        offlineQueue.enqueue(userId, payload, OFFLINE_QUEUE_MAX_SIZE);
    }

    /**
     * 判断这条消息是不是“对方已收到”的回执；这种消息不进离线队列。
     */
    private boolean isDeliveredAckPayload(String payload) {
        return payload.equals(DELIVERED_ACK) || payload.startsWith(DELIVERED_ACK_PREFIX);
    }
}
