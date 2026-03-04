package com.github.lystran.mochat.persistence.cache;

import com.github.lystran.mochat.persistence.MessageRepository;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;

class GroupMessageCacheTest {
    @Test
    void keepsLatest500MessagesPerGroupAcrossRedisAndL1Cache() {
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        String redisKey = "mochat:group:messages:5001";
        TreeMap<Long, String> redisZset = new TreeMap<>();

        doAnswer(invocation -> {
            double score = invocation.getArgument(1);
            String value = invocation.getArgument(2);
            redisZset.put((long) score, value);
            return 1L;
        }).when(redisCommands).zadd(eq(redisKey), anyDouble(), anyString());
        doAnswer(invocation -> (long) redisZset.size())
            .when(redisCommands).zcard(eq(redisKey));
        doAnswer(invocation -> {
            long start = invocation.getArgument(1);
            long stop = invocation.getArgument(2);
            return evictByRank(redisZset, start, stop);
        }).when(redisCommands).zremrangebyrank(eq(redisKey), anyLong(), anyLong());

        GroupMessageCache groupMessageCache = new GroupMessageCache(redisCommands);
        for (int seq = 1; seq <= 505; seq++) {
            groupMessageCache.cache(groupMessage(5001L, seq));
        }

        List<GroupMessageCache.CachedGroupMessage> recentMessages = groupMessageCache.recentMessages(5001L);
        assertEquals(500, recentMessages.size());
        assertEquals(6L, recentMessages.get(0).seq());
        assertEquals(505L, recentMessages.get(recentMessages.size() - 1).seq());

        assertEquals(500, redisZset.size());
        assertEquals(6L, redisZset.firstKey());
        assertEquals(505L, redisZset.lastKey());
    }

    private static long evictByRank(TreeMap<Long, String> sortedMessages, long startRank, long stopRank) {
        if (sortedMessages.isEmpty() || startRank > stopRank) {
            return 0L;
        }

        if (startRank != 0L) {
            throw new IllegalArgumentException("test helper only supports start rank 0");
        }

        long toRemove = stopRank - startRank + 1;
        long removed = 0L;
        while (!sortedMessages.isEmpty() && removed < toRemove) {
            sortedMessages.pollFirstEntry();
            removed++;
        }
        return removed;
    }

    private static MessageRepository.PersistedMessage groupMessage(long groupId, long seq) {
        return new MessageRepository.PersistedMessage(
            10_000L + seq,
            groupId,
            seq,
            900_000L + seq,
            "group",
            123L,
            null,
            null,
            groupId,
            200_000L + seq,
            "payload-" + seq
        );
    }
}
