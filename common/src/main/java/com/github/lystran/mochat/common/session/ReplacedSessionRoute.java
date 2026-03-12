package com.github.lystran.mochat.common.session;

public record ReplacedSessionRoute(
    String gatewayPod,
    String connectionId,
    String sessionId,
    long sessionVersion,
    long routeEpoch
) {
}
