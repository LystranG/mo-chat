package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.Objects;

/**
 * 用 Redis 列表暂存用户离线时没法立刻推送的消息。
 */
public final class RedisOfflineQueue implements OfflineQueue {
    private static final String DEFAULT_KEY_PREFIX = "mochat:offline:";

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;

    /**
     * 用默认 Redis 键名前缀创建离线队列。
     */
    public RedisOfflineQueue(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX);
    }

    /**
     * 用指定的 Redis 键名前缀创建离线队列。
     */
    public RedisOfflineQueue(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    /**
     * 把消息追加到用户离线队列尾部，并把总长度裁到上限。
     */
    @Override
    public void enqueue(long userId, String payload, int maxQueueSize) {
        Objects.requireNonNull(payload, "payload");
        if (maxQueueSize <= 0) {
            throw new IllegalArgumentException("maxQueueSize must be > 0");
        }

        String key = redisKey(userId);
        // 先追加到队尾，再把超出的最旧消息从队头裁掉；等用户登录重放时，再从队头一批批取出来，
        // 所以还保留着旧到新的顺序。
        redisCommands.rpush(key, payload);
        redisCommands.ltrim(key, -maxQueueSize, -1);
    }

    /**
     * 从队头一次取出一批待重放消息。
     */
    @Override
    public List<String> drain(long userId, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }

        String key = redisKey(userId);
        List<String> items = redisCommands.lpop(key, maxItems);
        return items == null ? List.of() : items;
    }

    /**
     * 生成这个用户离线队列的 Redis 键名。
     */
    private String redisKey(long userId) {
        return keyPrefix + userId;
    }
}
