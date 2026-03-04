package com.github.lystran.mochat.infra.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisIdempotencyStoreTest {
    @Test
    void storeIfAbsentUsesDefaultTtlAndNx() throws Exception {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        var store = new RedisIdempotencyStore(redisCommands);

        store.storeIfAbsent(11L, 12L, 13L, 14L);

        var setArgsCaptor = ArgumentCaptor.forClass(SetArgs.class);
        verify(redisCommands).set(eq("mochat:idempotency:11:12"), eq("13:14"), setArgsCaptor.capture());
        assertTrue(readBooleanField(setArgsCaptor.getValue(), "nx"));
        assertEquals(300_000L, readLongField(setArgsCaptor.getValue(), "px"));
    }

    @Test
    void storeIfAbsentUsesConfiguredTtl() throws Exception {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        var store = new RedisIdempotencyStore(redisCommands, "custom:", Duration.ofSeconds(30));

        store.storeIfAbsent(21L, 22L, 23L, 24L);

        var setArgsCaptor = ArgumentCaptor.forClass(SetArgs.class);
        verify(redisCommands).set(eq("custom:21:22"), eq("23:24"), setArgsCaptor.capture());
        assertTrue(readBooleanField(setArgsCaptor.getValue(), "nx"));
        assertEquals(30_000L, readLongField(setArgsCaptor.getValue(), "px"));
    }

    private static boolean readBooleanField(SetArgs setArgs, String fieldName) throws Exception {
        Field field = SetArgs.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(setArgs);
    }

    private static long readLongField(SetArgs setArgs, String fieldName) throws Exception {
        Field field = SetArgs.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        var value = (Long) field.get(setArgs);
        return value == null ? 0L : value;
    }
}
