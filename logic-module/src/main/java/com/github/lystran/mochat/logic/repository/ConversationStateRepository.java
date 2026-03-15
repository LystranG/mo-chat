package com.github.lystran.mochat.logic.repository;

import java.util.Optional;

/**
 * 会话读侧仓储，负责查询会话可见性和最新推进进度。
 */
public interface ConversationStateRepository {
    /**
     * 判断某个用户有没有权查看这个会话。
     */
    boolean hasConversationAccess(long conversationId, long requesterUid);

    /**
     * 查询私聊里“对方已经确认收到哪条消息”。
     */
    Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid);

    /**
     * 查询会话当前最新消息序号和最后消息时间。
     */
    Optional<ConversationLatestState> findConversationLatestState(long conversationId);

    /**
     * 会话当前最新状态的只读视图。
     */
    record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
