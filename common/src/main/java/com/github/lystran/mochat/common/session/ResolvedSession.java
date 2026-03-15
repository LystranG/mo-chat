package com.github.lystran.mochat.common.session;

import java.util.Objects;

/**
 * 表示已经查清楚“这个 session 属于谁、版本号是多少”的结果。
 */
public record ResolvedSession(String sessionId, long userId, long sessionVersion) {
    /**
     * 在创建时保证 sessionId 不为空。
     */
    public ResolvedSession {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
