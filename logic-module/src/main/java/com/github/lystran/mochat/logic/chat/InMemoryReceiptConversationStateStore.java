package com.github.lystran.mochat.logic.chat;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Singleton
@Requires(missingBeans = ReceiptConversationStateStore.class)
public final class InMemoryReceiptConversationStateStore implements ReceiptConversationStateStore {
    private final ConcurrentMap<Long, MutableState> states = new ConcurrentHashMap<>();

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
                current.latestSeq = Math.max(current.latestSeq, latestSeq);
            }
            return current;
        });
    }

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

    private static final class MutableState {
        private final long conversationId;
        private final long uidLow;
        private final long uidHigh;
        private long latestSeq;
        private long uidLowSeq;
        private long uidHighSeq;

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

        private PrivateConversationState snapshot() {
            return new PrivateConversationState(conversationId, uidLow, uidHigh, latestSeq, uidLowSeq, uidHighSeq);
        }
    }
}
