package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 用内存保存私聊回执进度，主要用于本地运行或兜底场景。
 */
@Singleton
@Requires(missingBeans = ReceiptConversationStateStore.class)
@Requires(property = "micronaut.application.name", notEquals = "api-service", defaultValue = "")
// 不再排除 message-service：dedicated message-service 在 inbound-consumer 开启时
// 也需要 ReceiptConversationStateStore 给 ReceiptService 用，而 persistence-service
// 的 JDBC owner 是另一个 JVM、跨不过去，所以 message-service 用内存兜底也合理。
public final class InMemoryReceiptConversationStateStore implements ReceiptConversationStateStore {
    private final ConcurrentMap<Long, MutableState> states = new ConcurrentHashMap<>();

    /**
     * 查询某条私聊会话当前保存的回执状态。
     */
    @Override
    public Optional<PrivateConversationState> findPrivateConversation(long conversationId) {
        MutableState state = states.get(conversationId);
        if (state == null) {
            return Optional.empty();
        }

        synchronized (state) {
            return Optional.of(state.snapshot());
        }
    }

    /**
     * 新建或更新一条私聊会话的基础信息。
     */
    @Override
    public void upsertPrivateConversation(long conversationId, long uidLow, long uidHigh, long latestSeq) {
        if (uidLow <= 0 || uidHigh <= 0 || uidLow >= uidHigh) {
            throw new IllegalArgumentException("private conversation participants must be ordered and positive");
        }
        if (latestSeq < 0) {
            throw new IllegalArgumentException("latestSeq must be >= 0");
        }

        states.compute(conversationId, (ignored, current) -> {
            if (current == null) {
                return new MutableState(conversationId, uidLow, uidHigh, latestSeq, 0L, 0L);
            }

            synchronized (current) {
                if (current.uidLow != uidLow || current.uidHigh != uidHigh) {
                    throw new IllegalStateException("conversation participants mismatch");
                }
                // 只保留更大的顺序号，避免旧数据把会话进度覆盖回去。
                current.latestSeq = Math.max(current.latestSeq, latestSeq);
            }
            return current;
        });
    }

    /**
     * 更新某个接收方已经确认收到的最大顺序号。
     */
    @Override
    public long updateLatestReceivedSeq(long conversationId, long receiverUid, long latestReceivedSeq) {
        if (latestReceivedSeq < 0) {
            throw new IllegalArgumentException("latestReceivedSeq must be >= 0");
        }

        MutableState state = states.get(conversationId);
        if (state == null) {
            throw new IllegalArgumentException("conversation not found");
        }

        synchronized (state) {
            if (receiverUid == state.uidLow) {
                state.uidLowSeq = Math.max(state.uidLowSeq, latestReceivedSeq);
                return state.uidLowSeq;
            }
            if (receiverUid == state.uidHigh) {
                state.uidHighSeq = Math.max(state.uidHighSeq, latestReceivedSeq);
                return state.uidHighSeq;
            }
        }

        throw new IllegalArgumentException("receiver is not a conversation participant");
    }

    /**
     * 方便在内存里原地更新的可变状态。
     */
    private static final class MutableState {
        private final long conversationId;
        private final long uidLow;
        private final long uidHigh;
        private long latestSeq;
        private long uidLowSeq;
        private long uidHighSeq;

        /**
         * 创建一条可变的会话进度记录。
         */
        private MutableState(
            long conversationId,
            long uidLow,
            long uidHigh,
            long latestSeq,
            long uidLowSeq,
            long uidHighSeq
        ) {
            this.conversationId = conversationId;
            this.uidLow = uidLow;
            this.uidHigh = uidHigh;
            this.latestSeq = latestSeq;
            this.uidLowSeq = uidLowSeq;
            this.uidHighSeq = uidHighSeq;
        }

        /**
         * 复制成对外暴露的只读快照。
         */
        private PrivateConversationState snapshot() {
            return new PrivateConversationState(conversationId, uidLow, uidHigh, latestSeq, uidLowSeq, uidHighSeq);
        }
    }
}
