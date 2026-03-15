package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Objects;

/**
 * 负责把私聊最新顺序号同步到回执状态仓库。
 */
@Singleton
@Requires(missingBeans = PrivateConversationProgressTracker.class)
public final class ReceiptConversationProgressTracker implements PrivateConversationProgressTracker {
    private final ReceiptConversationStateStore receiptConversationStateStore;

    /**
     * 创建一个基于回执状态仓库的进度记录器。
     */
    public ReceiptConversationProgressTracker(ReceiptConversationStateStore receiptConversationStateStore) {
        this.receiptConversationStateStore = Objects.requireNonNull(receiptConversationStateStore, "receiptConversationStateStore");
    }

    /**
     * 记录这条私聊目前已经生成到哪个顺序号。
     */
    @Override
    public void trackPrivateConversation(long conversationId, long peerUidLow, long peerUidHigh, long seq) {
        receiptConversationStateStore.upsertPrivateConversation(conversationId, peerUidLow, peerUidHigh, seq);
    }
}
