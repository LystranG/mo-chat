package com.github.lystran.mochat.logic.chat;

import java.util.Optional;

/**
 * 负责保存私聊会话的双方信息和回执进度。
 */
public interface ReceiptConversationStateStore {
    /**
     * 查询一条私聊会话当前记录的进度。
     */
    Optional<PrivateConversationState> findPrivateConversation(long conversationId);

    /**
     * 新建或更新一条私聊会话的基础状态。
     */
    void upsertPrivateConversation(long conversationId, long uidLow, long uidHigh, long latestSeq);

    /**
     * 更新某一方已经确认收到的最大顺序号。
     */
    long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq);

    /**
     * 表示一条私聊会话当前保存的双方进度。
     */
    record PrivateConversationState(
        long conversationId,
        long uidLow,
        long uidHigh,
        long latestSeq,
        long uidLowSeq,
        long uidHighSeq
    ) {
        /**
         * 判断某个用户是不是这条私聊的参与人。
         */
        public boolean isParticipant(long userId) {
            return userId == uidLow || userId == uidHigh;
        }

        /**
         * 根据当前用户找出这条私聊里的另一方。
         */
        public long peerUid(long userId) {
            if (userId == uidLow) {
                return uidHigh;
            }
            if (userId == uidHigh) {
                return uidLow;
            }
            throw new IllegalArgumentException("user is not a participant");
        }
    }
}
