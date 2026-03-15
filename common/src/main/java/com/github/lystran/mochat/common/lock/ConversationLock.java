package com.github.lystran.mochat.common.lock;

/**
 * 让同一个会话里的关键操作按顺序串行执行。
 */
public interface ConversationLock {
    /**
     * 锁住某个会话，并返回解锁句柄。
     */
    AutoCloseable acquire(long conversationId);
}
