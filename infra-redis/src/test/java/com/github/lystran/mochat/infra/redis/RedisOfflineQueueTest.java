package com.github.lystran.mochat.infra.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOfflineQueueTest {
    @Test
    void drainUsesAtomicLpopWithCount() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        when(redisCommands.lpop("mochat:offline:42", 3L)).thenReturn(List.of("a", "b"));

        var queue = new RedisOfflineQueue(redisCommands);
        var drained = queue.drain(42L, 3);

        assertEquals(List.of("a", "b"), drained);
        verify(redisCommands).lpop("mochat:offline:42", 3L);
        verify(redisCommands, never()).lrange(anyString(), anyLong(), anyLong());
        verify(redisCommands, never()).ltrim(anyString(), anyLong(), anyLong());
    }
}
