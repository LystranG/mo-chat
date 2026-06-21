package com.github.lystran.mochat.call.service;

import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;


@Singleton
public final class CallSessionResolver {
    private static final String SESSION_KEY_PREFIX = "mochat:session:";
    private static final String RECORD_VERSION = "v1";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final RedisCommands<String, String> redisCommands;

    public CallSessionResolver(RedisCommands<String, String> redisCommands) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
    }

    public Optional<Long> resolveUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String value = redisCommands.get(SESSION_KEY_PREFIX + sessionId);
        if (value == null) {
            return Optional.empty();
        }
        return parseUserId(value);
    }

    private static Optional<Long> parseUserId(String value) {
        // 旧格式：纯数字
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // 继续按新格式解析
        }
        // 新格式：v1|STATUS|userId|sessionVersion|expiresAt
        String[] segments = value.split("\\|", 5);
        if (segments.length != 5 || !RECORD_VERSION.equals(segments[0])) {
            return Optional.empty();
        }
        if (!STATUS_ACTIVE.equals(segments[1])) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(segments[2]);
            return userId > 0L ? Optional.of(userId) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
