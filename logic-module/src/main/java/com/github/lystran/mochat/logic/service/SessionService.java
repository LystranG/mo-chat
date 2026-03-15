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

/**
 * 负责签发、校验和撤销登录会话。
 * Redis 保存最终有效状态，本地缓存只负责减少重复读取。
 */
@Singleton
public final class SessionService implements SessionResolver {
    private static final String DEFAULT_SESSION_KEY_PREFIX = "mochat:session:";
    // 这个 key 记录“某个用户当前到底以哪一个 session 为准”。
    private static final String DEFAULT_ACTIVE_SESSION_KEY_PREFIX = "mochat:session-active-user:";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofDays(30);
    // 按用户分桶上锁，避免同一用户并发签发出两个新 session。
    private static final int ISSUE_SESSION_STRIPE_COUNT = 64;
    // Redis 里当前会话记录的序列化版本，方便兼容旧格式。
    private static final String RECORD_VERSION = "v1";

    private final RedisCommands<String, String> redisCommands;
    // 这里只做“sessionId -> userId”的读缓存，真正是否有效仍然要看 Redis。
    private final Cache<String, Long> l2Cache;
    private final String sessionKeyPrefix;
    private final String activeSessionKeyPrefix;
    private final Clock clock;
    private final Duration sessionTtl;
    // 同一个 userId 会落到同一把锁上，防止并发发号把版本号算乱。
    private final Object[] issueSessionStripes;

    /**
     * 用默认缓存和默认过期时间创建会话服务。
     */
    public SessionService(RedisCommands<String, String> redisCommands) {
        this(
            redisCommands,
            Caffeine.newBuilder().maximumSize(100_000).build()
        );
    }

    /**
     * 自定义本地缓存实现，其他参数仍然使用默认值。
     */
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

    /**
     * 自定义 session key 前缀，主要方便测试或隔离不同运行环境。
     */
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

    /**
     * 完整构造函数，允许测试精确控制 key 前缀、时钟和过期时间。
     */
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

    /**
     * 给用户签发一个新 session，并把旧 session 标记成已被替换。
     */
    public String issueSession(long userId) {
        synchronized (issueSessionStripe(userId)) {
            String sessionId = UUID.randomUUID().toString();
            ActiveSessionPointer previousActiveSession = readActiveSessionPointer(userId);
            // 同一个用户每次重新登录都把版本号加一，旧连接看到版本不匹配就会失效。
            long sessionVersion = previousActiveSession == null ? 1L : previousActiveSession.sessionVersion() + 1L;
            long expiresAtEpochMilli = clock.millis() + sessionTtl.toMillis();
            StoredSession newSession = StoredSession.active(sessionId, userId, sessionVersion, expiresAtEpochMilli);
            if (previousActiveSession != null) {
                // 新登录顶掉旧登录，旧 session 继续存在但状态改成 replaced，方便后续精确返回原因。
                markSessionReplaced(previousActiveSession.sessionId(), userId, previousActiveSession.sessionVersion());
                l2Cache.invalidate(previousActiveSession.sessionId());
            }
            redisCommands.set(redisKey(sessionId), serialize(newSession));
            // 这里保存的是“这个用户现在应该认哪一个 session/version”。
            redisCommands.set(activeSessionKey(userId), serialize(previousOrCurrent(sessionId, sessionVersion)));
            l2Cache.put(sessionId, userId);
            return sessionId;
        }
    }

    /**
     * 解析 session 当前是否有效、是否过期、是否已经被新登录替换。
     */
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
            // 过期后把 Redis 里的状态也写回去，后面再查能直接拿到明确结果。
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
        // 新格式 session 必须配套存在“当前活跃 session 指针”，缺了就说明这条记录已经不能再当真。
        if (storedSession.requiresActivePointer() && activeSessionPointer == null) {
            return replaceStoredSession(storedSession);
        }
        if (activeSessionPointer != null
            && (!sessionId.equals(activeSessionPointer.sessionId())
            || storedSession.sessionVersion() != activeSessionPointer.sessionVersion())) {
            // 指针已经指向别的 session 或更新版本，这条旧记录就要视为被替换。
            return replaceStoredSession(storedSession);
        }

        l2Cache.put(sessionId, storedSession.userId());
        return storedSession.asAuthority();
    }

    /**
     * 按 SessionResolver 接口约定，把解析结果转换成“可用 session 或空”。
     */
    @Override
    public Optional<ResolvedSession> resolveSession(String sessionId) {
        return resolveAuthority(sessionId).asResolvedSession();
    }

    /**
     * 主动撤销一个 session，并在需要时清理用户当前活跃指针。
     */
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

    /**
     * 拼出单条 session 记录的 Redis key。
     */
    private String redisKey(String sessionId) {
        return sessionKeyPrefix + sessionId;
    }

    /**
     * 拼出某个用户当前活跃 session 指针的 Redis key。
     */
    private String activeSessionKey(long userId) {
        return activeSessionKeyPrefix + userId;
    }

    /**
     * 从 Redis 读取一条 session 记录，并兼容历史上的旧格式。
     */
    private StoredSession readStoredSession(String sessionId) {
        String redisValue = redisCommands.get(redisKey(sessionId));
        if (redisValue == null) {
            return null;
        }
        try {
            // 旧格式只存了 userId，没有版本和过期时间，这里按兼容模式补齐默认值。
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

    /**
     * 读取某个用户当前正在生效的 session 指针。
     */
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

    /**
     * 把旧 session 写成 replaced，告诉后续调用方它是被新登录顶掉的。
     */
    private void markSessionReplaced(String sessionId, long userId, long sessionVersion) {
        StoredSession previousSession = readStoredSession(sessionId);
        StoredSession replaced = previousSession == null
            // 指针还在但旧记录缺失时，补一条 replaced 记录，保证结果仍然可解释。
            ? StoredSession.replaced(sessionId, userId, sessionVersion, clock.millis() + sessionTtl.toMillis())
            : previousSession.withStatus(SessionAuthorityStatus.REPLACED);
        redisCommands.set(redisKey(sessionId), serialize(replaced));
    }

    /**
     * 把一条旧 session 就地改成 replaced，并返回对应的权威状态。
     */
    private SessionAuthority replaceStoredSession(StoredSession storedSession) {
        StoredSession replaced = storedSession.withStatus(SessionAuthorityStatus.REPLACED);
        if (storedSession.status() != SessionAuthorityStatus.REPLACED) {
            redisCommands.set(redisKey(storedSession.sessionId()), serialize(replaced));
        }
        l2Cache.invalidate(storedSession.sessionId());
        return replaced.asAuthority();
    }

    /**
     * 只有当活跃指针仍然指向这条 session 时，才把它清掉，避免误删新登录的指针。
     */
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

    /**
     * 把单条 session 记录序列化成 Redis 字符串。
     */
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

    /**
     * 把用户当前活跃 session 指针序列化成 Redis 字符串。
     */
    private static String serialize(ActiveSessionPointer activeSessionPointer) {
        return activeSessionPointer.sessionId() + "|" + activeSessionPointer.sessionVersion();
    }

    /**
     * 构造当前应该写回去的活跃 session 指针。
     */
    private static ActiveSessionPointer previousOrCurrent(String sessionId, long sessionVersion) {
        return new ActiveSessionPointer(sessionId, sessionVersion);
    }

    /**
     * 根据 userId 选出对应的锁分桶。
     */
    private Object issueSessionStripe(long userId) {
        return issueSessionStripes[Math.floorMod(Long.hashCode(userId), issueSessionStripes.length)];
    }

    /**
     * 预先创建固定数量的锁对象，避免运行时反复分配。
     */
    private static Object[] createIssueSessionStripes() {
        Object[] stripes = new Object[ISSUE_SESSION_STRIPE_COUNT];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    /**
     * 判断字符串是不是有实际内容。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Redis 里的一条 session 记录。
     * `requiresActivePointer = false` 表示这是兼容旧格式补出来的记录。
     */
    private record StoredSession(
        SessionAuthorityStatus status,
        String sessionId,
        long userId,
        long sessionVersion,
        long expiresAtEpochMilli,
        boolean requiresActivePointer
    ) {
        /**
         * 创建一条当前有效的 session 记录。
         */
        private static StoredSession active(
            String sessionId,
            long userId,
            long sessionVersion,
            long expiresAtEpochMilli
        ) {
            return active(sessionId, userId, sessionVersion, expiresAtEpochMilli, true);
        }

        /**
         * 创建一条 session 记录，并指定它是否必须依赖活跃指针共同生效。
         */
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

        /**
         * 创建一条已经被新登录顶掉的 session 记录。
         */
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

        /**
         * 转成上层会用到的会话权威结果。
         */
        private SessionAuthority asAuthority() {
            return switch (status) {
                case ACTIVE -> SessionAuthority.active(sessionId, userId, sessionVersion);
                case INVALID -> SessionAuthority.invalid(sessionId);
                case EXPIRED -> SessionAuthority.expired(sessionId, userId, sessionVersion);
                case REPLACED -> SessionAuthority.replaced(sessionId, userId, sessionVersion);
            };
        }

        /**
         * 复制当前记录，只替换状态值。
         */
        private StoredSession withStatus(SessionAuthorityStatus resolvedStatus) {
            return new StoredSession(resolvedStatus, sessionId, userId, sessionVersion, expiresAtEpochMilli, requiresActivePointer);
        }
    }

    /**
     * 记录某个用户当前应该认哪一个 session 和版本号。
     */
    private record ActiveSessionPointer(String sessionId, long sessionVersion) {
    }
}
