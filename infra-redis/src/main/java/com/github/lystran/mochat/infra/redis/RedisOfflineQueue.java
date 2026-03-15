package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.offline.OfflineQueue;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.Objects;

/**
 * 用 Redis 列表暂存用户离线时没来得及收到的消息。
 */
public final class RedisOfflineQueue implements OfflineQueue {
    private static final String DEFAULT_KEY_PREFIX = "mochat:offline:";

    private final RedisCommands<String, String> redisCommands;
    private final String keyPrefix;

    /**
     * 用默认键名前缀创建离线队列。
     */
    public RedisOfflineQueue(RedisCommands<String, String> redisCommands) {
        this(redisCommands, DEFAULT_KEY_PREFIX);
    }

    /**
     * 用指定键名前缀创建离线队列。
     */
    public RedisOfflineQueue(RedisCommands<String, String> redisCommands, String keyPrefix) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    /**
     * 把一条消息追加到用户离线队列尾部，并把队列长度裁到上限。
     */
    public void enqueue(long userId, String payload, int maxQueueSize) {
        Objects.requireNonNull(payload, "payload");
        if (maxQueueSize <= 0) {
            throw new IllegalArgumentException("maxQueueSize must be > 0");
        }

        String key = redisKey(userId);
        // 先加到队尾，再把最旧的超额消息从队头裁掉，这样重放时仍然是按旧到新取出来。
        redisCommands.rpush(key, payload);
        redisCommands.ltrim(key, -maxQueueSize, -1);
    }

    @Override
    /**
     * 从队头一次拿出一批待补发的离线消息。
     */
    public List<String> drain(long userId, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }

        String key = redisKey(userId);
        List<String> items = redisCommands.lpop(key, maxItems);
        return items == null ? List.of() : items;
    }

    /**
     * 拼出这个用户离线队列对应的 Redis 键名。
     */
    private String redisKey(long userId) {
        return keyPrefix + userId;
    }
}
