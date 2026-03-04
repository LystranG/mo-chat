package com.github.lystran.mochat.common.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class JucConversationLock implements ConversationLock {
    private final ConcurrentMap<Long, ReentrantLock> locksByConversationId = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable acquire(long conversationId) {
        var lock = locksByConversationId.computeIfAbsent(conversationId, ignored -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }
}
