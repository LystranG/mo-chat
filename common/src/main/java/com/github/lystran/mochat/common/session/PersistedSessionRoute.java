package com.github.lystran.mochat.common.session;

public record PersistedSessionRoute(long routeEpoch, ReplacedSessionRoute replacedRoute) {
    public PersistedSessionRoute(long routeEpoch) {
        this(routeEpoch, null);
    }

    public static PersistedSessionRoute none() {
        return new PersistedSessionRoute(0L, null);
    }
}
