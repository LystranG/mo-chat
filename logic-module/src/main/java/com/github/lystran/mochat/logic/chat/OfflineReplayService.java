package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 在用户重新登录后，把之前没收到的消息从离线队列里补发出去。
 */
@Singleton
public final class OfflineReplayService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    public static final int MAX_REPLAY_ITEMS = 50;

    private final OfflineQueue offlineQueue;
    private final EventBus eventBus;
    private final String outboundTopic;

    /**
     * 使用默认发送事件通道构造离线补发服务。
     */
    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus) {
        this(offlineQueue, eventBus, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定发送事件通道构造离线补发服务。
     */
    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus, String outboundTopic) {
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 取出用户离线窗口内的消息，并按原顺序重新发到连接层。
     */
    public void replayOnLogin(long userId) {
        List<String> drainedPayloads = offlineQueue.drain(userId, MAX_REPLAY_ITEMS);
        for (int index = 0; index < drainedPayloads.size(); index++) {
            String payload = drainedPayloads.get(index);
            try {
                eventBus.publish(outboundTopic, userId + "|" + payload);
            } catch (RuntimeException publishFailure) {
                // 中途补发失败时，只把当前这条以及后面还没发成功的消息放回队列，避免前面已发出的消息再来一遍。
                reEnqueueUndeliveredPayloads(userId, drainedPayloads, index);
                return;
            }
        }
    }

    /**
     * 把还没补发成功的那一段消息重新放回离线队列。
     */
    private void reEnqueueUndeliveredPayloads(long userId, List<String> drainedPayloads, int firstUndeliveredIndex) {
        for (int index = firstUndeliveredIndex; index < drainedPayloads.size(); index++) {
            offlineQueue.enqueue(userId, drainedPayloads.get(index), MAX_REPLAY_ITEMS);
        }
    }
}
