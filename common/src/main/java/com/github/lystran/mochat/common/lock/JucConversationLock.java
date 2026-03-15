package com.github.lystran.mochat.common.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用 JUC 锁让同一个会话里的关键路径排队执行。
 */
public final class JucConversationLock implements ConversationLock {
    private final ConcurrentMap<Long, LockEntry> locksByConversationId = new ConcurrentHashMap<>();

    /**
     * 拿到某个会话的锁，并在释放时顺手回收已经闲下来的锁条目。
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
     * 释放会话锁；如果这把锁已经没人拿、也没人等，就把它从表里删掉。
     */
    private void release(long conversationId, LockEntry lockEntry) {
        lockEntry.lock.unlock();

        locksByConversationId.compute(conversationId, (ignored, current) -> {
            if (current != lockEntry) {
                return current;
            }

            int holders = lockEntry.holderCount.decrementAndGet();
            // 只有既没人拿着这把锁，也没人排队等它时，才能安全删掉这条记录。
            if (holders == 0 && !lockEntry.lock.isLocked() && !lockEntry.lock.hasQueuedThreads()) {
                return null;
            }

            return lockEntry;
        });
    }

    /**
     * 保存某个会话对应的锁对象和当前还有多少地方在用它。
     */
    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger holderCount = new AtomicInteger();
    }
}
