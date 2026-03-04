package com.github.lystran.mochat.logic.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {
    @Test
    void issuedSessionIsPersistedInRedisAndCachedLocally() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        SessionService sessionService = new SessionService(redisCommands, Caffeine.newBuilder().maximumSize(1_000).build());

        String sessionId = sessionService.issueSession(42L);
        String redisKey = "mochat:session:" + sessionId;
        when(redisCommands.get(redisKey)).thenReturn("42");

        verify(redisCommands).set(redisKey, "42");
        assertEquals(Optional.of(42L), sessionService.resolveUserId(sessionId));
        verify(redisCommands).get(redisKey);
    }

    @Test
    void resolveFallsBackToRedisAndHydratesCache() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        SessionService sessionService = new SessionService(redisCommands, Caffeine.newBuilder().maximumSize(1_000).build());

        when(redisCommands.get("mochat:session:s-1")).thenReturn("52");

        assertEquals(Optional.of(52L), sessionService.resolveUserId("s-1"));
        assertEquals(Optional.of(52L), sessionService.resolveUserId("s-1"));
        verify(redisCommands, times(2)).get("mochat:session:s-1");
    }

    @Test
    void resolveReturnsEmptyAfterRedisRevocationEvenWhenCachedLocally() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        SessionService sessionService = new SessionService(redisCommands, Caffeine.newBuilder().maximumSize(1_000).build());

        when(redisCommands.get("mochat:session:s-1")).thenReturn("52", (String) null);

        assertEquals(Optional.of(52L), sessionService.resolveUserId("s-1"));
        assertTrue(sessionService.resolveUserId("s-1").isEmpty());
        verify(redisCommands, times(2)).get("mochat:session:s-1");
    }

    @Test
    void resolveReturnsEmptyWhenRedisContainsInvalidUserId() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        SessionService sessionService = new SessionService(redisCommands, Caffeine.newBuilder().maximumSize(1_000).build());

        when(redisCommands.get("mochat:session:s-1")).thenReturn("not-a-number");

        assertTrue(sessionService.resolveUserId("s-1").isEmpty());
    }
}
