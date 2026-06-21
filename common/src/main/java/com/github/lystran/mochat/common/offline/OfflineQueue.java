package com.github.lystran.mochat.common.offline;

import java.util.List;

/**
 * 用来暂存用户离线时还没收到的消息。
 */
public interface OfflineQueue {
    /**
     * 把一条消息塞进用户的离线队列，并按最大容量截断旧消息。
     */
    void enqueue(long userId, String payload, int maxQueueSize);

    /**
     * 一次取出用户离线队列里的多条消息。
     */
    List<String> drain(long userId, int maxItems);
}
