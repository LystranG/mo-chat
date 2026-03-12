package com.github.lystran.mochat.logic.chat;

public interface PrivateConversationProgressTracker {
    void trackPrivateConversation(long conversationId, long peerUidLow, long peerUidHigh, long seq);
}
