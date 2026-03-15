package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.lock.ConversationLock;
import com.github.lystran.mochat.common.seq.ConversationSeqGenerator;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Objects;
import java.util.function.LongUnaryOperator;

/**
 * 用 Redis 的自增值给每个会话排消息顺序号。
 */
public final class RedisConversationSeqGenerator implements ConversationSeqGenerator {
    private static final String DEFAULT_KEY_PREFIX = "mochat:conversation:seq:";

    private final RedisCommands<String, String> redisCommands;
    private final ConversationLock conversationLock;
    private final LongUnaryOperator seedProvider;
    private final String keyPrefix;

    /**
     * 用默认 Redis 键名前缀创建顺序号生成器。
     */
    public RedisConversationSeqGenerator(
        RedisCommands<String, String> redisCommands,
        ConversationLock conversationLock,
        LongUnaryOperator seedProvider
    ) {
        this(redisCommands, conversationLock, seedProvider, DEFAULT_KEY_PREFIX);
    }

    /**
     * 用指定的 Redis 键名前缀创建顺序号生成器。
     */
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

    /**
     * 给指定会话取下一个可写入数据库的顺序号。
     */
    @Override
    public long next(long conversationId) {
        AutoCloseable lockHandle = conversationLock.acquire(conversationId);
        try {
            String redisKey = keyPrefix + conversationId;
            // Redis 里还没有这个会话的计数时，先用数据库里的最新 seq 当起点，再在同一把会话锁里自增；
            // 这样服务重启后顺序号不会倒退，也不会被两个并发请求重复初始化。
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

    /**
     * 统一关闭会话锁，并把受检异常改成运行时异常。
     */
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
