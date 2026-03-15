package com.github.lystran.mochat.logic.chat;

import java.util.Optional;

/**
 * 维护私聊会话的最新消息序号，以及双方各自已经确认收到哪条消息。
 */
public interface ReceiptConversationStateStore {
    /**
     * 读取指定私聊会话的当前状态。
     */
    Optional<PrivateConversationState> findPrivateConversation(long conversationId);

    /**
     * 建立或刷新服务端已知的私聊最新消息序号。
     */
    void upsertPrivateConversation(long conversationId, long uidLow, long uidHigh, long latestSeq);

    /**
     * 记录某个接收方已经确认收到哪条消息。
     */
    long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq);

    /**
     * 表示服务端眼里这条私聊的参与者，以及双方已经确认到哪条消息。
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
         * 判断给定用户是否属于该私聊会话。
         */
        public boolean isParticipant(long userId) {
            return userId == uidLow || userId == uidHigh;
        }

        /**
         * 返回当前用户在私聊中的对端用户 ID。
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
