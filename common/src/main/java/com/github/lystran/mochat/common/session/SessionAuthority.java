package com.github.lystran.mochat.common.session;

import java.util.Objects;
import java.util.Optional;

/**
 * 表示某个 session 当前的权威判断结果。
 */
public record SessionAuthority(
    SessionAuthorityStatus status,
    String sessionId,
    long userId,
    long sessionVersion
) {
    /**
     * 在创建时保证状态和值的基本完整性。
     */
    public SessionAuthority {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /**
     * 创建一条“session 当前可用”的判断结果。
     */
    public static SessionAuthority active(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.ACTIVE, sessionId, userId, sessionVersion);
    }

    /**
     * 创建一条“session 根本无效”的判断结果。
     */
    public static SessionAuthority invalid(String sessionId) {
        return new SessionAuthority(SessionAuthorityStatus.INVALID, sessionId == null ? "" : sessionId, 0L, 0L);
    }

    /**
     * 创建一条“session 已过期”的判断结果。
     */
    public static SessionAuthority expired(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.EXPIRED, sessionId, userId, sessionVersion);
    }

    /**
     * 创建一条“session 已被新登录顶掉”的判断结果。
     */
    public static SessionAuthority replaced(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.REPLACED, sessionId, userId, sessionVersion);
    }

    /**
     * 判断这条结果是不是“当前还可用”。
     */
    public boolean isActive() {
        return status == SessionAuthorityStatus.ACTIVE;
    }

    /**
     * 只在 session 还可用时，把结果转成后续链路可直接使用的对象。
     */
    public Optional<ResolvedSession> asResolvedSession() {
        if (!isActive()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSession(sessionId, userId, sessionVersion));
    }
}
