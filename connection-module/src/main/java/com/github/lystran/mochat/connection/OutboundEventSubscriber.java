package com.github.lystran.mochat.connection;

import com.github.lystran.mochat.common.directory.UserChannelDirectory;
import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import com.github.lystran.mochat.protocol.FrameConstants;
import com.github.lystran.mochat.protocol.MsgType;
import com.github.lystran.mochat.protocol.SerializerType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Objects;

/**
 * 订阅准备发给客户端的事件，优先写到本地连接，失败时再转离线队列。
 */
public final class OutboundEventSubscriber implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OutboundEventSubscriber.class);
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
     * 使用默认主题订阅发往客户端的事件。
     */
    public OutboundEventSubscriber(
        EventBus eventBus,
        UserChannelDirectory<Channel> userChannelDirectory,
        OfflineQueue offlineQueue
    ) {
        this(eventBus, userChannelDirectory, offlineQueue, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 指定事件主题，方便在测试或特殊部署里替换默认通道。
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
     * 开始监听要发给客户端的消息。
     */
    public void start() {
        log.info("出站事件订阅器启动，主题={}", topic);
        subscription = eventBus.subscribe(topic, this::handleOutboundEvent);
    }

    @Override
    /**
     * 取消事件订阅。
     */
    public void close() throws Exception {
        log.info("出站事件订阅器关闭");
        subscription.close();
    }

    /**
     * 解析事件里的目标用户和消息内容，然后决定发在线连接还是走离线队列。
     */
    private void handleOutboundEvent(String event) {
        int separator = event.indexOf('|');
        if (separator <= 0) {
            log.warn("出站事件格式无效: {}", event.length() > 100 ? event.substring(0, 100) + "..." : event);
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
            channel -> attemptDelivery(channel, userId, payload, true),
            () -> queueOffline(userId, payload)
        );
    }

    /**
     * 只有当前网关还真正持有这条连接时才直接写回；否则再尝试一次或转离线。
     */
    private void attemptDelivery(Channel channel, long userId, String payload, boolean allowRetry) {
        if (!SessionBindingHandler.hasActiveRouteOwnership(channel)) {
            if (allowRetry) {
                log.debug("连接 [{}] 用户 [{}] 路由已失效，延迟重试", channel.id().asShortText(), userId);
                scheduleRetry(channel, userId, payload);
                return;
            }
            log.debug("连接 [{}] 用户 [{}] 重试后仍无有效路由，转离线队列", channel.id().asShortText(), userId);
            queueOffline(userId, payload);
            return;
        }

        ByteBuf frame = null;
        try {
            frame = encodeFrame(channel, payload);
            channel.writeAndFlush(frame).addListener(future -> {
                if (!future.isSuccess()) {
                    log.debug("连接 [{}] 用户 [{}] 写入失败，转离线队列", channel.id().asShortText(), userId);
                    queueOffline(userId, payload);
                }
            });
        } catch (RuntimeException ignored) {
            if (frame != null) {
                frame.release();
            }
            queueOffline(userId, payload);
        }
    }

    /**
     * 把一次立即失败的在线投递延后到该连接自己的事件循环里再试一遍。
     */
    private void scheduleRetry(Channel channel, long userId, String payload) {
        try {
            channel.eventLoop().execute(() -> attemptDelivery(channel, userId, payload, false));
        } catch (RuntimeException ignored) {
            queueOffline(userId, payload);
        }
    }

    /**
     * 把字符串事件重新编码成 TCP 协议帧。
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
     * 在线投递走不通时，把消息放进离线队列，等待用户下次上线补发。
     */
    private void queueOffline(long userId, String payload) {
        if (isDeliveredAckPayload(payload)) {
            return;
        }
        offlineQueue.enqueue(userId, payload, OFFLINE_QUEUE_MAX_SIZE);
    }

    /**
     * 已送达回执不需要再走离线补发，避免重复噪音。
     */
    private boolean isDeliveredAckPayload(String payload) {
        return payload.equals(DELIVERED_ACK) || payload.startsWith(DELIVERED_ACK_PREFIX);
    }
}
