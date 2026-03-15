package com.github.lystran.mochat.common.offline;

import java.util.List;

/**
 * 暂存用户离线时没能立刻送达的消息。
 */
public interface OfflineQueue {
    /**
     * 往离线队列里追加一条消息，并按上限裁掉过旧内容。
     */
    void enqueue(long userId, String payload, int maxQueueSize);

    /**
     * 取出一批待补发消息。
     */
    List<String> drain(long userId, int maxItems);
}
