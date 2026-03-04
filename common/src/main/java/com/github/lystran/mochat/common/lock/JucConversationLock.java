package com.github.lystran.mochat.common.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class JucConversationLock implements ConversationLock {
    private final ConcurrentMap<Long, LockEntry> locksByConversationId = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable acquire(long conversationId) {
        var lockEntry = locksByConversationId.compute(conversationId, (ignored, existing) -> {
            var current = existing == null ? new LockEntry() : existing;
            current.holderCount.incrementAndGet();
            return current;
        });

        lockEntry.lock.lock();
        return () -> release(conversationId, lockEntry);
    }

    private void release(long conversationId, LockEntry lockEntry) {
        lockEntry.lock.unlock();

        locksByConversationId.compute(conversationId, (ignored, current) -> {
            if (current != lockEntry) {
                return current;
            }

            int holders = lockEntry.holderCount.decrementAndGet();
            if (holders == 0 && !lockEntry.lock.isLocked() && !lockEntry.lock.hasQueuedThreads()) {
                return null;
            }

            return lockEntry;
        });
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger holderCount = new AtomicInteger();
    }
}
