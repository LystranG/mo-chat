package com.github.lystran.mochat.logic.chat;

/**
 * 负责记录私聊会话目前推进到了哪一个顺序号。
 */
public interface PrivateConversationProgressTracker {
    /**
     * 保存私聊会话参与人和最新顺序号，方便后续回执处理。
     */
    void trackPrivateConversation(long conversationId, long peerUidLow, long peerUidHigh, long seq);
}
