package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 用 Redis 记住“这条客户端消息是不是已经处理过”，避免短时间内重复发送。
 */
public final class RedisIdempotencyStore implements IdempotencyStore {
    private static final String DEFAULT_KEY_PREFIX = "mochat:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * 用默认键名前缀和过期时间创建幂等窗口。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX, DEFAULT_TTL);
    }

    /**
     * 用指定键名前缀和默认过期时间创建幂等窗口。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this(redisCommands, keyPrefix, DEFAULT_TTL);
    }

    /**
     * 用指定键名前缀和过期时间创建幂等窗口。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix, Duration ttl) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be > 0");
        }
    }

    @Override
    /**
     * 查某个发送方的某条客户端消息是不是已经处理过。
     */
    public Optional<StoredSendResult> find(long senderUid, long clientMsgId) {
        String value = redisCommands.get(redisKey(senderUid, clientMsgId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        try {
            return Optional.of(new StoredSendResult(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    /**
     * 只有第一次处理这条客户端消息时才记下来，后续重试沿用旧结果。
     */
    public void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq) {
        redisCommands.set(
            redisKey(senderUid, clientMsgId),
            // 把生成出来的 msgId 和 seq 压成一个简单字符串，一次读取就能恢复原结果。
            msgId + ":" + seq,
            new SetArgs().nx().px(ttl)
        );
    }

    /**
     * 拼出这条幂等记录对应的 Redis 键名。
     */
    private String redisKey(long senderUid, long clientMsgId) {
        return keyPrefix + senderUid + ":" + clientMsgId;
    }
}
