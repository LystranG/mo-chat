package com.github.lystran.mochat.common.session;

import java.util.Optional;

public interface SessionResolver {
    Optional<ResolvedSession> resolveSession(String sessionId);

    default SessionAuthority resolveAuthority(String sessionId) {
        return resolveSession(sessionId)
            .map(resolvedSession -> SessionAuthority.active(
                resolvedSession.sessionId(),
                resolvedSession.userId(),
                resolvedSession.sessionVersion()
            ))
            .orElseGet(() -> SessionAuthority.invalid(sessionId));
    }

    default Optional<Long> resolveUserId(String sessionId) {
        return resolveSession(sessionId).map(ResolvedSession::userId);
    }
}
