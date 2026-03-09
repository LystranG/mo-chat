package com.github.lystran.mochat.persistence.cache;

import com.github.lystran.mochat.persistence.MessageRepository;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupMessageCacheTest {
    @Test
    void keepsLatest500MessagesPerGroupAcrossRedisAndL1Cache() {
        String redisKey = "mochat:group:messages:5001";
        FakeRedisZset redisZset = new FakeRedisZset();
        RedisCommands<String, String> redisCommands = mockRedisCommands(redisKey, redisZset);

        GroupMessageCache groupMessageCache = new GroupMessageCache(redisCommands);
        for (int seq = 1; seq <= 505; seq++) {
            groupMessageCache.cache(groupMessage(5001L, seq));
        }

        List<GroupMessageCache.CachedGroupMessage> recentMessages = groupMessageCache.recentMessages(5001L);
        assertEquals(500, recentMessages.size());
        assertEquals(6L, recentMessages.get(0).seq());
        assertEquals(505L, recentMessages.get(recentMessages.size() - 1).seq());

        assertEquals(500, redisZset.size());
        assertEquals(6L, redisZset.lowestScore());
        assertEquals(505L, redisZset.highestScore());
    }

    @Test
    void storesDistinctRedisMembersWhenPayloadsAreEqual() {
        String redisKey = "mochat:group:messages:5001";
        FakeRedisZset redisZset = new FakeRedisZset();
        RedisCommands<String, String> redisCommands = mockRedisCommands(redisKey, redisZset);

        GroupMessageCache groupMessageCache = new GroupMessageCache(redisCommands);
        groupMessageCache.cache(groupMessage(5001L, 101L, "same-payload"));
        groupMessageCache.cache(groupMessage(5001L, 102L, "same-payload"));

        assertEquals(2, redisZset.size());
        assertEquals(101L, redisZset.lowestScore());
        assertEquals(102L, redisZset.highestScore());
    }

    @Test
    void warmsL1CacheFromRedisOnCacheMiss() {
        String redisKey = "mochat:group:messages:6001";
        @SuppressWarnings("unchecked")
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);
        when(redisCommands.zrange(redisKey, 0, -1)).thenReturn(List.of("8|payload-8", "9|payload-9"));

        GroupMessageCache groupMessageCache = new GroupMessageCache(redisCommands);

        List<GroupMessageCache.CachedGroupMessage> firstRead = groupMessageCache.recentMessages(6001L);
        List<GroupMessageCache.CachedGroupMessage> secondRead = groupMessageCache.recentMessages(6001L);

        assertEquals(List.of(
            new GroupMessageCache.CachedGroupMessage(8L, "payload-8"),
            new GroupMessageCache.CachedGroupMessage(9L, "payload-9")
        ), firstRead);
        assertEquals(firstRead, secondRead);
        verify(redisCommands, times(1)).zrange(redisKey, 0, -1);
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> mockRedisCommands(String redisKey, FakeRedisZset redisZset) {
        RedisCommands<String, String> redisCommands = mock(RedisCommands.class);

        doAnswer(invocation -> {
            double score = invocation.getArgument(1);
            String value = invocation.getArgument(2);
            return redisZset.zadd(score, value);
        }).when(redisCommands).zadd(eq(redisKey), anyDouble(), anyString());
        doAnswer(invocation -> redisZset.size())
            .when(redisCommands).zcard(eq(redisKey));
        doAnswer(invocation -> {
            long start = invocation.getArgument(1);
            long stop = invocation.getArgument(2);
            return redisZset.zremrangebyrank(start, stop);
        }).when(redisCommands).zremrangebyrank(eq(redisKey), anyLong(), anyLong());

        return redisCommands;
    }

    private static MessageRepository.PersistedMessage groupMessage(long groupId, long seq) {
        return groupMessage(groupId, seq, "payload-" + seq);
    }

    private static MessageRepository.PersistedMessage groupMessage(long groupId, long seq, String payloadBase64) {
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
            payloadBase64
        );
    }

    private static final class FakeRedisZset {
        private final Map<String, Double> memberToScore = new HashMap<>();

        long zadd(double score, String member) {
            Double previous = memberToScore.put(member, score);
            return previous == null ? 1L : 0L;
        }

        long zremrangebyrank(long startRank, long stopRank) {
            if (memberToScore.isEmpty() || startRank > stopRank || stopRank < 0) {
                return 0L;
            }

            List<Map.Entry<String, Double>> ordered = orderedEntries();
            int from = (int) Math.max(0, startRank);
            if (from >= ordered.size()) {
                return 0L;
            }
            int to = (int) Math.min(ordered.size() - 1, stopRank);
            long removed = 0L;
            for (int index = from; index <= to; index++) {
                memberToScore.remove(ordered.get(index).getKey());
                removed++;
            }
            return removed;
        }

        long size() {
            return memberToScore.size();
        }

        long lowestScore() {
            return orderedEntries().isEmpty() ? 0L : orderedEntries().get(0).getValue().longValue();
        }

        long highestScore() {
            List<Map.Entry<String, Double>> ordered = orderedEntries();
            return ordered.isEmpty() ? 0L : ordered.get(ordered.size() - 1).getValue().longValue();
        }

        private List<Map.Entry<String, Double>> orderedEntries() {
            List<Map.Entry<String, Double>> ordered = new ArrayList<>(memberToScore.entrySet());
            ordered.sort(
                Comparator.comparing(Map.Entry<String, Double>::getValue)
                    .thenComparing(Map.Entry::getKey)
            );
            return ordered;
        }
    }
}
