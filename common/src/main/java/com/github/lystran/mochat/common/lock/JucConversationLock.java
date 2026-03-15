package com.github.lystran.mochat.common.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 JDK 锁实现的会话级串行锁。
 */
public final class JucConversationLock implements ConversationLock {
    private final ConcurrentMap<Long, LockEntry> locksByConversationId = new ConcurrentHashMap<>();

    /**
     * 锁住指定会话，确保同一个会话里的关键操作一次只跑一条。
     */
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

    /**
     * 解锁后按引用计数回收不用的锁对象，免得会话多了以后一直堆在内存里。
     */
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

    /**
     * 保存单个会话当前正在用的锁和引用计数。
     */
    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger holderCount = new AtomicInteger();
    }
}
