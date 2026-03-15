package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在没有数据库状态仓储时，用内存记录私聊里双方确认到哪条消息。
 */
@Singleton
@Requires(missingBeans = ReceiptConversationStateStore.class)
public final class InMemoryReceiptConversationStateStore implements ReceiptConversationStateStore {
    private final ConcurrentMap<Long, MutableState> states = new ConcurrentHashMap<>();

    /**
     * 读取内存里的私聊状态副本。
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
     * 建立或刷新服务端已知的私聊最新消息序号。
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
                // 只接受更大的 latestSeq，避免旧消息把服务端已经知道的最新进度覆盖回去。
                current.latestSeq = Math.max(current.latestSeq, latestSeq);
            }
            return current;
        });
    }

    /**
     * 推进指定接收方“已经确认收到”的位置。
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
     * 保存可变的私聊状态，并在读取时给外部一份只读副本。
     */
    private static final class MutableState {
        private final long conversationId;
        private final long uidLow;
        private final long uidHigh;
        private long latestSeq;
        private long uidLowSeq;
        private long uidHighSeq;

        /**
         * 使用完整状态字段构造内存中的可变状态对象。
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
         * 复制当前状态，供外部只读使用。
         */
        private PrivateConversationState snapshot() {
            return new PrivateConversationState(conversationId, uidLow, uidHigh, latestSeq, uidLowSeq, uidHighSeq);
        }
    }
}
