package com.github.lystran.mochat.infra.redis;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void enqueueKeepsNewest50ItemsAfterOverflow() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        String key = "mochat:offline:42";
        var stored = new ArrayList<String>();

        doAnswer(invocation -> {
            stored.add(invocation.getArgument(1));
            return (long) stored.size();
        }).when(redisCommands).rpush(eq(key), anyString());
        doAnswer(invocation -> {
            long start = invocation.getArgument(1);
            long stop = invocation.getArgument(2);
            trim(stored, start, stop);
            return "OK";
        }).when(redisCommands).ltrim(eq(key), anyLong(), anyLong());
        doAnswer(invocation -> {
            long count = invocation.getArgument(1);
            int drainCount = (int) Math.min(count, stored.size());
            var drained = new ArrayList<>(stored.subList(0, drainCount));
            stored.subList(0, drainCount).clear();
            return drained;
        }).when(redisCommands).lpop(eq(key), eq(100L));

        var queue = new RedisOfflineQueue(redisCommands);
        for (int i = 1; i <= 55; i++) {
            queue.enqueue(42L, "msg-" + i, 50);
        }

        var drained = queue.drain(42L, 100);
        var expected = new ArrayList<String>();
        for (int i = 6; i <= 55; i++) {
            expected.add("msg-" + i);
        }

        assertEquals(expected, drained);
        verify(redisCommands, times(55)).ltrim(key, -50L, -1L);
    }

    private static void trim(List<String> values, long startInclusive, long stopInclusive) {
        int size = values.size();
        int normalizedStart = normalizeIndex(startInclusive, size);
        int normalizedStop = normalizeIndex(stopInclusive, size);

        if (size == 0 || normalizedStart >= size || normalizedStart > normalizedStop) {
            values.clear();
            return;
        }

        int from = Math.max(0, normalizedStart);
        int to = Math.min(size - 1, normalizedStop);
        var kept = new ArrayList<>(values.subList(from, to + 1));
        values.clear();
        values.addAll(kept);
    }

    private static int normalizeIndex(long index, int size) {
        return index >= 0 ? (int) index : size + (int) index;
    }
}
