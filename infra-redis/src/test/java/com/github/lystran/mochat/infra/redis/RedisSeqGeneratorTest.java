package com.github.lystran.mochat.infra.redis;

import com.github.lystran.mochat.common.lock.JucConversationLock;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class RedisSeqGeneratorTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379);

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;

    @BeforeEach
    void setUp() {
        String redisUri = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        redisClient = RedisClient.create(redisUri);
        connection = redisClient.connect();
    }

    @AfterEach
    void tearDown() {
        connection.close();
        redisClient.shutdown();
    }

    @Test
    void nextReturnsIncreasingValuesForConversation() {
        var generator = new RedisConversationSeqGenerator(
            connection.sync(),
            new JucConversationLock(),
            conversationId -> 0L
        );

        long first = generator.next(42L);
        long second = generator.next(42L);

        assertEquals(1L, first);
        assertEquals(2L, second);
    }

    @Test
    void bootstrapsFromSeedProviderWhenCounterMissing() {
        var seedCalls = new AtomicInteger();
        var generator = new RedisConversationSeqGenerator(
            connection.sync(),
            new JucConversationLock(),
            conversationId -> {
                seedCalls.incrementAndGet();
                return 100L;
            }
        );

        long first = generator.next(7L);
        long second = generator.next(7L);

        assertEquals(101L, first);
        assertEquals(102L, second);
        assertEquals(1, seedCalls.get());
    }
}
