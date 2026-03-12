package com.github.lystran.mochat.common.session;

public interface SessionReplacementHandler {
    void handleReplacement(ResolvedSession newBinding, PersistedSessionRoute persistedRoute);

    static SessionReplacementHandler noop() {
        return (newBinding, persistedRoute) -> {
        };
    }
}
