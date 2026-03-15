package com.github.lystran.mochat.logic.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责签发、解析和撤销登录 session，并让 Redis 与本地缓存保持一致。
 */
@Singleton
public final class SessionService implements SessionResolver {
    private static final String DEFAULT_SESSION_KEY_PREFIX = "mochat:session:";

    private final RedisCommands<String, String> redisCommands;
    // 这份本地缓存只负责加速，真正决定 session 是否有效的还是 Redis。
    private final Cache<String, Long> l2Cache;
    private final String sessionKeyPrefix;

    /**
     * 使用默认本地缓存配置构造 session 服务。
     */
    public SessionService(RedisCommands<String, String> redisCommands) {
        this(
            redisCommands,
            Caffeine.newBuilder().maximumSize(100_000).build(),
            DEFAULT_SESSION_KEY_PREFIX
        );
    }

    /**
     * 使用指定本地缓存和默认 key 前缀构造 session 服务。
     */
    public SessionService(RedisCommands<String, String> redisCommands, Cache<String, Long> l2Cache) {
        this(redisCommands, l2Cache, DEFAULT_SESSION_KEY_PREFIX);
    }

    /**
     * 使用完整依赖构造 session 服务。
     */
    public SessionService(
        RedisCommands<String, String> redisCommands,
        Cache<String, Long> l2Cache,
        String sessionKeyPrefix
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.l2Cache = Objects.requireNonNull(l2Cache, "l2Cache");
        this.sessionKeyPrefix = Objects.requireNonNull(sessionKeyPrefix, "sessionKeyPrefix");
    }

    /**
     * 为指定用户签发新的 session，并同步写入 Redis 和本地缓存。
     */
    public String issueSession(long userId) {
        String sessionId = UUID.randomUUID().toString();
        redisCommands.set(redisKey(sessionId), Long.toString(userId));
        l2Cache.put(sessionId, userId);
        return sessionId;
    }

    /**
     * 解析 session 对应的用户 ID。
     */
    @Override
    public Optional<Long> resolveUserId(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }

        // Redis 是 session 真相源，进程内 Caffeine 只做读加速；先查 Redis 才能让 revoke 和异常值修正立即生效。
        String redisValue = redisCommands.get(redisKey(sessionId));
        if (redisValue == null) {
            l2Cache.invalidate(sessionId);
            return Optional.empty();
        }

        try {
            long userId = Long.parseLong(redisValue);
            Long cachedUserId = l2Cache.getIfPresent(sessionId);
            if (cachedUserId == null || cachedUserId != userId) {
                l2Cache.put(sessionId, userId);
            }
            return Optional.of(userId);
        } catch (NumberFormatException ignored) {
            l2Cache.invalidate(sessionId);
            return Optional.empty();
        }
    }

    /**
     * 撤销指定 session，并清理 Redis 与本地缓存中的对应条目。
     */
    public void revoke(String sessionId) {
        if (!hasText(sessionId)) {
            return;
        }

        redisCommands.del(redisKey(sessionId));
        l2Cache.invalidate(sessionId);
    }

    /**
     * 生成 Redis 中存放 session 的完整 key。
     */
    private String redisKey(String sessionId) {
        return sessionKeyPrefix + sessionId;
    }

    /**
     * 判断字符串是否包含有效文本内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
