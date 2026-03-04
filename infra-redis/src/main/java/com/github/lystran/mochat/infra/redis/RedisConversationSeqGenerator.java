package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.function.LongUnaryOperator;

public final class RedisConversationSeqGenerator implements ConversationSeqGenerator {
    private static final String DEFAULT_KEY_PREFIX = "mochat:conversation:seq:";

    private final RedisCommands<String, String> redisCommands;
    private final ConversationLock conversationLock;
    private final LongUnaryOperator seedProvider;
    private final String keyPrefix;

    public RedisConversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        LongUnaryOperator seedProvider
    ) {
        this(redisCommands, conversationLock, seedProvider, DEFAULT_KEY_PREFIX);
    }

    public RedisConversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        LongUnaryOperator seedProvider,
        String keyPrefix
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.conversationLock = Objects.requireNonNull(conversationLock, "conversationLock");
        this.seedProvider = Objects.requireNonNull(seedProvider, "seedProvider");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public long next(long conversationId) {
        AutoCloseable lockHandle = conversationLock.acquire(conversationId);
        try {
            String redisKey = keyPrefix + conversationId;
            if (redisCommands.get(redisKey) == null) {
                long seed = seedProvider.applyAsLong(conversationId);
                redisCommands.setnx(redisKey, Long.toString(seed));
            }

            Long nextValue = redisCommands.incr(redisKey);
            if (nextValue == null) {
                throw new IllegalStateException("Redis INCR returned null for key " + redisKey);
            }

            return nextValue;
        } finally {
            closeLock(lockHandle);
        }
    }

    private static void closeLock(AutoCloseable lockHandle) {
        try {
            lockHandle.close();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to release conversation lock", exception);
        }
    }
}
