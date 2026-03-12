package com.github.lystran.mochat.common.session;

public interface SessionRouteWriter<C> {
    PersistedSessionRoute writeRoute(ResolvedSession resolvedSession, C channelRef);

    default boolean renewRoute(ResolvedSession resolvedSession, C channelRef, PersistedSessionRoute persistedRoute) {
        return true;
    }

    default boolean clearRoute(ResolvedSession resolvedSession, C channelRef, PersistedSessionRoute persistedRoute) {
        return false;
    }

    static <C> SessionRouteWriter<C> noop() {
        return (resolvedSession, channelRef) -> PersistedSessionRoute.none();
    }
}
