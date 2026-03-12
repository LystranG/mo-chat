package com.github.lystran.mochat.logic.chat;

import java.util.Optional;

public interface ReceiptConversationStateStore {
    Optional<PrivateConversationState> findPrivateConversation(long conversationId);

    void upsertPrivateConversation(long conversationId, long uidLow, long uidHigh, long latestSeq);

    long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq);

    record PrivateConversationState(
        long conversationId,
        long uidLow,
        long uidHigh,
        long latestSeq,
        long uidLowSeq,
        long uidHighSeq
    ) {
        public boolean isParticipant(long userId) {
            return userId == uidLow || userId == uidHigh;
        }

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
