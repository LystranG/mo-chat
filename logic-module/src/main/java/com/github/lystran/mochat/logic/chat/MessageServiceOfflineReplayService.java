package com.github.lystran.mochat.logic.chat;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Objects;

@Singleton
public final class MessageServiceOfflineReplayService {
    private final OfflineQueue offlineQueue;
    private final MessageRecipientDispatcher messageRecipientDispatcher;

    public MessageServiceOfflineReplayService(
        OfflineQueue offlineQueue,
        MessageRecipientDispatcher messageRecipientDispatcher
    ) {
        this.offlineQueue = Objects.requireNonNull(offlineQueue, "offlineQueue");
        this.messageRecipientDispatcher = Objects.requireNonNull(messageRecipientDispatcher, "messageRecipientDispatcher");
    }

    public int replay(long userId, int maxBatchSize) {
        int batchSize = maxBatchSize > 0 ? maxBatchSize : OfflineReplayService.MAX_REPLAY_ITEMS;
        List<String> drainedPayloads = offlineQueue.drain(userId, batchSize);
        int replayedCount = 0;
        for (int index = 0; index < drainedPayloads.size(); index++) {
            String payload = drainedPayloads.get(index);
            if (!ReplayableDeliveryPayloadCodec.replayToRecipient(messageRecipientDispatcher, userId, payload)) {
                reEnqueueUndeliveredPayloads(userId, drainedPayloads, index);
                return replayedCount;
            }
            replayedCount++;
        }
        return replayedCount;
    }

    private void reEnqueueUndeliveredPayloads(long userId, List<String> drainedPayloads, int firstUndeliveredIndex) {
        for (int index = firstUndeliveredIndex; index < drainedPayloads.size(); index++) {
            offlineQueue.enqueue(userId, drainedPayloads.get(index), OfflineReplayService.MAX_REPLAY_ITEMS);
        }
    }
}
