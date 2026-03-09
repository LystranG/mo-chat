package com.github.lystran.mochat.logic.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public final class SessionService implements SessionResolver {
    private static final String DEFAULT_SESSION_KEY_PREFIX = "mochat:session:";

    private final RedisCommands<String, String> redisCommands;
    private final Cache<String, Long> l2Cache;
    private final String sessionKeyPrefix;

    public SessionService(RedisCommands<String, String> redisCommands) {
        this(
            redisCommands,
            Caffeine.newBuilder().maximumSize(100_000).build(),
            DEFAULT_SESSION_KEY_PREFIX
        );
    }

    public SessionService(RedisCommands<String, String> redisCommands, Cache<String, Long> l2Cache) {
        this(redisCommands, l2Cache, DEFAULT_SESSION_KEY_PREFIX);
    }

    public SessionService(
        RedisCommands<String, String> redisCommands,
        Cache<String, Long> l2Cache,
        String sessionKeyPrefix
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.l2Cache = Objects.requireNonNull(l2Cache, "l2Cache");
        this.sessionKeyPrefix = Objects.requireNonNull(sessionKeyPrefix, "sessionKeyPrefix");
    }

    public String issueSession(long userId) {
        String sessionId = UUID.randomUUID().toString();
        redisCommands.set(redisKey(sessionId), Long.toString(userId));
        l2Cache.put(sessionId, userId);
        return sessionId;
    }

    @Override
    public Optional<Long> resolveUserId(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }

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

    public void revoke(String sessionId) {
        if (!hasText(sessionId)) {
            return;
        }

        redisCommands.del(redisKey(sessionId));
        l2Cache.invalidate(sessionId);
    }

    private String redisKey(String sessionId) {
        return sessionKeyPrefix + sessionId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
