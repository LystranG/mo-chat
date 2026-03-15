package com.github.lystran.mochat.common.session;

import java.util.Optional;

/**
 * 负责把 sessionId 查成用户和版本信息。
 */
public interface SessionResolver {
    /**
     * 查出这条 session 当前归谁、版本是多少。
     */
    Optional<ResolvedSession> resolveSession(String sessionId);

    /**
     * 把原始查询结果整理成带状态的统一返回值。
     */
    default SessionAuthority resolveAuthority(String sessionId) {
        return resolveSession(sessionId)
            .map(resolvedSession -> SessionAuthority.active(
                resolvedSession.sessionId(),
                resolvedSession.userId(),
                resolvedSession.sessionVersion()
            ))
            .orElseGet(() -> SessionAuthority.invalid(sessionId));
    }

    /**
     * 只关心用户 id 时，直接从 session 查询结果里取出来。
     */
    default Optional<Long> resolveUserId(String sessionId) {
        return resolveSession(sessionId).map(ResolvedSession::userId);
    }
}
