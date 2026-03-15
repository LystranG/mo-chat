package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

/**
 * 负责在 message-service 中把离线队列里的消息重新投递给刚上线的人。
 */
@Singleton
public final class MessageServiceOfflineReplayService {
    private final OfflineQueue offlineQueue;
    private final MessageRecipientDispatcher messageRecipientDispatcher;

    /**
     * 创建离线补发服务。
     */
    public MessageServiceOfflineReplayService(
        OfflineQueue offlineQueue,
        MessageRecipientDispatcher messageRecipientDispatcher
    ) {
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.messageRecipientDispatcher = Objects.requireNonNull(messageRecipientDispatcher, "messageRecipientDispatcher");
    }

    /**
     * 登录后按批次补发之前没送达的消息。
     */
    public int replay(long userId, int maxBatchSize) {
        int batchSize = maxBatchSize > 0 ? maxBatchSize : OfflineReplayService.MAX_REPLAY_ITEMS;
        List<String> drainedPayloads = offlineQueue.drain(userId, batchSize);
        int replayedCount = 0;
        for (int index = 0; index < drainedPayloads.size(); index++) {
            String payload = drainedPayloads.get(index);
            // 只要中途有一条补发失败，就把这一条以及后面的消息重新放回离线队列，避免顺序乱掉。
            if (!ReplayableDeliveryPayloadCodec.replayToRecipient(messageRecipientDispatcher, userId, payload)) {
                reEnqueueUndeliveredPayloads(userId, drainedPayloads, index);
                return replayedCount;
            }
            replayedCount++;
        }
        return replayedCount;
    }

    /**
     * 把本次没补发成功的消息重新塞回离线队列。
     */
    private void reEnqueueUndeliveredPayloads(long userId, List<String> drainedPayloads, int firstUndeliveredIndex) {
        for (int index = firstUndeliveredIndex; index < drainedPayloads.size(); index++) {
            offlineQueue.enqueue(userId, drainedPayloads.get(index), OfflineReplayService.MAX_REPLAY_ITEMS);
        }
    }
}
