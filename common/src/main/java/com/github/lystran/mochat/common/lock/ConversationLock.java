package com.github.lystran.mochat.common.lock;

public interface ConversationLock {
    AutoCloseable acquire(long conversationId);
}
