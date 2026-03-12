package com.github.lystran.mochat.common.session;

import java.util.Objects;
import java.util.Optional;

public record SessionAuthority(
    SessionAuthorityStatus status,
    String sessionId,
    long userId,
    long sessionVersion
) {
    public SessionAuthority {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    public static SessionAuthority active(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.ACTIVE, sessionId, userId, sessionVersion);
    }

    public static SessionAuthority invalid(String sessionId) {
        return new SessionAuthority(SessionAuthorityStatus.INVALID, sessionId == null ? "" : sessionId, 0L, 0L);
    }

    public static SessionAuthority expired(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.EXPIRED, sessionId, userId, sessionVersion);
    }

    public static SessionAuthority replaced(String sessionId, long userId, long sessionVersion) {
        return new SessionAuthority(SessionAuthorityStatus.REPLACED, sessionId, userId, sessionVersion);
    }

    public boolean isActive() {
        return status == SessionAuthorityStatus.ACTIVE;
    }

    public Optional<ResolvedSession> asResolvedSession() {
        if (!isActive()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedSession(sessionId, userId, sessionVersion));
    }
}
