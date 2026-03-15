package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 把短时间内的发送结果先放进 Redis，客户端重试时还能拿回原来的 msgId 和 seq。
 */
public final class RedisIdempotencyStore implements IdempotencyStore {
    private static final String DEFAULT_KEY_PREFIX = "mochat:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;
    private final Duration ttl;

    /**
     * 用默认 Redis 键名前缀和过期时间创建幂等存储。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX, DEFAULT_TTL);
    }

    /**
     * 用指定的 Redis 键名前缀和默认过期时间创建幂等存储。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this(redisCommands, keyPrefix, DEFAULT_TTL);
    }

    /**
     * 用指定的 Redis 键名前缀和过期时间创建幂等存储。
     */
    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix, Duration ttl) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be > 0");
        }
    }

    /**
     * 查某个发送方的某条客户端消息是否已经发过。
     */
    @Override
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

    /**
     * 只有第一次发送时才记下来，避免重试把原来的 msgId 和 seq 覆盖掉。
     */
    @Override
    public void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq) {
        redisCommands.set(
            redisKey(senderUid, clientMsgId),
            // 把 msgId 和 seq 挤在一个字符串里存下去，读取时一次 GET 就能恢复出来。
            msgId + ":" + seq,
            new SetArgs().nx().px(ttl)
        );
    }

    /**
     * 生成这条幂等记录对应的 Redis 键名。
     */
    private String redisKey(long senderUid, long clientMsgId) {
        return keyPrefix + senderUid + ":" + clientMsgId;
    }
}
