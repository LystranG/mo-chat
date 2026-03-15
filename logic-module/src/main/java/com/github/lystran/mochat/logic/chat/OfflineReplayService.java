package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.event.EventBus;
import com.github.lystran.mochat.common.offline.OfflineQueue;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 通过事件总线把离线消息重新推给连接层，常用于登录后补发。
 */
@Singleton
public final class OfflineReplayService {
    public static final String DEFAULT_OUTBOUND_TOPIC = "connection.outbound";
    public static final int MAX_REPLAY_ITEMS = 50;

    private final OfflineQueue offlineQueue;
    private final EventBus eventBus;
    private final String outboundTopic;

    /**
     * 使用默认主题创建离线补发服务。
     */
    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus) {
        this(offlineQueue, eventBus, DEFAULT_OUTBOUND_TOPIC);
    }

    /**
     * 使用指定主题创建离线补发服务。
     */
    public OfflineReplayService(OfflineQueue offlineQueue, EventBus eventBus, String outboundTopic) {
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.outboundTopic = Objects.requireNonNull(outboundTopic, "outboundTopic");
    }

    /**
     * 登录后把离线队列里的消息一批批发回去。
     */
    public void replayOnLogin(long userId) {
        List<String> drainedPayloads = offlineQueue.drain(userId, MAX_REPLAY_ITEMS);
        for (int index = 0; index < drainedPayloads.size(); index++) {
            String payload = drainedPayloads.get(index);
            try {
                // 继续沿用“用户 ID + 竖线 + 字符串消息”的格式发给连接层。
                eventBus.publish(outboundTopic, userId + "|" + payload);
            } catch (RuntimeException publishFailure) {
                reEnqueueUndeliveredPayloads(userId, drainedPayloads, index);
                return;
            }
        }
    }

    /**
     * 把没成功补发的剩余消息重新放回离线队列。
     */
    private void reEnqueueUndeliveredPayloads(long userId, List<String> drainedPayloads, int firstUndeliveredIndex) {
        for (int index = firstUndeliveredIndex; index < drainedPayloads.size(); index++) {
            offlineQueue.enqueue(userId, drainedPayloads.get(index), MAX_REPLAY_ITEMS);
        }
    }
}
