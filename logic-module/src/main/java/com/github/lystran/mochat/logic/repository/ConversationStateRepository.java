package com.github.lystran.mochat.logic.repository;

import java.util.Optional;

/**
 * 定义“用户能不能看这个会话”以及“会话最新状态怎么查”的仓储接口。
 */
public interface ConversationStateRepository {
    // 判断请求方是否有权访问指定会话。
    boolean hasConversationAccess(long conversationId, long requesterUid);

    // 查询私聊里“对方已经收到”的最新消息编号。
    Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid);

    // 查询会话最后一条消息的状态。
    Optional<ConversationLatestState> findConversationLatestState(long conversationId);

    /** 会话最后一条消息状态的读取结果。 */
    record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
