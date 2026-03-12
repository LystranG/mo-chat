package com.github.lystran.mochat.logic.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceTest {
    @Test
    void issuedSessionIsPersistedAsActiveAuthorityRecordAndResolvesUser() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );

        String sessionId = sessionService.issueSession(42L);
        String redisKey = "mochat:session:" + sessionId;

        assertTrue(redis.get(redisKey).startsWith("v1|ACTIVE|42|1|"));
        assertEquals(Optional.of(42L), sessionService.resolveUserId(sessionId));
        assertEquals(SessionAuthorityStatus.ACTIVE, sessionService.resolveAuthority(sessionId).status());
        assertEquals(1L, sessionService.resolveAuthority(sessionId).sessionVersion());
    }

    @Test
    void issuingSecondSessionForSameUserReplacesPreviousSessionAndBumpsVersion() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );

        String firstSessionId = sessionService.issueSession(42L);
        String secondSessionId = sessionService.issueSession(42L);

        assertEquals(SessionAuthorityStatus.REPLACED, sessionService.resolveAuthority(firstSessionId).status());
        assertEquals(1L, sessionService.resolveAuthority(firstSessionId).sessionVersion());
        assertEquals(SessionAuthorityStatus.ACTIVE, sessionService.resolveAuthority(secondSessionId).status());
        assertEquals(2L, sessionService.resolveAuthority(secondSessionId).sessionVersion());
    }

    @Test
    void sessionResolverAuthorityExposesReplacementAndExpiryFenceMetadata() {
        InMemoryRedis redis = new InMemoryRedis();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-11T00:00:00Z"));
        SessionResolver sessionResolver = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build(),
            "mochat:session:",
            "mochat:session-active-user:",
            clock,
            Duration.ofSeconds(5)
        );

        String firstSessionId = ((SessionService) sessionResolver).issueSession(42L);
        String secondSessionId = ((SessionService) sessionResolver).issueSession(42L);

        SessionAuthority replaced = sessionResolver.resolveAuthority(firstSessionId);
        SessionAuthority active = sessionResolver.resolveAuthority(secondSessionId);

        assertEquals(SessionAuthorityStatus.REPLACED, replaced.status());
        assertEquals(firstSessionId, replaced.sessionId());
        assertEquals(42L, replaced.userId());
        assertEquals(1L, replaced.sessionVersion());

        assertEquals(SessionAuthorityStatus.ACTIVE, active.status());
        assertEquals(secondSessionId, active.sessionId());
        assertEquals(42L, active.userId());
        assertEquals(2L, active.sessionVersion());

        clock.advance(Duration.ofSeconds(6));

        SessionAuthority expired = sessionResolver.resolveAuthority(secondSessionId);

        assertEquals(SessionAuthorityStatus.EXPIRED, expired.status());
        assertEquals(secondSessionId, expired.sessionId());
        assertEquals(42L, expired.userId());
        assertEquals(2L, expired.sessionVersion());
    }

    @Test
    void crossInstanceConcurrentIssuanceDoesNotAllowLosingSessionToBecomeActiveAfterWinnerRevocation() throws Exception {
        RacingInMemoryRedis redis = new RacingInMemoryRedis();
        SessionService firstInstance = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );
        SessionService secondInstance = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executorService.submit(() -> firstInstance.issueSession(42L));
            Future<String> second = executorService.submit(() -> secondInstance.issueSession(42L));

            String firstSessionId = first.get(5, TimeUnit.SECONDS);
            String secondSessionId = second.get(5, TimeUnit.SECONDS);
            String activePointer = redis.peek("mochat:session-active-user:42");
            assertTrue(activePointer != null && !activePointer.isBlank());

            String activeSessionId = activePointer.split("\\|", 2)[0];
            String replacedSessionId = activeSessionId.equals(firstSessionId) ? secondSessionId : firstSessionId;

            assertTrue(redis.peek("mochat:session:" + replacedSessionId).startsWith("v1|ACTIVE|42|1|"));

            firstInstance.revoke(activeSessionId);

            assertEquals(
                SessionAuthorityStatus.REPLACED,
                firstInstance.resolveAuthority(replacedSessionId).status()
            );
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void authorityRecordFailsClosedWhenActivePointerIsMissing() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );

        String sessionId = sessionService.issueSession(42L);
        redis.delete("mochat:session-active-user:42");

        assertEquals(SessionAuthorityStatus.REPLACED, sessionService.resolveAuthority(sessionId).status());
        assertTrue(sessionService.resolveUserId(sessionId).isEmpty());
    }

    @Test
    void resolveFallsBackToLegacyNumericRedisValue() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );
        redis.set("mochat:session:s-1", "52");

        assertEquals(Optional.of(52L), sessionService.resolveUserId("s-1"));
        assertEquals(1L, sessionService.resolveAuthority("s-1").sessionVersion());
    }

    @Test
    void resolveReturnsEmptyAfterRedisRevocationEvenWhenPreviouslyActive() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );
        String sessionId = sessionService.issueSession(52L);

        assertEquals(Optional.of(52L), sessionService.resolveUserId(sessionId));

        sessionService.revoke(sessionId);

        assertTrue(sessionService.resolveUserId(sessionId).isEmpty());
        assertEquals(SessionAuthorityStatus.INVALID, sessionService.resolveAuthority(sessionId).status());
    }

    @Test
    void resolveReturnsExpiredWhenAuthorityRecordAgeExceedsSessionTtl() {
        InMemoryRedis redis = new InMemoryRedis();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-11T00:00:00Z"));
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build(),
            "mochat:session:",
            "mochat:session-active-user:",
            clock,
            Duration.ofSeconds(5)
        );
        String sessionId = sessionService.issueSession(52L);

        clock.advance(Duration.ofSeconds(6));

        assertEquals(SessionAuthorityStatus.EXPIRED, sessionService.resolveAuthority(sessionId).status());
        assertTrue(sessionService.resolveUserId(sessionId).isEmpty());
    }

    @Test
    void resolveReturnsEmptyWhenRedisContainsInvalidPayload() {
        InMemoryRedis redis = new InMemoryRedis();
        SessionService sessionService = new SessionService(
            redis.commands(),
            Caffeine.newBuilder().maximumSize(1_000).build()
        );
        redis.set("mochat:session:s-1", "not-a-number");

        assertTrue(sessionService.resolveUserId("s-1").isEmpty());
        assertEquals(SessionAuthorityStatus.INVALID, sessionService.resolveAuthority("s-1").status());
    }

    private static final class InMemoryRedis {
        private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> values.get((String) args[0]);
                    case "set" -> {
                        values.put((String) args[0], (String) args[1]);
                        yield "OK";
                    }
                    case "del" -> {
                        long deleted = 0L;
                        Object rawKeys = args[0];
                        if (rawKeys instanceof String[] keys) {
                            for (String key : keys) {
                                deleted += values.remove(key) != null ? 1L : 0L;
                            }
                        } else {
                            deleted += values.remove((String) rawKeys) != null ? 1L : 0L;
                        }
                        yield deleted;
                    }
                    case "toString" -> "SessionServiceTestRedisCommands";
                    default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
                }
            );
        }

        private void set(String key, String value) {
            values.put(key, value);
        }

        private String get(String key) {
            return values.get(key);
        }

        private void delete(String key) {
            values.remove(key);
        }
    }

    private static final class RacingInMemoryRedis {
        private static final String ACTIVE_SESSION_KEY_PREFIX = "mochat:session-active-user:";

        private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();
        private final AtomicLong nullActivePointerReads = new AtomicLong();
        private final CountDownLatch secondNullActivePointerRead = new CountDownLatch(1);
        private final AtomicLong activePointerWrites = new AtomicLong();
        private final CountDownLatch secondActivePointerWrite = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> get((String) args[0]);
                    case "set" -> {
                        set((String) args[0], (String) args[1]);
                        yield "OK";
                    }
                    case "del" -> {
                        long deleted = 0L;
                        Object rawKeys = args[0];
                        if (rawKeys instanceof String[] keys) {
                            for (String key : keys) {
                                deleted += values.remove(key) != null ? 1L : 0L;
                            }
                        } else {
                            deleted += values.remove((String) rawKeys) != null ? 1L : 0L;
                        }
                        yield deleted;
                    }
                    case "toString" -> "SessionServiceTestRacingRedisCommands";
                    default -> throw new UnsupportedOperationException("Unsupported RedisCommands method: " + method.getName());
                }
            );
        }

        private String get(String key) throws InterruptedException {
            String value = values.get(key);
            if (value == null && key.startsWith(ACTIVE_SESSION_KEY_PREFIX)) {
                long readNumber = nullActivePointerReads.incrementAndGet();
                if (readNumber == 1L) {
                    secondNullActivePointerRead.await(500, TimeUnit.MILLISECONDS);
                } else if (readNumber == 2L) {
                    secondNullActivePointerRead.countDown();
                }
            }
            return value;
        }

        private void set(String key, String value) throws InterruptedException {
            if (key.startsWith(ACTIVE_SESSION_KEY_PREFIX)) {
                long writeNumber = activePointerWrites.incrementAndGet();
                if (writeNumber == 1L) {
                    secondActivePointerWrite.await(500, TimeUnit.MILLISECONDS);
                } else if (writeNumber == 2L) {
                    secondActivePointerWrite.countDown();
                }
            }
            values.put(key, value);
        }

        private String peek(String key) {
            return values.get(key);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong currentEpochMilli;

        private MutableClock(Instant initialInstant) {
            this.currentEpochMilli = new AtomicLong(initialInstant.toEpochMilli());
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentEpochMilli.get());
        }

        private void advance(Duration duration) {
            currentEpochMilli.addAndGet(duration.toMillis());
        }
    }
}
