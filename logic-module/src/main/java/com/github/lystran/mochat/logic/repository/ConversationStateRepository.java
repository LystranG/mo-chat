package com.github.lystran.mochat.logic.repository;

import java.util.Optional;

public interface ConversationStateRepository {
    Optional<Long> findPrivatePeerLatestReceivedSeq(long conversationId, long requesterUid);

    Optional<ConversationLatestState> findConversationLatestState(long conversationId);

    record ConversationLatestState(long conversationId, long latestSeq, long latestMessageTime) {
    }
}
