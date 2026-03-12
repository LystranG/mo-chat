package com.github.lystran.mochat.accessgateway.runtime;

public record LocalConnectionStateSnapshot(
    String sessionId,
    long sessionVersion,
    long routeEpoch,
    boolean activeRouteOwner
) {
}
