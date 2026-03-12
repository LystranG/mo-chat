package com.github.lystran.mochat.logic.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.lystran.mochat.common.session.ResolvedSession;
import com.github.lystran.mochat.common.session.SessionAuthority;
import com.github.lystran.mochat.common.session.SessionAuthorityStatus;
import com.github.lystran.mochat.common.session.SessionResolver;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Singleton
public final class SessionService implements SessionResolver {
    private static final String DEFAULT_SESSION_KEY_PREFIX = "mochat:session:";
    private static final String DEFAULT_ACTIVE_SESSION_KEY_PREFIX = "mochat:session-active-user:";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofDays(30);
    private static final int ISSUE_SESSION_STRIPE_COUNT = 64;
    private static final String RECORD_VERSION = "v1";

    private final RedisCommands<String, String> redisCommands;
    private final Cache<String, Long> l2Cache;
    private final String sessionKeyPrefix;
    private final String activeSessionKeyPrefix;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Object[] issueSessionStripes;

    public SessionService(RedisCommands<String, String> redisCommands) {
        this(
            redisCommands,
            Caffeine.newBuilder().maximumSize(100_000).build()
        );
    }

    public SessionService(RedisCommands<String, String> redisCommands, Cache<String, Long> l2Cache) {
        this(
            redisCommands,
            l2Cache,
            DEFAULT_SESSION_KEY_PREFIX,
            DEFAULT_ACTIVE_SESSION_KEY_PREFIX,
            Clock.systemUTC(),
            DEFAULT_SESSION_TTL
        );
    }

    public SessionService(
        RedisCommands<String, String> redisCommands,
        Cache<String, Long> l2Cache,
        String sessionKeyPrefix
    ) {
        this(
            redisCommands,
            l2Cache,
            sessionKeyPrefix,
            DEFAULT_ACTIVE_SESSION_KEY_PREFIX,
            Clock.systemUTC(),
            DEFAULT_SESSION_TTL
        );
    }

    SessionService(
        RedisCommands<String, String> redisCommands,
        Cache<String, Long> l2Cache,
        String sessionKeyPrefix,
        String activeSessionKeyPrefix,
        Clock clock,
        Duration sessionTtl
    ) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands");
        this.l2Cache = Objects.requireNonNull(l2Cache, "l2Cache");
        this.sessionKeyPrefix = Objects.requireNonNull(sessionKeyPrefix, "sessionKeyPrefix");
        this.activeSessionKeyPrefix = Objects.requireNonNull(activeSessionKeyPrefix, "activeSessionKeyPrefix");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        this.issueSessionStripes = createIssueSessionStripes();
    }

    public String issueSession(long userId) {
        synchronized (issueSessionStripe(userId)) {
            String sessionId = UUID.randomUUID().toString();
            ActiveSessionPointer previousActiveSession = readActiveSessionPointer(userId);
            long sessionVersion = previousActiveSession == null ? 1L : previousActiveSession.sessionVersion() + 1L;
            long expiresAtEpochMilli = clock.millis() + sessionTtl.toMillis();
            StoredSession newSession = StoredSession.active(sessionId, userId, sessionVersion, expiresAtEpochMilli);
            if (previousActiveSession != null) {
                markSessionReplaced(previousActiveSession.sessionId(), userId, previousActiveSession.sessionVersion());
                l2Cache.invalidate(previousActiveSession.sessionId());
            }
            redisCommands.set(redisKey(sessionId), serialize(newSession));
            redisCommands.set(activeSessionKey(userId), serialize(previousOrCurrent(sessionId, sessionVersion)));
            l2Cache.put(sessionId, userId);
            return sessionId;
        }
    }

    public SessionAuthority resolveAuthority(String sessionId) {
        if (!hasText(sessionId)) {
            return SessionAuthority.invalid(sessionId);
        }

        StoredSession storedSession = readStoredSession(sessionId);
        if (storedSession == null) {
            l2Cache.invalidate(sessionId);
            return SessionAuthority.invalid(sessionId);
        }
        if (storedSession.userId() <= 0L) {
            l2Cache.invalidate(sessionId);
            return SessionAuthority.invalid(sessionId);
        }
        if (storedSession.expiresAtEpochMilli() <= clock.millis()) {
            StoredSession expired = storedSession.withStatus(SessionAuthorityStatus.EXPIRED);
            redisCommands.set(redisKey(sessionId), serialize(expired));
            clearActivePointerIfMatches(expired.asAuthority());
            l2Cache.invalidate(sessionId);
            return expired.asAuthority();
        }
        if (storedSession.status() == SessionAuthorityStatus.REPLACED) {
            l2Cache.invalidate(sessionId);
            return storedSession.asAuthority();
        }

        ActiveSessionPointer activeSessionPointer = readActiveSessionPointer(storedSession.userId());
        if (storedSession.requiresActivePointer() && activeSessionPointer == null) {
            return replaceStoredSession(storedSession);
        }
        if (activeSessionPointer != null
            && (!sessionId.equals(activeSessionPointer.sessionId())
            || storedSession.sessionVersion() != activeSessionPointer.sessionVersion())) {
            return replaceStoredSession(storedSession);
        }

        l2Cache.put(sessionId, storedSession.userId());
        return storedSession.asAuthority();
    }

    @Override
    public Optional<ResolvedSession> resolveSession(String sessionId) {
        return resolveAuthority(sessionId).asResolvedSession();
    }

    public void revoke(String sessionId) {
        if (!hasText(sessionId)) {
            return;
        }

        StoredSession storedSession = readStoredSession(sessionId);
        redisCommands.del(redisKey(sessionId));
        if (storedSession != null) {
            clearActivePointerIfMatches(storedSession.asAuthority());
        }
        l2Cache.invalidate(sessionId);
    }

    private String redisKey(String sessionId) {
        return sessionKeyPrefix + sessionId;
    }

    private String activeSessionKey(long userId) {
        return activeSessionKeyPrefix + userId;
    }

    private StoredSession readStoredSession(String sessionId) {
        String redisValue = redisCommands.get(redisKey(sessionId));
        if (redisValue == null) {
            return null;
        }
        try {
            long legacyUserId = Long.parseLong(redisValue);
            return StoredSession.active(sessionId, legacyUserId, 1L, Long.MAX_VALUE, false);
        } catch (NumberFormatException ignored) {
            String[] segments = redisValue.split("\\|", 5);
            if (segments.length != 5 || !RECORD_VERSION.equals(segments[0])) {
                return null;
            }
            try {
                return new StoredSession(
                    SessionAuthorityStatus.valueOf(segments[1]),
                    sessionId,
                    Long.parseLong(segments[2]),
                    Long.parseLong(segments[3]),
                    Long.parseLong(segments[4]),
                    true
                );
            } catch (IllegalArgumentException ignoredToo) {
                return null;
            }
        }
    }

    private ActiveSessionPointer readActiveSessionPointer(long userId) {
        String redisValue = redisCommands.get(activeSessionKey(userId));
        if (redisValue == null) {
            return null;
        }
        String[] segments = redisValue.split("\\|", 2);
        if (segments.length != 2 || !hasText(segments[0])) {
            return null;
        }
        try {
            return new ActiveSessionPointer(segments[0], Long.parseLong(segments[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void markSessionReplaced(String sessionId, long userId, long sessionVersion) {
        StoredSession previousSession = readStoredSession(sessionId);
        StoredSession replaced = previousSession == null
            ? StoredSession.replaced(sessionId, userId, sessionVersion, clock.millis() + sessionTtl.toMillis())
            : previousSession.withStatus(SessionAuthorityStatus.REPLACED);
        redisCommands.set(redisKey(sessionId), serialize(replaced));
    }

    private SessionAuthority replaceStoredSession(StoredSession storedSession) {
        StoredSession replaced = storedSession.withStatus(SessionAuthorityStatus.REPLACED);
        if (storedSession.status() != SessionAuthorityStatus.REPLACED) {
            redisCommands.set(redisKey(storedSession.sessionId()), serialize(replaced));
        }
        l2Cache.invalidate(storedSession.sessionId());
        return replaced.asAuthority();
    }

    private void clearActivePointerIfMatches(SessionAuthority authority) {
        if (authority.userId() <= 0L) {
            return;
        }
        ActiveSessionPointer activeSessionPointer = readActiveSessionPointer(authority.userId());
        if (activeSessionPointer != null
            && authority.sessionId().equals(activeSessionPointer.sessionId())
            && authority.sessionVersion() == activeSessionPointer.sessionVersion()) {
            redisCommands.del(activeSessionKey(authority.userId()));
        }
    }

    private static String serialize(StoredSession storedSession) {
        return String.join(
            "|",
            RECORD_VERSION,
            storedSession.status().name(),
            Long.toString(storedSession.userId()),
            Long.toString(storedSession.sessionVersion()),
            Long.toString(storedSession.expiresAtEpochMilli())
        );
    }

    private static String serialize(ActiveSessionPointer activeSessionPointer) {
        return activeSessionPointer.sessionId() + "|" + activeSessionPointer.sessionVersion();
    }

    private static ActiveSessionPointer previousOrCurrent(String sessionId, long sessionVersion) {
        return new ActiveSessionPointer(sessionId, sessionVersion);
    }

    private Object issueSessionStripe(long userId) {
        return issueSessionStripes[Math.floorMod(Long.hashCode(userId), issueSessionStripes.length)];
    }

    private static Object[] createIssueSessionStripes() {
        Object[] stripes = new Object[ISSUE_SESSION_STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record StoredSession(
        SessionAuthorityStatus status,
        String sessionId,
        long userId,
        long sessionVersion,
        long expiresAtEpochMilli,
        boolean requiresActivePointer
    ) {
        private static StoredSession active(
            String sessionId,
            long userId,
            long sessionVersion,
            long expiresAtEpochMilli
        ) {
            return active(sessionId, userId, sessionVersion, expiresAtEpochMilli, true);
        }

        private static StoredSession active(
            String sessionId,
            long userId,
            long sessionVersion,
            long expiresAtEpochMilli,
            boolean requiresActivePointer
        ) {
            return new StoredSession(
                SessionAuthorityStatus.ACTIVE,
                sessionId,
                userId,
                sessionVersion,
                expiresAtEpochMilli,
                requiresActivePointer
            );
        }

        private static StoredSession replaced(
            String sessionId,
            long userId,
            long sessionVersion,
            long expiresAtEpochMilli
        ) {
            return new StoredSession(
                SessionAuthorityStatus.REPLACED,
                sessionId,
                userId,
                sessionVersion,
                expiresAtEpochMilli,
                true
            );
        }

        private SessionAuthority asAuthority() {
            return switch (status) {
                case ACTIVE -> SessionAuthority.active(sessionId, userId, sessionVersion);
                case INVALID -> SessionAuthority.invalid(sessionId);
                case EXPIRED -> SessionAuthority.expired(sessionId, userId, sessionVersion);
                case REPLACED -> SessionAuthority.replaced(sessionId, userId, sessionVersion);
            };
        }

        private StoredSession withStatus(SessionAuthorityStatus resolvedStatus) {
            return new StoredSession(resolvedStatus, sessionId, userId, sessionVersion, expiresAtEpochMilli, requiresActivePointer);
        }
    }

    private record ActiveSessionPointer(String sessionId, long sessionVersion) {
    }
}
