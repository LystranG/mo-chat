package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.Objects;

public final class RedisOfflineQueue implements OfflineQueue {
    private static final String DEFAULT_KEY_PREFIX = "mochat:offline:";

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;

    public RedisOfflineQueue(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX);
    }

    public RedisOfflineQueue(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public void enqueue(long userId, String payload, int maxQueueSize) {
        Objects.requireNonNull(payload, "payload");
        if (maxQueueSize <= 0) {
            throw new IllegalArgumentException("maxQueueSize must be > 0");
        }

        String key = redisKey(userId);
        redisCommands.rpush(key, payload);
        redisCommands.ltrim(key, -maxQueueSize, -1);
    }

    @Override
    public List<String> drain(long userId, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }

        String key = redisKey(userId);
        List<String> items = redisCommands.lrange(key, 0, maxItems - 1L);
        if (!items.isEmpty()) {
            redisCommands.ltrim(key, items.size(), -1);
        }

        return items;
    }

    private String redisKey(long userId) {
        return keyPrefix + userId;
    }
}
