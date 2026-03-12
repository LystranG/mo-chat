package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

@Singleton
@Requires(missingBeans = PrivateConversationProgressTracker.class)
public final class ReceiptConversationProgressTracker implements PrivateConversationProgressTracker {
    private final ReceiptConversationStateStore receiptConversationStateStore;

    public ReceiptConversationProgressTracker(ReceiptConversationStateStore receiptConversationStateStore) {
        this.receiptConversationStateStore = Objects.requireNonNull(receiptConversationStateStore, "receiptConversationStateStore");
    }

    @Override
    public void trackPrivateConversation(long conversationId, long peerUidLow, long peerUidHigh, long seq) {
        receiptConversationStateStore.upsertPrivateConversation(conversationId, peerUidLow, peerUidHigh, seq);
    }
}
