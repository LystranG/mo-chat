package com.github.lystran.mochat.call.service;

import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Optional;

@Singleton
public final class CallSessionResolver {
    private static final String SESSION_KEY_PREFIX = "mochat:session:";

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
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
