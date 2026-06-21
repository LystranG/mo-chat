package com.github.lystran.mochat.common.lock;

/**
 * 用来保证同一个会话里的关键操作要排队执行。
 */
public interface ConversationLock {
    /**
     * 拿到某个会话的独占锁，并返回一个用完后可直接释放的句柄。
     */
    AutoCloseable acquire(long conversationId);
}
