package com.github.lystran.mochat.persistence.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.lystran.mochat.persistence.MessageRepository;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

public final class GroupMessageCache {
    private static final String DEFAULT_REDIS_KEY_PREFIX = "mochat:group:messages:";
    private static final int DEFAULT_MAX_MESSAGES_PER_GROUP = 500;
    private static final long DEFAULT_L1_MAX_GROUPS = 50_000L;
    private static final char REDIS_MEMBER_DELIMITER = '|';

    private final RedisCommands<String, String> redisCommands;
    private final Cache<Long, NavigableMap<Long, CachedGroupMessage>> l1Cache;
    private final String redisKeyPrefix;
    private final int maxMessagesPerGroup;

    public GroupMessageCache(RedisCommands<String, String> redisCommands) {
        this(
            redisCommands,
            Caffeine.newBuilder().maximumSize(DEFAULT_L1_MAX_GROUPS).build(),
            DEFAULT_REDIS_KEY_PREFIX,
            DEFAULT_MAX_MESSAGES_PER_GROUP
        );
    }

    public GroupMessageCache(
        RedisCommands<String, String> redisCommands,
        Cache<Long, NavigableMap<Long, CachedGroupMessage>> l1Cache,
        String redisKeyPrefix,
        int maxMessagesPerGroup
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.l1Cache = Objects.requireNonNull(l1Cache, "l1Cache");
        this.redisKeyPrefix = Objects.requireNonNull(redisKeyPrefix, "redisKeyPrefix");
        if (maxMessagesPerGroup <= 0) {
            throw new IllegalArgumentException("maxMessagesPerGroup must be > 0");
        }
        this.maxMessagesPerGroup = maxMessagesPerGroup;
    }

    public void cache(MessageRepository.PersistedMessage message) {
        Objects.requireNonNull(message, "message");
        if (!isGroupMessage(message)) {
            return;
        }

        cacheGroupMessage(message.groupId(), message.seq(), message.payloadBase64());
    }

    public void cacheGroupMessage(long groupId, long seq, String payloadBase64) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("groupId must be > 0");
        }
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be > 0");
        }
        Objects.requireNonNull(payloadBase64, "payloadBase64");

        String key = redisKey(groupId);
        redisCommands.zadd(key, (double) seq, redisMember(seq, payloadBase64));
        trimRedisWindow(key);

        l1Cache.asMap().compute(groupId, (ignored, existing) -> {
            NavigableMap<Long, CachedGroupMessage> messages =
                existing == null ? new ConcurrentSkipListMap<>() : existing;
            messages.put(seq, new CachedGroupMessage(seq, payloadBase64));
            trimL1Window(messages);
            return messages;
        });
    }

    public List<CachedGroupMessage> recentMessages(long groupId) {
        NavigableMap<Long, CachedGroupMessage> messages = l1Cache.getIfPresent(groupId);
        if (messages == null || messages.isEmpty()) {
            messages = warmFromRedis(groupId);
        }
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return List.copyOf(messages.values());
    }

    private NavigableMap<Long, CachedGroupMessage> warmFromRedis(long groupId) {
        List<String> redisMembers = redisCommands.zrange(redisKey(groupId), 0, -1);
        if (redisMembers == null || redisMembers.isEmpty()) {
            return null;
        }

        NavigableMap<Long, CachedGroupMessage> warmed = new ConcurrentSkipListMap<>();
        for (String member : redisMembers) {
            CachedGroupMessage message = parseRedisMember(member);
            if (message != null) {
                warmed.put(message.seq(), message);
            }
        }
        if (warmed.isEmpty()) {
            return null;
        }
        trimL1Window(warmed);
        l1Cache.put(groupId, warmed);
        return warmed;
    }

    private void trimRedisWindow(String key) {
        Long total = redisCommands.zcard(key);
        if (total == null || total <= maxMessagesPerGroup) {
            return;
        }

        long overflow = total - maxMessagesPerGroup;
        redisCommands.zremrangebyrank(key, 0, overflow - 1);
    }

    private void trimL1Window(NavigableMap<Long, CachedGroupMessage> messages) {
        while (messages.size() > maxMessagesPerGroup) {
            messages.pollFirstEntry();
        }
    }

    private String redisKey(long groupId) {
        return redisKeyPrefix + groupId;
    }

    private static String redisMember(long seq, String payloadBase64) {
        return seq + String.valueOf(REDIS_MEMBER_DELIMITER) + payloadBase64;
    }

    private static CachedGroupMessage parseRedisMember(String redisMember) {
        if (redisMember == null || redisMember.isBlank()) {
            return null;
        }
        int delimiterIndex = redisMember.indexOf(REDIS_MEMBER_DELIMITER);
        if (delimiterIndex <= 0 || delimiterIndex == redisMember.length() - 1) {
            return null;
        }

        try {
            long seq = Long.parseLong(redisMember.substring(0, delimiterIndex));
            String payloadBase64 = redisMember.substring(delimiterIndex + 1);
            return new CachedGroupMessage(seq, payloadBase64);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isGroupMessage(MessageRepository.PersistedMessage message) {
        return message.groupId() != null && "group".equals(message.kind());
    }

    public record CachedGroupMessage(long seq, String payloadBase64) {
        public CachedGroupMessage {
            if (seq <= 0) {
                throw new IllegalArgumentException("seq must be > 0");
            }
            Objects.requireNonNull(payloadBase64, "payloadBase64");
        }
    }
}
