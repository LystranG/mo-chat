package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.idempotency.IdempotencyStore;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class RedisIdempotencyStore implements IdempotencyStore {
    private static final String DEFAULT_KEY_PREFIX = "mochat:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX, DEFAULT_TTL);
    }

    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this(redisCommands, keyPrefix, DEFAULT_TTL);
    }

    public RedisIdempotencyStore(RedisCommands<String, String> redisCommands, String keyPrefix, Duration ttl) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be > 0");
        }
    }

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

        return Optional.of(new StoredSendResult(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
    }

    @Override
    public void storeIfAbsent(long senderUid, long clientMsgId, long msgId, long seq) {
        redisCommands.set(
            redisKey(senderUid, clientMsgId),
            msgId + ":" + seq,
            new SetArgs().nx().px(ttl)
        );
    }

    private String redisKey(long senderUid, long clientMsgId) {
        return keyPrefix + senderUid + ":" + clientMsgId;
    }
}
