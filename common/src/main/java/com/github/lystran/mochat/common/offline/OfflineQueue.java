package com.github.lystran.mochat.common.offline;

import java.util.List;

public interface OfflineQueue {
    void enqueue(long userId, String payload, int maxQueueSize);

    List<String> drain(long userId, int maxItems);
}
