package com.github.lystran.mochat.common.session;

import java.util.Objects;

public record ResolvedSession(String sessionId, long userId, long sessionVersion) {
    public ResolvedSession {
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
